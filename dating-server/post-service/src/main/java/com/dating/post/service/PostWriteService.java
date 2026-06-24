package com.dating.post.service;

import com.dating.post.client.UserClient;
import com.dating.post.config.CacheKeyConfig;
import com.dating.post.config.SnowflakeIdGenerator;
import com.dating.post.constant.ErrorCode;
import com.dating.post.constant.PostStatus;
import com.dating.post.constant.RedisKeys;
import com.dating.post.entity.Post;
import com.dating.post.entity.PostImage;
import com.dating.post.exception.BizException;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 发帖 / 删帖业务(post-service-design §9.1 / §9.2)。
 * <p>
 * 事务边界:**只覆盖 DB 写**(posts + post_images + post_stats);
 * Redis 写、冷启动池写、写扩散都是事务外的 best-effort,失败不回滚 DB
 * —— 帖子已落库,后续 5 分钟池重建会兜底。
 */
@Service
@RequiredArgsConstructor
public class PostWriteService {

    private static final Logger log = LoggerFactory.getLogger(PostWriteService.class);

    private static final int CONTENT_MAX = 1024;
    private static final int IMAGE_MAX = 9;
    private static final Duration COLD_START_TTL = Duration.ofDays(7);
    private static final long COLD_START_CAP = 10_000L;

    private final PostManager postManager;
    private final PostStatManager postStatManager;
    private final PostFanoutService postFanoutService;
    private final SnowflakeIdGenerator idGenerator;
    private final UserClient userClient;
    private final StringRedisTemplate redis;
    private final CacheKeyConfig cacheKeyConfig;

    /**
     * 发帖完整流程。
     *
     * @return 新帖业务主键 post_id
     */
    public long createPost(long userId, String content, List<String> imageKeys) {
        // 1. 入参校验:content 非空 ≤ 1024;图片数 ≤ 9 且 key 非空
        validateContent(content);
        validateImages(imageKeys);

        long postId = idGenerator.nextId();
        OffsetDateTime now = OffsetDateTime.now();

        Post post = new Post();
        post.setPostId(postId);
        post.setUserId(userId);
        post.setContent(content);
        post.setStatus(PostStatus.NORMAL);
        post.setDeleted(0);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);

        List<PostImage> images = buildImages(postId, imageKeys, now);

        // 2. 事务内:posts + post_images + post_stats 三表 INSERT
        insertTx(post, images);

        // 3. 事务外 best-effort:冷启动池 + 写扩散(失败仅 log,不抛)
        try {
            zaddColdStart(postId, userId, now);
        } catch (Exception e) {
            log.warn("Cold-start ZADD failed, postId={}", postId, e);
        }

        try {
            postFanoutService.fanoutToFollowers(postId, userId, now);
        } catch (Exception e) {
            log.warn("Fanout dispatch failed, postId={}", postId, e);
        }

        log.info("Post created: postId={} userId={} images={}", postId, userId, images.size());
        return postId;
    }

    /** 删帖:权限校验 + 逻辑删 + 清详情缓存 + 冷启动池移除。 */
    @Transactional(rollbackFor = Exception.class)
    public void deletePost(long userId, long postId) {
        // 1. SELECT 校验存在 + 是本人
        Post post = postManager.findRawByPostId(postId)
                .orElseThrow(() -> BizException.postNotFound(postId));
        if (!post.getUserId().equals(userId)) {
            throw BizException.forbidden("非帖子作者无法删除");
        }

        // 2. 逻辑删
        postManager.logicalDelete(postId, userId);

        // 3. 事务后操作放 commit 后执行(简单起见这里同步做,失败仅警告)
        try {
            postManager.evictDetailCache(postId);
            zremColdStart(postId, post.getUserId());
            redis.opsForSet().remove(
                    RedisKeys.postUpdatedSet(cacheKeyConfig.getKeyPrefix()),
                    String.valueOf(postId)
            );
        } catch (Exception e) {
            log.warn("Post delete side-effect failed, postId={}", postId, e);
        }

        log.info("Post deleted: postId={} userId={}", postId, userId);
    }

    // ------------------------------------------------------------------
    // 事务边界拆出来,便于 readability
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    protected void insertTx(Post post, List<PostImage> images) {
        postManager.insertPostWithImages(post, images);
        postStatManager.initStat(post.getPostId());
    }

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.CONTENT_EMPTY, "content 不能为空");
        }
        if (content.length() > CONTENT_MAX) {
            throw new BizException(ErrorCode.CONTENT_TOO_LONG,
                    "content 长度超限: " + content.length() + " > " + CONTENT_MAX);
        }
    }

    private void validateImages(List<String> imageKeys) {
        if (imageKeys == null) {
            return;
        }
        if (imageKeys.size() > IMAGE_MAX) {
            throw new BizException(ErrorCode.IMAGE_COUNT_EXCEEDED,
                    "图片数量超限: " + imageKeys.size() + " > " + IMAGE_MAX);
        }
        for (String key : imageKeys) {
            if (key == null || key.isBlank()) {
                throw new BizException(ErrorCode.IMAGE_KEY_EMPTY, "image_key 不能为空");
            }
        }
    }

    private List<PostImage> buildImages(long postId, List<String> imageKeys, OffsetDateTime now) {
        List<PostImage> images = new ArrayList<>();
        if (imageKeys == null) {
            return images;
        }
        for (int i = 0; i < imageKeys.size(); i++) {
            PostImage img = new PostImage();
            img.setPostId(postId);
            img.setSortOrder(i);
            img.setImageKey(imageKeys.get(i));
            img.setCreatedAt(now);
            images.add(img);
        }
        return images;
    }

    private void zaddColdStart(long postId, long userId, OffsetDateTime now) {
        boolean male = userClient.isMale(userId);
        String key = RedisKeys.feedColdStartPool(cacheKeyConfig.getKeyPrefix(), male);
        double score = now.toEpochSecond();
        redis.opsForZSet().add(key, String.valueOf(postId), score);
        redis.expire(key, COLD_START_TTL);
        Long size = redis.opsForZSet().zCard(key);
        if (size != null && size > COLD_START_CAP) {
            redis.opsForZSet().removeRange(key, 0, size - COLD_START_CAP - 1);
        }
    }

    private void zremColdStart(long postId, long authorUserId) {
        boolean male = userClient.isMale(authorUserId);
        redis.opsForZSet().remove(
                RedisKeys.feedColdStartPool(cacheKeyConfig.getKeyPrefix(), male),
                String.valueOf(postId)
        );
    }
}
