package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.config.CacheKeyConfig;
import com.dating.post.constant.PostStatus;
import com.dating.post.constant.RedisKeys;
import com.dating.post.entity.Post;
import com.dating.post.entity.PostImage;
import com.dating.post.mapper.PostImageMapper;
import com.dating.post.mapper.PostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子主聚合(post + post_image)的读写 + 详情缓存。
 * <p>
 * 设计约束(red lines 1 / 10 / 11):
 * <ul>
 *   <li>所有查询单表,跨表在内存拼装</li>
 *   <li>不持有 service 依赖(只接受 mapper / redis)</li>
 *   <li>Redis 详情 7 天 TTL,失效后从 DB 重建</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PostManager {

    private static final Duration DETAIL_TTL = Duration.ofDays(7);

    private final PostMapper postMapper;
    private final PostImageMapper postImageMapper;
    private final StringRedisTemplate redis;
    private final CacheKeyConfig cacheKeyConfig;

    /** 主表 + 图片表写入,事务由 service 层 {@link Transactional} 包。 */
    public void insertPostWithImages(Post post, List<PostImage> images) {
        postMapper.insert(post);
        for (PostImage image : images) {
            postImageMapper.insert(image);
        }
    }

    /** 按业务主键 post_id 查未删的帖子。 */
    public Optional<Post> findByPostId(long postId) {
        Post post = postMapper.selectOne(
                new LambdaQueryWrapper<Post>()
                        .eq(Post::getPostId, postId)
                        .eq(Post::getStatus, PostStatus.NORMAL)
                        .last("LIMIT 1")
        );
        return Optional.ofNullable(post);
    }

    /** 仅查存在性(不校验 status),用于权限校验。 */
    public Optional<Post> findRawByPostId(long postId) {
        return Optional.ofNullable(postMapper.selectOne(
                new LambdaQueryWrapper<Post>()
                        .eq(Post::getPostId, postId)
                        .last("LIMIT 1")
        ));
    }

    /** 批量查帖子(FeedService / FeedScoreJob 拼装用)。 */
    public List<Post> listByPostIds(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Collections.emptyList();
        }
        return postMapper.selectList(
                new LambdaQueryWrapper<Post>()
                        .in(Post::getPostId, postIds)
                        .eq(Post::getStatus, PostStatus.NORMAL)
        );
    }

    /** 「TA 发的帖」游标分页(按 post_id 倒序,即雪花 ID 倒序 ≈ 时间倒序)。 */
    public List<Post> listUserPosts(long userId, long cursor, int pageSize) {
        LambdaQueryWrapper<Post> w = new LambdaQueryWrapper<Post>()
                .eq(Post::getUserId, userId)
                .eq(Post::getStatus, PostStatus.NORMAL)
                .orderByDesc(Post::getPostId)
                .last("LIMIT " + pageSize);
        if (cursor > 0) {
            w.lt(Post::getPostId, cursor);
        }
        return postMapper.selectList(w);
    }

    /** 近 N 天所有正常帖(FeedScoreJob 全量重建池用)。 */
    public List<Post> listRecentPosts(OffsetDateTime since) {
        return postMapper.selectList(
                new LambdaQueryWrapper<Post>()
                        .ge(Post::getCreatedAt, since)
                        .eq(Post::getStatus, PostStatus.NORMAL)
        );
    }

    /** 按 post_id 批量取图片,在 service 内存里 join。 */
    public Map<Long, List<PostImage>> loadImages(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<PostImage> all = postImageMapper.selectList(
                new LambdaQueryWrapper<PostImage>()
                        .in(PostImage::getPostId, postIds)
                        .orderByAsc(PostImage::getSortOrder)
        );
        Map<Long, List<PostImage>> grouped = new HashMap<>();
        for (PostImage img : all) {
            grouped.computeIfAbsent(img.getPostId(), k -> new ArrayList<>()).add(img);
        }
        return grouped;
    }

    /** 单帖图片。 */
    public List<PostImage> loadImages(long postId) {
        return postImageMapper.selectList(
                new LambdaQueryWrapper<PostImage>()
                        .eq(PostImage::getPostId, postId)
                        .orderByAsc(PostImage::getSortOrder)
        );
    }

    /** 逻辑删除帖子。 */
    public int logicalDelete(long postId, long userId) {
        return postMapper.delete(
                new LambdaQueryWrapper<Post>()
                        .eq(Post::getPostId, postId)
                        .eq(Post::getUserId, userId)
        );
    }

    /** 详情缓存淘汰(cache aside:先写库再删缓存)。 */
    public void evictDetailCache(long postId) {
        redis.delete(RedisKeys.postDetail(cacheKeyConfig.getKeyPrefix(), postId));
    }

    public String detailCacheKey(long postId) {
        return RedisKeys.postDetail(cacheKeyConfig.getKeyPrefix(), postId);
    }

    public Duration detailTtl() {
        return DETAIL_TTL;
    }
}
