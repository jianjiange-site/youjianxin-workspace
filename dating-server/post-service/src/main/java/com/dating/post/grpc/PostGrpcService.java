package com.dating.post.grpc;

import com.dating.post.exception.BizException;
import com.dating.youjianxin.proto.post.ActionLikeRequest;
import com.dating.youjianxin.proto.post.ActionLikeResponse;
import com.dating.youjianxin.proto.post.CreateCommentRequest;
import com.dating.youjianxin.proto.post.CreateCommentResponse;
import com.dating.youjianxin.proto.post.CreatePostRequest;
import com.dating.youjianxin.proto.post.CreatePostResponse;
import com.dating.youjianxin.proto.post.DeleteCommentRequest;
import com.dating.youjianxin.proto.post.DeleteCommentResponse;
import com.dating.youjianxin.proto.post.DeletePostRequest;
import com.dating.youjianxin.proto.post.DeletePostResponse;
import com.dating.youjianxin.proto.post.GetPostDetailRequest;
import com.dating.youjianxin.proto.post.GetPostDetailResponse;
import com.dating.youjianxin.proto.post.GetRecommendFeedRequest;
import com.dating.youjianxin.proto.post.GetRecommendFeedResponse;
import com.dating.youjianxin.proto.post.LikeAction;
import com.dating.youjianxin.proto.post.ListCommentsRequest;
import com.dating.youjianxin.proto.post.ListCommentsResponse;
import com.dating.youjianxin.proto.post.ListUserPostsRequest;
import com.dating.youjianxin.proto.post.ListUserPostsResponse;
import com.dating.youjianxin.proto.post.PostServiceGrpc;
import com.dating.post.service.CommentService;
import com.dating.post.service.FeedService;
import com.dating.post.service.LikeService;
import com.dating.post.service.PostReadService;
import com.dating.post.service.PostWriteService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * gRPC 9 个 RPC 实现(post-service-design §8.1)。
 * <p>
 * 调用方向严格单向(红线 10):grpc → service → manager → mapper。
 * 本类**只做编排**:取 currentUserId、调 service、统一异常 → BaseResponse、回响应。
 * <p>
 * gRPC 状态码统一 OK,业务错误码通过 {@code BaseResponse.code} 表达,
 * 让 mobile-gateway 可以稳定地把 code 反序列化成上游 HTTP {@code Result.code}。
 */
@GrpcService
@RequiredArgsConstructor
public class PostGrpcService extends PostServiceGrpc.PostServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PostGrpcService.class);

    private final PostWriteService postWriteService;
    private final PostReadService postReadService;
    private final LikeService likeService;
    private final CommentService commentService;
    private final FeedService feedService;

    // ===================================================================
    // 帖子
    // ===================================================================

    @Override
    public void createPost(CreatePostRequest req, StreamObserver<CreatePostResponse> obs) {
        respond(obs, () -> {
            long userId = UserIdServerInterceptor.currentUserId();
            long postId = postWriteService.createPost(userId, req.getContent(), req.getImageKeysList());
            return CreatePostResponse.newBuilder()
                    .setBase(BaseResponses.ok())
                    .setPostId(postId)
                    .build();
        }, e -> CreatePostResponse.newBuilder().setBase(e).build());
    }

    @Override
    public void getPostDetail(GetPostDetailRequest req, StreamObserver<GetPostDetailResponse> obs) {
        respond(obs, () -> {
            long userId = UserIdServerInterceptor.currentUserId();
            var info = postReadService.getPostDetail(userId, req.getPostId());
            return GetPostDetailResponse.newBuilder()
                    .setBase(BaseResponses.ok())
                    .setPost(info)
                    .build();
        }, e -> GetPostDetailResponse.newBuilder().setBase(e).build());
    }

    @Override
    public void listUserPosts(ListUserPostsRequest req, StreamObserver<ListUserPostsResponse> obs) {
        respond(obs, () -> {
            long userId = UserIdServerInterceptor.currentUserId();
            int pageSize = sanitizePageSize(req.getPageSize());
            var page = postReadService.listUserPosts(userId, req.getUserId(), req.getCursor(), pageSize);
            return ListUserPostsResponse.newBuilder()
                    .setBase(BaseResponses.ok())
                    .addAllItems(page.items())
                    .setPagination(page.pagination())
                    .build();
        }, e -> ListUserPostsResponse.newBuilder().setBase(e).build());
    }

    @Override
    public void deletePost(DeletePostRequest req, StreamObserver<DeletePostResponse> obs) {
        respond(obs, () -> {
            long userId = UserIdServerInterceptor.currentUserId();
            postWriteService.deletePost(userId, req.getPostId());
            return DeletePostResponse.newBuilder().setBase(BaseResponses.ok()).build();
        }, e -> DeletePostResponse.newBuilder().setBase(e).build());
    }

    // ===================================================================
    // 点赞
    // ===================================================================

    @Override
    public void actionLike(ActionLikeRequest req, StreamObserver<ActionLikeResponse> obs) {
        respond(obs, () -> {
            long userId = UserIdServerInterceptor.currentUserId();
            boolean liked = req.getAction() == LikeAction.LIKE;
            likeService.action(userId, req.getPostId(), liked);
            return ActionLikeResponse.newBuilder().setBase(BaseResponses.ok()).build();
        }, e -> ActionLikeResponse.newBuilder().setBase(e).build());
    }

    // ===================================================================
    // 评论
    // ===================================================================

    @Override
    public void createComment(CreateCommentRequest req, StreamObserver<CreateCommentResponse> obs) {
        respond(obs, () -> {
            long userId = UserIdServerInterceptor.currentUserId();
            long commentId = commentService.createComment(
                    userId, req.getPostId(), req.getContent(),
                    req.getRootId(), req.getParentId());
            return CreateCommentResponse.newBuilder()
                    .setBase(BaseResponses.ok())
                    .setCommentId(commentId)
                    .build();
        }, e -> CreateCommentResponse.newBuilder().setBase(e).build());
    }

    @Override
    public void listComments(ListCommentsRequest req, StreamObserver<ListCommentsResponse> obs) {
        respond(obs, () -> {
            int pageSize = sanitizePageSize(req.getPageSize());
            var page = commentService.listComments(req.getPostId(), req.getCursor(), pageSize);
            return ListCommentsResponse.newBuilder()
                    .setBase(BaseResponses.ok())
                    .addAllItems(page.items())
                    .setPagination(page.pagination())
                    .build();
        }, e -> ListCommentsResponse.newBuilder().setBase(e).build());
    }

    @Override
    public void deleteComment(DeleteCommentRequest req, StreamObserver<DeleteCommentResponse> obs) {
        respond(obs, () -> {
            long userId = UserIdServerInterceptor.currentUserId();
            commentService.deleteComment(userId, req.getCommentId());
            return DeleteCommentResponse.newBuilder().setBase(BaseResponses.ok()).build();
        }, e -> DeleteCommentResponse.newBuilder().setBase(e).build());
    }

    // ===================================================================
    // 推荐 Feed
    // ===================================================================

    @Override
    public void getRecommendFeed(GetRecommendFeedRequest req,
                                  StreamObserver<GetRecommendFeedResponse> obs) {
        respond(obs, () -> {
            long userId = UserIdServerInterceptor.currentUserId();
            int pageSize = sanitizePageSize(req.getPageSize());
            var page = feedService.getRecommendFeed(userId, req.getCursor(), pageSize);
            return GetRecommendFeedResponse.newBuilder()
                    .setBase(BaseResponses.ok())
                    .addAllItems(page.items())
                    .setPagination(page.pagination())
                    .build();
        }, e -> GetRecommendFeedResponse.newBuilder().setBase(e).build());
    }

    // ===================================================================
    // 统一异常 → BaseResponse 包装
    // ===================================================================

    /**
     * 统一执行 + 异常映射:
     * <ul>
     *   <li>{@link BizException} → {@code BaseResponses.fail}(业务码)</li>
     *   <li>其他 Throwable → {@code BaseResponses.internalError}(5000)</li>
     * </ul>
     * 一律 gRPC OK 返回,业务码在 base 字段。
     */
    private <T> void respond(StreamObserver<T> obs,
                             Supplier<T> action,
                             java.util.function.Function<com.dating.youjianxin.proto.common.BaseResponse, T> errorBuilder) {
        T result;
        try {
            result = action.get();
        } catch (BizException e) {
            result = errorBuilder.apply(BaseResponses.fail(e));
        } catch (Throwable e) {
            log.error("Unhandled RPC error", e);
            result = errorBuilder.apply(BaseResponses.internalError(e));
        }
        obs.onNext(result);
        obs.onCompleted();
    }

    private int sanitizePageSize(int requested) {
        if (requested <= 0) return 10;
        return Math.min(requested, 50);
    }
}
