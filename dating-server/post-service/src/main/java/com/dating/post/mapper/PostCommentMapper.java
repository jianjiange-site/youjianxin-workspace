package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.PostComment;
import org.apache.ibatis.annotations.Mapper;

/** 评论 Mapper。 */
@Mapper
public interface PostCommentMapper extends BaseMapper<PostComment> {
}
