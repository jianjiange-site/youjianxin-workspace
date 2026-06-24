package com.dating.post.constant;

/**
 * Redis key 模板(post-service-design §6.1)。
 * <p>
 * 第一段 prefix 由 {@code app.cache.key-prefix} 注入(默认 {@code youjianxin}),
 * 业务代码统一通过本类拼 key,**禁止散落硬编码**(student-dev-guide §6.2)。
 * <p>
 * 实例化时通过 {@link com.dating.post.config.CacheKeyConfig} 注入 prefix,这里只提供静态模板。
 */
public final class RedisKeys {

    /** 帖子详情 Hash。 */
    public static String postDetail(String prefix, long postId) {
        return prefix + ":post:detail:" + postId;
    }

    /** 帖子点赞未刷盘增量(可正可负)。 */
    public static String postLikeIncr(String prefix, long postId) {
        return prefix + ":post:stat:incr:" + postId + ":likes";
    }

    /** 帖子评论未刷盘增量。 */
    public static String postCommentIncr(String prefix, long postId) {
        return prefix + ":post:stat:incr:" + postId + ":comments";
    }

    /** 评论 ZSet,只保留最新 200 条窗口(score = comment_id)。 */
    public static String postCommentsZset(String prefix, long postId) {
        return prefix + ":post:comments:" + postId;
    }

    /** 待刷盘 post_id 集合(LikeFlushJob / CommentFlushJob 消费)。 */
    public static String postUpdatedSet(String prefix) {
        return prefix + ":post:updated_set";
    }

    /** 用户时间线(写扩散),score = epoch 秒,最多 100 条。 */
    public static String userTimeline(String prefix, long userId) {
        return prefix + ":user:timeline:" + userId;
    }

    /** 全网热门池(按发帖人性别分桶)。 */
    public static String feedPoolRecommend(String prefix, boolean male) {
        return prefix + ":feed:pool:recommend:" + (male ? "male" : "female");
    }

    /** 全网热门池影子写 tmp(FeedScoreJob 重建用,RENAME 后覆盖正式 key)。 */
    public static String feedPoolRecommendTmp(String prefix, boolean male) {
        return prefix + ":feed:pool:recommend:" + (male ? "male" : "female") + ":tmp";
    }

    /** 冷启动池。 */
    public static String feedColdStartPool(String prefix, boolean male) {
        return prefix + ":feed:cold_start:pool:" + (male ? "male" : "female");
    }

    /** 已读去重 BloomFilter。 */
    public static String userReadBloom(String prefix, long userId) {
        return prefix + ":user:read:bloom:" + userId;
    }

    /** 分布式锁通用前缀。 */
    public static String lock(String prefix, String name) {
        return prefix + ":lock:post:" + name;
    }

    private RedisKeys() {
    }
}
