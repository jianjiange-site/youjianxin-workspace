package com.dating.post.service;

import com.dating.youjianxin.proto.common.BaseResponse;
import com.dating.youjianxin.proto.common.Pagination;
import com.dating.post.entity.Post;
import com.dating.post.entity.PostImage;
import com.dating.post.exception.BizException;
import com.dating.post.manager.PostCommentManager;
import com.dating.post.manager.PostLikeManager;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import com.dating.youjianxin.proto.post.PostInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读路径:帖子详情、TA 的帖子列表、内部 PostInfo 拼装。
 * <p>
 * 设计要点:
 * <ul>
 *   <li>实时计数 = post_stats(底座) + Redis 增量(详见 design §6.2.3)</li>
 *   <li>批量拼装走 selectBatchIds + 内存 join(红线 1)</li>
 *   <li>当前调用方 user_id 为 0 时,liked 字段恒 false</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PostReadService {

    private final PostManager postManager;
    private final PostStatManager postStatManager;
    private final PostLikeManager postLikeManager;

    /** 帖子详情。 */
    public PostInfo getPostDetail(long currentUserId, long postId) {
        Post post = postManager.findByPostId(postId)
                .orElseThrow(() -> BizException.postNotFound(postId));
        List<PostImage> images = postManager.loadImages(postId);

        long[] incr = postStatManager.readIncr(postId);
        var stats = postStatManager.batchGet(List.of(postId)).get(postId);
        long baseLike = stats == null ? 0 : stats.getLikeCount();
        long baseComment = stats == null ? 0 : stats.getCommentCount();

        boolean liked = currentUserId > 0 && postLikeManager.isLiked(currentUserId, postId);

        return buildInfo(post, images, baseLike + incr[0], baseComment + incr[1], liked);
    }

    /** TA 的帖子列表(游标分页)。 */
    public PageResult<PostInfo> listUserPosts(long currentUserId,
                                              long targetUserId,
                                              long cursor,
                                              int pageSize) {
        List<Post> posts = postManager.listUserPosts(targetUserId, cursor, pageSize);
        if (posts.isEmpty()) {
            return new PageResult<>(Collections.emptyList(), Pagination.newBuilder()
                    .setNextCursor("").setHasMore(false).build());
        }

        List<Long> postIds = posts.stream().map(Post::getPostId).toList();
        Map<Long, List<PostImage>> imageMap = postManager.loadImages(postIds);
        Map<Long, com.dating.post.entity.PostStat> statMap = postStatManager.batchGet(postIds);

        // 当前用户的点赞标记:逐个 isLiked 查询(小页面 size 可接受;真要批量化再加 mapper 方法)
        List<PostInfo> items = new ArrayList<>(posts.size());
        for (Post p : posts) {
            long pid = p.getPostId();
            long[] incr = postStatManager.readIncr(pid);
            var stat = statMap.get(pid);
            long like = (stat == null ? 0 : stat.getLikeCount()) + incr[0];
            long comment = (stat == null ? 0 : stat.getCommentCount()) + incr[1];
            boolean liked = currentUserId > 0 && postLikeManager.isLiked(currentUserId, pid);
            items.add(buildInfo(p, imageMap.getOrDefault(pid, Collections.emptyList()),
                    like, comment, liked));
        }

        Post last = posts.get(posts.size() - 1);
        Pagination pagination = Pagination.newBuilder()
                .setNextCursor(String.valueOf(last.getPostId()))
                .setHasMore(posts.size() == pageSize)
                .build();
        return new PageResult<>(items, pagination);
    }

    /** 批量拼装(给 FeedService 用)。 */
    public List<PostInfo> assembleFeedItems(long currentUserId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Post> posts = postManager.listByPostIds(postIds);
        if (posts.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, List<PostImage>> imageMap = postManager.loadImages(postIds);
        Map<Long, com.dating.post.entity.PostStat> statMap = postStatManager.batchGet(postIds);

        Map<Long, Post> postMap = new HashMap<>();
        for (Post p : posts) {
            postMap.put(p.getPostId(), p);
        }

        List<PostInfo> result = new ArrayList<>(posts.size());
        // 保持调用方传入的 postIds 顺序(Feed 混排有强排序意义)
        for (Long pid : postIds) {
            Post p = postMap.get(pid);
            if (p == null) {
                // 该 post_id 已被删,跳过(design §10.4)
                continue;
            }
            long[] incr = postStatManager.readIncr(pid);
            var stat = statMap.get(pid);
            long like = (stat == null ? 0 : stat.getLikeCount()) + incr[0];
            long comment = (stat == null ? 0 : stat.getCommentCount()) + incr[1];
            boolean liked = currentUserId > 0 && postLikeManager.isLiked(currentUserId, pid);
            result.add(buildInfo(p, imageMap.getOrDefault(pid, Collections.emptyList()),
                    like, comment, liked));
        }
        return result;
    }

    private PostInfo buildInfo(Post post, List<PostImage> images,
                                long likeCount, long commentCount, boolean liked) {
        PostInfo.Builder builder = PostInfo.newBuilder()
                .setPostId(post.getPostId())
                .setUserId(post.getUserId())
                .setContent(post.getContent())
                .setLikeCount(likeCount)
                .setCommentCount(commentCount)
                .setLiked(liked)
                .setCreatedAt(post.getCreatedAt() == null
                        ? ""
                        : post.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        for (PostImage img : images) {
            builder.addImages(com.dating.youjianxin.proto.post.PostImage.newBuilder()
                    .setSortOrder(img.getSortOrder())
                    .setImageKey(img.getImageKey())
                    .build());
        }
        return builder.build();
    }

    /** 分页结果包装。 */
    public record PageResult<T>(List<T> items, Pagination pagination) {
    }
}
