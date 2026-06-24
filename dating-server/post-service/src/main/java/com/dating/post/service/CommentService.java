package com.dating.post.service;

import com.dating.common.proto.Pagination;
import com.dating.post.config.SnowflakeIdGenerator;
import com.dating.post.constant.ErrorCode;
import com.dating.post.constant.PostStatus;
import com.dating.post.entity.PostComment;
import com.dating.post.exception.BizException;
import com.dating.post.manager.PostCommentManager;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import com.dating.post.proto.CommentInfo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 评论 增 / 删 / 列(post-service-design §9.5 / §9.6 / §9.7)。
 * <p>
 * 初期所有评论都是一级评论({@code root_id = parent_id = reply_to_user_id = 0})。
 * 计数走 Redis 增量 → CommentFlushJob 批量刷盘。
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    private static final int CONTENT_MIN = 1;
    private static final int CONTENT_MAX = 512;

    private final PostManager postManager;
    private final PostCommentManager postCommentManager;
    private final PostStatManager postStatManager;
    private final SnowflakeIdGenerator idGenerator;

    @Transactional(rollbackFor = Exception.class)
    public long createComment(long userId, long postId, String content,
                              long rootId, long parentId) {
        validateContent(content);

        // 校验目标帖子存在
        postManager.findByPostId(postId)
                .orElseThrow(() -> BizException.postNotFound(postId));

        long commentId = idGenerator.nextId();
        OffsetDateTime now = OffsetDateTime.now();

        PostComment comment = new PostComment();
        comment.setCommentId(commentId);
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setRootId(rootId);
        comment.setParentId(parentId);
        comment.setReplyToUserId(0L);
        comment.setContent(content.trim());
        comment.setStatus(PostStatus.NORMAL);
        comment.setDeleted(0);
        comment.setCreatedAt(now);

        postCommentManager.insert(comment);

        // 事务外的 Redis 操作:严格说应该在 commit 后跑,简化起见这里直接写,
        // 失败仅丢一条 ZSet 记录(读侧仍能 DB 回源)。
        try {
            postCommentManager.addToZsetAndTrim(postId, commentId);
            postStatManager.applyCommentDelta(postId, +1);
        } catch (Exception e) {
            log.warn("Comment cache write failed, commentId={} postId={}", commentId, postId, e);
        }

        log.info("Comment created: commentId={} postId={} userId={}", commentId, postId, userId);
        return commentId;
    }

    public PostReadService.PageResult<CommentInfo> listComments(long postId, long cursor, int pageSize) {
        List<PostComment> comments = postCommentManager.listTopLevel(postId, cursor, pageSize);
        if (comments.isEmpty()) {
            return new PostReadService.PageResult<>(Collections.emptyList(),
                    Pagination.newBuilder().setNextCursor("").setHasMore(false).build());
        }

        List<CommentInfo> items = new ArrayList<>(comments.size());
        for (PostComment c : comments) {
            items.add(toInfo(c));
        }

        PostComment last = comments.get(comments.size() - 1);
        Pagination pagination = Pagination.newBuilder()
                .setNextCursor(String.valueOf(last.getCommentId()))
                .setHasMore(comments.size() == pageSize)
                .build();
        return new PostReadService.PageResult<>(items, pagination);
    }

    public void deleteComment(long userId, long commentId) {
        PostComment c = postCommentManager.findByCommentId(commentId)
                .orElseThrow(() -> BizException.commentNotFound(commentId));
        if (!c.getUserId().equals(userId)) {
            throw BizException.forbidden("非评论作者无法删除");
        }
        postCommentManager.logicalDelete(commentId, userId);
        try {
            postCommentManager.removeFromZset(c.getPostId(), commentId);
            postStatManager.applyCommentDelta(c.getPostId(), -1);
        } catch (Exception e) {
            log.warn("Comment delete cache cleanup failed, commentId={}", commentId, e);
        }
    }

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.COMMENT_EMPTY, "评论内容不能为空");
        }
        String trimmed = content.trim();
        if (trimmed.length() < CONTENT_MIN) {
            throw new BizException(ErrorCode.COMMENT_EMPTY, "评论内容过短");
        }
        if (trimmed.length() > CONTENT_MAX) {
            throw new BizException(ErrorCode.COMMENT_TOO_LONG,
                    "评论内容超长: " + trimmed.length() + " > " + CONTENT_MAX);
        }
    }

    private CommentInfo toInfo(PostComment c) {
        return CommentInfo.newBuilder()
                .setCommentId(c.getCommentId())
                .setPostId(c.getPostId())
                .setUserId(c.getUserId())
                .setRootId(c.getRootId() == null ? 0 : c.getRootId())
                .setParentId(c.getParentId() == null ? 0 : c.getParentId())
                .setReplyToUserId(c.getReplyToUserId() == null ? 0 : c.getReplyToUserId())
                .setContent(c.getContent())
                .setCreatedAt(c.getCreatedAt() == null
                        ? ""
                        : c.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .build();
    }
}
