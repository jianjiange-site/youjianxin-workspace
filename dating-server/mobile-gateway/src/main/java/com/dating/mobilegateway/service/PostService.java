package com.dating.mobilegateway.service;

import com.dating.mobilegateway.dto.CreateCommentReq;
import com.dating.mobilegateway.dto.CreatePostReq;
import com.dating.mobilegateway.vo.CommentVO;
import com.dating.mobilegateway.vo.PostDetailVO;
import com.dating.mobilegateway.vo.PostVO;

import java.util.List;

public interface PostService {

    long createPost(CreatePostReq req, long userId);

    PostDetailVO getPostDetail(long userId, long postId);

    List<PostVO> listUserPosts(long currentUserId, long targetUserId, int pageSize, long cursor);

    boolean actionLike(long userId, long postId, boolean like);

    long createComment(CreateCommentReq req, long userId);

    List<CommentVO> listComments(long userId, long postId, int pageSize, long cursor);

    boolean deleteComment(long userId, long commentId);

    List<PostVO> getRecommendFeed(long userId, int pageSize, String cursor);

    boolean deletePost(long userId, long postId);
}
