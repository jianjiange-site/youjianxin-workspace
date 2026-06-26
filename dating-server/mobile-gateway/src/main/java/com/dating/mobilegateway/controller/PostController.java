package com.dating.mobilegateway.controller;

import com.dating.mobilegateway.constant.JwtClaims;
import com.dating.mobilegateway.dto.CreateCommentReq;
import com.dating.mobilegateway.dto.CreatePostReq;
import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.exception.Result;
import com.dating.mobilegateway.service.PostService;
import com.dating.mobilegateway.vo.CommentVO;
import com.dating.mobilegateway.vo.PostDetailVO;
import com.dating.mobilegateway.vo.PostVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/post")
@RequiredArgsConstructor
@Tag(name = "Post", description = "帖子 / 动态 / 点赞 / 评论 / Feed")
public class PostController {

    private final PostService postService;

    @PostMapping
    @Operation(summary = "发布帖子")
    public Result<Long> create(@Valid @RequestBody CreatePostReq req,
                                HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(postService.createPost(req, userId));
    }

    @GetMapping("/{postId}")
    @Operation(summary = "帖子详情")
    public Result<PostDetailVO> detail(@PathVariable long postId,
                                        HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(postService.getPostDetail(userId, postId));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "某用户的帖子列表")
    public Result<List<PostVO>> userPosts(@PathVariable long userId,
                                           @RequestParam(defaultValue = "20") int pageSize,
                                           @RequestParam(defaultValue = "0") long cursor,
                                           HttpServletRequest http) {
        long currentUserId = requireSelf(http);
        return Result.ok(postService.listUserPosts(currentUserId, userId, pageSize, cursor));
    }

    @PostMapping("/{postId}/like")
    @Operation(summary = "点赞")
    public Result<Boolean> like(@PathVariable long postId, HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(postService.actionLike(userId, postId, true));
    }

    @DeleteMapping("/{postId}/like")
    @Operation(summary = "取消点赞")
    public Result<Boolean> unlike(@PathVariable long postId, HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(postService.actionLike(userId, postId, false));
    }

    @PostMapping("/{postId}/comment")
    @Operation(summary = "发表评论")
    public Result<Long> createComment(@PathVariable long postId,
                                       @Valid @RequestBody CreateCommentReq req,
                                       HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(postService.createComment(
                new CreateCommentReq(postId, req.content()), userId));
    }

    @GetMapping("/{postId}/comment")
    @Operation(summary = "评论列表")
    public Result<List<CommentVO>> comments(@PathVariable long postId,
                                             @RequestParam(defaultValue = "20") int pageSize,
                                             @RequestParam(defaultValue = "0") long cursor,
                                             HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(postService.listComments(userId, postId, pageSize, cursor));
    }

    @DeleteMapping("/comment/{commentId}")
    @Operation(summary = "删除评论")
    public Result<Boolean> deleteComment(@PathVariable long commentId,
                                          HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(postService.deleteComment(userId, commentId));
    }

    @GetMapping("/feed")
    @Operation(summary = "推荐 Feed")
    public Result<List<PostVO>> feed(@RequestParam(defaultValue = "10") int pageSize,
                                      @RequestParam(required = false) String cursor,
                                      HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(postService.getRecommendFeed(userId, pageSize, cursor));
    }

    @DeleteMapping("/{postId}")
    @Operation(summary = "删除帖子")
    public Result<Boolean> delete(@PathVariable long postId,
                                   HttpServletRequest http) {
        long userId = requireSelf(http);
        return Result.ok(postService.deletePost(userId, postId));
    }

    private static long requireSelf(HttpServletRequest http) {
        Object selfAttr = http.getAttribute(JwtClaims.REQUEST_ATTR_USER_ID);
        if (!(selfAttr instanceof Long selfUserId) || selfUserId <= 0) {
            throw new BizException(ErrorCodes.UNAUTHENTICATED, "missing user context");
        }
        return selfUserId;
    }
}
