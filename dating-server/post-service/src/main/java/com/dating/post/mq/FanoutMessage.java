package com.dating.post.mq;

/**
 * Fanout 消息体(post-service-design §10.2.2)。
 * <p>
 * 只塞索引信息,不预拉 followers —— 关注列表动态变,以 Consumer 时点为准更新鲜;
 * msg 体积小,Broker 落盘 / 网络传输开销低。
 *
 * @param postId          帖子业务主键
 * @param authorUserId    发帖人 user_id
 * @param createdAtEpoch  发帖时间 epoch 秒,Consumer 写 timeline ZSet 的 score
 */
public record FanoutMessage(long postId, long authorUserId, long createdAtEpoch) {
}
