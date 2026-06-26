package com.dating.mobilegateway.client;

import com.dating.youjianxin.proto.post.ActionLikeRequest;
import com.dating.youjianxin.proto.post.ActionLikeResponse;
import com.dating.youjianxin.proto.post.BaseRequest;
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
import com.dating.youjianxin.proto.post.ListCommentsRequest;
import com.dating.youjianxin.proto.post.ListCommentsResponse;
import com.dating.youjianxin.proto.post.ListUserPostsRequest;
import com.dating.youjianxin.proto.post.ListUserPostsResponse;
import com.dating.youjianxin.proto.post.PostServiceGrpc;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostClient {

    private static final long CALL_TIMEOUT_MS = 5000L;

    @GrpcClient("post-service")
    private PostServiceGrpc.PostServiceBlockingStub stub;

    public CreatePostResponse createPost(long userId, String content,
                                         java.util.List<String> imageKeys) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .createPost(CreatePostRequest.newBuilder()
                            .setBase(baseRequest(userId))
                            .setContent(content != null ? content : "")
                            .addAllImageKeys(imageKeys)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public GetPostDetailResponse getPostDetail(long userId, long postId) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getPostDetail(GetPostDetailRequest.newBuilder()
                            .setBase(baseRequest(userId))
                            .setPostId(postId)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public ListUserPostsResponse listUserPosts(long currentUserId, long targetUserId,
                                                int pageSize, long cursor) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .listUserPosts(ListUserPostsRequest.newBuilder()
                            .setBase(baseRequest(currentUserId))
                            .setUserId(targetUserId)
                            .setPageSize(pageSize)
                            .setCursor(cursor)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public ActionLikeResponse actionLike(long userId, long postId, boolean like) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .actionLike(ActionLikeRequest.newBuilder()
                            .setBase(baseRequest(userId))
                            .setPostId(postId)
                            .setAction(like
                                    ? ActionLikeRequest.LikeAction.LIKE
                                    : ActionLikeRequest.LikeAction.UNLIKE)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public CreateCommentResponse createComment(long userId, long postId, String content) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .createComment(CreateCommentRequest.newBuilder()
                            .setBase(baseRequest(userId))
                            .setPostId(postId)
                            .setContent(content)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public ListCommentsResponse listComments(long userId, long postId,
                                              int pageSize, long cursor) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .listComments(ListCommentsRequest.newBuilder()
                            .setBase(baseRequest(userId))
                            .setPostId(postId)
                            .setPageSize(pageSize)
                            .setCursor(cursor)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public DeleteCommentResponse deleteComment(long userId, long commentId) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .deleteComment(DeleteCommentRequest.newBuilder()
                            .setBase(baseRequest(userId))
                            .setCommentId(commentId)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public GetRecommendFeedResponse getRecommendFeed(long userId, int pageSize, String cursor) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .getRecommendFeed(GetRecommendFeedRequest.newBuilder()
                            .setBase(baseRequest(userId))
                            .setPageSize(pageSize)
                            .setCursor(cursor != null ? cursor : "")
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    public DeletePostResponse deletePost(long userId, long postId) {
        try {
            return stub.withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .deletePost(DeletePostRequest.newBuilder()
                            .setUserId(userId)
                            .setPostId(postId)
                            .build());
        } catch (StatusRuntimeException sre) {
            throw GrpcStatusMapper.map(sre);
        }
    }

    private static BaseRequest baseRequest(long userId) {
        return BaseRequest.newBuilder()
                .putExtra("user_id", String.valueOf(userId))
                .build();
    }
}
