package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.config.CacheKeyConfig;
import com.dating.post.constant.RedisKeys;
import com.dating.post.entity.PostComment;
import com.dating.post.mapper.PostCommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 评论读写 + 最新 200 条 ZSet 窗口(post-service-design §6.3 / §9.6)。
 * <p>
 * 90% 用户只看最新评论的前几屏,ZSet 挡住绝大多数读流量。
 * 翻到 200 之外才回源 DB(走 {@code (post_id, root_id, created_at DESC)} 索引);
 * **回源不回写 ZSet**(回写老数据会破坏裁剪 invariant)。
 */
@Component
@RequiredArgsConstructor
public class PostCommentManager {

    private static final Duration ZSET_TTL = Duration.ofDays(7);
    private static final int ZSET_WINDOW = 200;

    private final PostCommentMapper postCommentMapper;
    private final StringRedisTemplate redis;
    private final CacheKeyConfig cacheKeyConfig;

    public void insert(PostComment comment) {
        postCommentMapper.insert(comment);
    }

    public Optional<PostComment> findByCommentId(long commentId) {
        PostComment c = postCommentMapper.selectOne(
                new LambdaQueryWrapper<PostComment>()
                        .eq(PostComment::getCommentId, commentId)
                        .last("LIMIT 1")
        );
        return Optional.ofNullable(c);
    }

    public int logicalDelete(long commentId, long userId) {
        return postCommentMapper.delete(
                new LambdaQueryWrapper<PostComment>()
                        .eq(PostComment::getCommentId, commentId)
                        .eq(PostComment::getUserId, userId)
        );
    }

    /** 一级评论分页(root_id = 0),热路径优先走 ZSet。 */
    public List<PostComment> listTopLevel(long postId, long cursor, int pageSize) {
        String zsetKey = RedisKeys.postCommentsZset(cacheKeyConfig.getKeyPrefix(), postId);

        // cursor=0 视为首页,走 ZSet 倒序前 N 条
        Set<String> ids = (cursor > 0)
                ? redis.opsForZSet().reverseRangeByScore(
                        zsetKey, Double.NEGATIVE_INFINITY, cursor - 1, 0, pageSize)
                : redis.opsForZSet().reverseRange(zsetKey, 0, pageSize - 1);

        if (ids != null && !ids.isEmpty()) {
            List<Long> commentIds = ids.stream().map(Long::parseLong).toList();
            // 按 ZSet 取出的 id 拼回顺序,保证返回顺序与 score 倒序一致
            List<PostComment> comments = postCommentMapper.selectList(
                    new LambdaQueryWrapper<PostComment>()
                            .in(PostComment::getCommentId, commentIds)
            );
            return reorderByIds(comments, commentIds);
        }

        // ZSet 空(冷帖或翻到 200 之外)→ DB 回源
        LambdaQueryWrapper<PostComment> w = new LambdaQueryWrapper<PostComment>()
                .eq(PostComment::getPostId, postId)
                .eq(PostComment::getRootId, 0L)
                .orderByDesc(PostComment::getCreatedAt)
                .last("LIMIT " + pageSize);
        if (cursor > 0) {
            w.lt(PostComment::getCommentId, cursor);
        }
        return postCommentMapper.selectList(w);
    }

    /** 新评论入 ZSet + 裁剪到 200 + 续期。 */
    public void addToZsetAndTrim(long postId, long commentId) {
        String key = RedisKeys.postCommentsZset(cacheKeyConfig.getKeyPrefix(), postId);
        ZSetOperations<String, String> z = redis.opsForZSet();
        z.add(key, String.valueOf(commentId), commentId);
        Long size = z.zCard(key);
        if (size != null && size > ZSET_WINDOW) {
            z.removeRange(key, 0, size - ZSET_WINDOW - 1);
        }
        redis.expire(key, ZSET_TTL);
    }

    /** 删评论后 ZSet 也移除(不影响 200 条窗口语义)。 */
    public void removeFromZset(long postId, long commentId) {
        String key = RedisKeys.postCommentsZset(cacheKeyConfig.getKeyPrefix(), postId);
        redis.opsForZSet().remove(key, String.valueOf(commentId));
    }

    private static List<PostComment> reorderByIds(List<PostComment> source, List<Long> orderIds) {
        if (source.isEmpty()) {
            return Collections.emptyList();
        }
        List<PostComment> result = new ArrayList<>(source.size());
        for (Long id : orderIds) {
            for (PostComment c : source) {
                if (id.equals(c.getCommentId())) {
                    result.add(c);
                    break;
                }
            }
        }
        return result;
    }
}
