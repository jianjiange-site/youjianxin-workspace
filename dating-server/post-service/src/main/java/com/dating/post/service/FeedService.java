package com.dating.post.service;

import com.dating.common.proto.Pagination;
import com.dating.post.client.UserClient;
import com.dating.post.config.CacheKeyConfig;
import com.dating.post.constant.RedisKeys;
import com.dating.post.entity.Post;
import com.dating.post.entity.PostStat;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import com.dating.post.proto.PostInfo;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐 Feed:池重建 + 三路混合(post-service-design §10)。
 * <p>
 * 三路池(详见 design §10.2):
 * <ul>
 *   <li>① 全网热门池(pull / FeedScoreJob 5 分钟重建,Hacker News 热度打分)</li>
 *   <li>② 好友时间线(push / 发帖经 RocketMQ Consumer 写扩散,纯时间倒序)</li>
 *   <li>③ 冷启动池(发帖时同步 ZADD,纯时间倒序)</li>
 * </ul>
 * 10 条一页位置分配 + 布隆去重 + 同好友频控见 §10.3。
 */
@Service
@RequiredArgsConstructor
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    /** Hacker News 变体打分参数。 */
    private static final double W_BASE = 10.0;
    private static final double ALPHA = 1.0;
    private static final double BETA = 3.0;
    private static final double DECAY_POWER = 1.5;

    private static final int POOL_CAP = 3000;
    private static final Duration POOL_TTL = Duration.ofDays(7);
    private static final Duration BLOOM_TTL = Duration.ofDays(7);

    private final PostManager postManager;
    private final PostStatManager postStatManager;
    private final PostReadService postReadService;
    private final UserClient userClient;
    private final StringRedisTemplate redis;
    private final RedissonClient redissonClient;
    private final CacheKeyConfig cacheKeyConfig;

    // ===================================================================
    // 池重建(FeedScoreJob 调用)
    // ===================================================================

    /**
     * 重建全网热门池(design §10.2.1):
     * <pre>
     * 1. 捞近 3 天 posts(单表)
     * 2. selectBatchIds 取 post_stats(单表)
     * 3. 读 Redis incr 补偿实时计数
     * 4. Hacker News 内存打分
     * 5. 批量取性别(必须 batch,避免压垮 user-service)
     * 6. 影子写 tmp ZSet + 裁剪到 3000 + EXPIRE 7d
     * 7. RENAME tmp → 正式 key(原子,读侧无半写)
     * </pre>
     */
    public void rebuildRecommendPool() {
        OffsetDateTime since = OffsetDateTime.now().minusDays(3);
        List<Post> posts = postManager.listRecentPosts(since);
        if (posts.isEmpty()) {
            log.info("Feed pool rebuild skipped: no recent posts");
            return;
        }

        List<Long> postIds = posts.stream().map(Post::getPostId).toList();
        Map<Long, PostStat> statMap = postStatManager.batchGet(postIds);

        // 性别批量取
        Set<Long> userIds = new HashSet<>();
        for (Post p : posts) {
            userIds.add(p.getUserId());
        }
        Map<Long, Boolean> genderMap = userClient.getGenders(userIds);

        Map<Long, Double> maleBucket = new HashMap<>();
        Map<Long, Double> femaleBucket = new HashMap<>();
        long nowEpoch = OffsetDateTime.now().toEpochSecond();

        for (Post p : posts) {
            long pid = p.getPostId();
            PostStat stat = statMap.get(pid);
            long like = (stat == null ? 0 : stat.getLikeCount()) + postStatManager.readLikeIncr(pid);
            long comment = (stat == null ? 0 : stat.getCommentCount())
                    + postStatManager.readCommentIncr(pid);

            double hoursDiff = Math.max(0,
                    (nowEpoch - p.getCreatedAt().toEpochSecond()) / 3600.0);
            double score = (W_BASE + ALPHA * like + BETA * comment)
                    / Math.pow(hoursDiff + 2, DECAY_POWER);

            boolean male = Boolean.TRUE.equals(genderMap.get(p.getUserId()));
            if (male) {
                maleBucket.put(pid, score);
            } else {
                femaleBucket.put(pid, score);
            }
        }

        // 影子写 tmp,完成后 RENAME 切换
        String prefix = cacheKeyConfig.getKeyPrefix();
        writeBucket(maleBucket,
                RedisKeys.feedPoolRecommendTmp(prefix, true),
                RedisKeys.feedPoolRecommend(prefix, true));
        writeBucket(femaleBucket,
                RedisKeys.feedPoolRecommendTmp(prefix, false),
                RedisKeys.feedPoolRecommend(prefix, false));

        log.info("Feed pool rebuilt, candidates={} male={} female={}",
                posts.size(), maleBucket.size(), femaleBucket.size());
    }

    private void writeBucket(Map<Long, Double> bucket, String tmpKey, String finalKey) {
        redis.delete(tmpKey);
        if (bucket.isEmpty()) {
            // 没数据也要 RENAME 一个空 key 覆盖,免得读到陈旧的旧池
            redis.opsForZSet().add(tmpKey, "0", 0); // 占位
            redis.opsForZSet().remove(tmpKey, "0");
            // 上面操作产生不了 key,直接删旧
            redis.delete(finalKey);
            return;
        }
        for (Map.Entry<Long, Double> e : bucket.entrySet()) {
            redis.opsForZSet().add(tmpKey, String.valueOf(e.getKey()), e.getValue());
        }
        Long size = redis.opsForZSet().zCard(tmpKey);
        if (size != null && size > POOL_CAP) {
            redis.opsForZSet().removeRange(tmpKey, 0, size - POOL_CAP - 1);
        }
        redis.expire(tmpKey, POOL_TTL);
        // 原子 RENAME 切换
        redis.rename(tmpKey, finalKey);
    }

    // ===================================================================
    // 读 Feed(三路混合)
    // ===================================================================

    /**
     * 推荐 Feed(design §9.8 / §10.3)。
     *
     * @param currentUserId 当前用户(必填)
     * @param cursor        "recOffset:csOffset",首次传 "0:0" 或空串
     * @param pageSize      每页条数(典型 10)
     */
    public PostReadService.PageResult<PostInfo> getRecommendFeed(long currentUserId,
                                                                  String cursor,
                                                                  int pageSize) {
        long[] offsets = parseCursor(cursor);
        long recOffset = offsets[0];
        long csOffset = offsets[1];

        // 异性优先:男看女池,女看男池
        boolean userMale = userClient.isMale(currentUserId);
        boolean targetMale = !userMale;
        String prefix = cacheKeyConfig.getKeyPrefix();

        // 三路取数(偏多取一些做兜底)
        List<Long> recommendIds = zrevrange(
                RedisKeys.feedPoolRecommend(prefix, targetMale), recOffset, recOffset + 30);
        List<Long> friendIds = zrevrange(
                RedisKeys.userTimeline(prefix, currentUserId), 0, 5);
        List<Long> coldStartIds = zrevrange(
                RedisKeys.feedColdStartPool(prefix, targetMale), csOffset, csOffset + 10);

        // 布隆去重
        RBloomFilter<String> bloom = redissonClient.getBloomFilter(
                RedisKeys.userReadBloom(prefix, currentUserId));
        if (!bloom.isExists()) {
            bloom.tryInit(5000L, 0.01);
            // bloom 没有显式 TTL 接口,Redisson 内部用普通 key,这里 expire 一下
            redis.expire(RedisKeys.userReadBloom(prefix, currentUserId), BLOOM_TTL);
        }

        recommendIds = filterBloom(recommendIds, bloom);
        friendIds = filterBloom(friendIds, bloom);
        coldStartIds = filterBloom(coldStartIds, bloom);

        // 混排(同好友 1 条/页频控)
        List<Long> ordered = mergeThreeWay(recommendIds, friendIds, coldStartIds, pageSize);

        // 拼装详情(自动跳过已删的 post_id)
        List<PostInfo> items = postReadService.assembleFeedItems(currentUserId, ordered);

        // 把返回的 id 加入布隆
        for (PostInfo info : items) {
            bloom.add(String.valueOf(info.getPostId()));
        }

        // 下一页游标
        Pagination pagination = Pagination.newBuilder()
                .setNextCursor((recOffset + pageSize) + ":" + (csOffset + 1))
                .setHasMore(!items.isEmpty())
                .build();
        return new PostReadService.PageResult<>(items, pagination);
    }

    private List<Long> filterBloom(List<Long> ids, RBloomFilter<String> bloom) {
        if (ids.isEmpty()) {
            return ids;
        }
        List<Long> kept = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (!bloom.contains(String.valueOf(id))) {
                kept.add(id);
            }
        }
        return kept;
    }

    /**
     * 三路混排(design §10.3):
     * <pre>
     * 位置 1,2,4,5,7,8,9,10 → recommend(降级 cold_start → friend)
     * 位置 3              → friend(降级 recommend)
     * 位置 6              → cold_start(降级 recommend)
     * </pre>
     * 同好友 1 条/页,顺序去重(LinkedHashSet)。
     */
    List<Long> mergeThreeWay(List<Long> recommend, List<Long> friend,
                             List<Long> coldStart, int pageSize) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        int recIdx = 0, friendIdx = 0, csIdx = 0;

        for (int pos = 1; pos <= pageSize; pos++) {
            Long picked;
            if (pos == 3) {
                picked = pick(friend, friendIdx);
                if (picked != null) {
                    friendIdx++;
                } else {
                    picked = pick(recommend, recIdx);
                    if (picked != null) recIdx++;
                }
            } else if (pos == 6) {
                picked = pick(coldStart, csIdx);
                if (picked != null) {
                    csIdx++;
                } else {
                    picked = pick(recommend, recIdx);
                    if (picked != null) recIdx++;
                }
            } else {
                picked = pick(recommend, recIdx);
                if (picked != null) {
                    recIdx++;
                } else {
                    picked = pick(coldStart, csIdx);
                    if (picked != null) {
                        csIdx++;
                    } else {
                        picked = pick(friend, friendIdx);
                        if (picked != null) friendIdx++;
                    }
                }
            }
            if (picked != null) {
                result.add(picked);
            }
        }
        return new ArrayList<>(result);
    }

    private Long pick(List<Long> src, int idx) {
        return idx < src.size() ? src.get(idx) : null;
    }

    private List<Long> zrevrange(String key, long start, long end) {
        Set<String> ids = redis.opsForZSet().reverseRange(key, start, end);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return ids.stream().map(Long::parseLong).toList();
    }

    private long[] parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank() || "0:0".equals(cursor)) {
            return new long[]{0L, 0L};
        }
        try {
            String[] parts = cursor.split(":", 2);
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (RuntimeException e) {
            return new long[]{0L, 0L};
        }
    }
}
