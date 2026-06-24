package com.dating.post.service;

import com.dating.post.constant.LikeStatus;
import com.dating.post.manager.PostLikeManager;
import com.dating.post.manager.PostStatManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 点赞 / 取消(post-service-design §9.3)。
 * <p>
 * 写路径:
 * <ol>
 *   <li>post_likes upsert(用户级幂等记录,联合主键 + ON CONFLICT)</li>
 *   <li>影响行数 0 表示已是目标状态,直接 return(幂等)</li>
 *   <li>影响行数 1 表示状态真变了 → Redis INCR/DECR + SADD updated_set</li>
 * </ol>
 * 1000 并发点赞总耗时 ~50ms,且**不碰 PG 的 post_stats 行**(写合并 / Write Coalescing)。
 */
@Service
@RequiredArgsConstructor
public class LikeService {

    private static final Logger log = LoggerFactory.getLogger(LikeService.class);

    private final PostLikeManager postLikeManager;
    private final PostStatManager postStatManager;

    /**
     * 点赞 / 取消。幂等。
     *
     * @param liked true = 点赞 / false = 取消
     */
    public void action(long userId, long postId, boolean liked) {
        int target = liked ? LikeStatus.LIKED : LikeStatus.UNLIKED;
        boolean changed = postLikeManager.upsert(userId, postId, target);
        if (!changed) {
            // 已是目标状态,幂等返回
            return;
        }

        int delta = liked ? 1 : -1;
        postStatManager.applyLikeDelta(postId, delta);
        log.info("Like action: userId={} postId={} liked={}", userId, postId, liked);
    }
}
