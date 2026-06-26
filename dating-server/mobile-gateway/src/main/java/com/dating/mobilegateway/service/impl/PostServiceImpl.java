package com.dating.mobilegateway.service.impl;

import com.dating.mobilegateway.client.PostClient;
import com.dating.mobilegateway.dto.CreateCommentReq;
import com.dating.mobilegateway.dto.CreatePostReq;
import com.dating.mobilegateway.service.PostService;
import com.dating.mobilegateway.vo.CommentVO;
import com.dating.mobilegateway.vo.PostDetailVO;
import com.dating.mobilegateway.vo.PostVO;
import com.dating.youjianxin.proto.post.GetPostDetailResponse;
import com.dating.youjianxin.proto.post.GetRecommendFeedResponse;
import com.dating.youjianxin.proto.post.ListCommentsResponse;
import com.dating.youjianxin.proto.post.ListUserPostsResponse;
import com.dating.youjianxin.proto.post.PostInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostClient postClient;

    @Override
    public long createPost(CreatePostReq req, long userId) {
        var resp = postClient.createPost(userId, req.content(),
                req.imageKeys() != null ? req.imageKeys() : Collections.emptyList());
        return resp.getPostId();
    }

    @Override
    public PostDetailVO getPostDetail(long userId, long postId) {
        GetPostDetailResponse resp = postClient.getPostDetail(userId, postId);
        PostInfo p = resp.getPost();
        return new PostDetailVO(p.getPostId(), p.getUserId(), p.getContent(),
                p.getImageKeysList(),
                p.getStat().getLikeCount(), p.getStat().getCommentCount(),
                p.getIsLiked(), p.getCreatedAt());
    }

    @Override
    public List<PostVO> listUserPosts(long currentUserId, long targetUserId,
                                       int pageSize, long cursor) {
        ListUserPostsResponse resp = postClient.listUserPosts(
                currentUserId, targetUserId, pageSize, cursor);
        return toPostVOList(resp.getItemsList());
    }

    @Override
    public boolean actionLike(long userId, long postId, boolean like) {
        var resp = postClient.actionLike(userId, postId, like);
        return resp.getSuccess();
    }

    @Override
    public long createComment(CreateCommentReq req, long userId) {
        var resp = postClient.createComment(userId, req.postId(), req.content());
        return resp.getCommentId();
    }

    @Override
    public List<CommentVO> listComments(long userId, long postId, int pageSize, long cursor) {
        ListCommentsResponse resp = postClient.listComments(userId, postId, pageSize, cursor);
        List<CommentVO> comments = new ArrayList<>(resp.getCommentsCount());
        for (var c : resp.getCommentsList()) {
            comments.add(new CommentVO(c.getCommentId(), c.getPostId(),
                    c.getUserId(), c.getContent(), c.getCreatedAt()));
        }
        return comments;
    }

    @Override
    public boolean deleteComment(long userId, long commentId) {
        var resp = postClient.deleteComment(userId, commentId);
        return resp.getSuccess();
    }

    @Override
    public List<PostVO> getRecommendFeed(long userId, int pageSize, String cursor) {
        GetRecommendFeedResponse resp = postClient.getRecommendFeed(userId, pageSize, cursor);
        return toPostVOList(resp.getItemsList());
    }

    @Override
    public boolean deletePost(long userId, long postId) {
        var resp = postClient.deletePost(userId, postId);
        return resp.getSuccess();
    }

    private static List<PostVO> toPostVOList(List<PostInfo> items) {
        List<PostVO> vos = new ArrayList<>(items.size());
        for (PostInfo p : items) {
            vos.add(new PostVO(p.getPostId(), p.getUserId(), p.getContent(),
                    p.getImageKeysList(),
                    p.getStat().getLikeCount(), p.getStat().getCommentCount(),
                    p.getIsLiked(), p.getCreatedAt()));
        }
        return vos;
    }
}
