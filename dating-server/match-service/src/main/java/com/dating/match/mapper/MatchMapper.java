package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.Match;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MatchMapper extends BaseMapper<Match> {

    /**
     * 按规范化的 (low, high) 反查 match,用于 ON CONFLICT 兜底后取 existing。
     */
    @Select("SELECT * FROM match WHERE user_id_low = #{low} AND user_id_high = #{high} "
            + "AND deleted = false LIMIT 1")
    Match selectByPair(@Param("low") Long low, @Param("high") Long high);

    /**
     * 列出 userId 的所有匹配伙伴 user_id (post-service fanout 用)。
     * 单表查询,按 matched_at 倒序,limit 由调用方传入。
     */
    @Select("SELECT CASE WHEN user_id_low = #{uid} THEN user_id_high ELSE user_id_low END "
            + "FROM match WHERE (user_id_low = #{uid} OR user_id_high = #{uid}) "
            + "AND deleted = false ORDER BY matched_at DESC LIMIT #{limit}")
    List<Long> selectFriendUserIds(@Param("uid") Long userId, @Param("limit") int limit);
}
