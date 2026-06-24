package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.Post;
import org.apache.ibatis.annotations.Mapper;

/**
 * 帖子主表 Mapper。
 * <p>
 * 单表 CRUD 走 BaseMapper / LambdaQueryWrapper,**禁多表 JOIN**(红线 1)。
 * 跨表数据在 service / manager 层多次单表查 + 内存拼装。
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {
}
