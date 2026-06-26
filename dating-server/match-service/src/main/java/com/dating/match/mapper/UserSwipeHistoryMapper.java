package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.UserSwipeHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface UserSwipeHistoryMapper extends BaseMapper<UserSwipeHistory> {

    /**
     * 该用户所有 target_user_id(任何 direction)── 召回时作为 exclude_user_ids 传给 user-service。
     * 上限由 service 层截断(默认 5000)。
     */
    @Select("SELECT target_user_id FROM user_swipe_history "
            + "WHERE user_id = #{userId} AND deleted = false "
            + "ORDER BY swiped_at DESC")
    List<Long> selectTargetIdsByUser(@Param("userId") Long userId);

    /**
     * 检查 "target 曾经右划过 caller" ── BH 互划立即匹配判定。
     * direction IN (2=RIGHT, 3=SUPER_HI)。
     */
    @Select("SELECT EXISTS ("
            + "  SELECT 1 FROM user_swipe_history "
            + "  WHERE user_id = #{targetUserId} AND target_user_id = #{callerUserId} "
            + "    AND direction IN (2, 3) AND deleted = false"
            + ")")
    boolean existsRightSwipeFromTarget(@Param("callerUserId") Long callerUserId,
                                       @Param("targetUserId") Long targetUserId);

    /**
     * 取该用户最近 N 天 RIGHT / SUPER_HI 的 target_user_id + target_user_type,
     * 用于 D1 PreferenceBuilder 反查 target 资料聚合画像。
     * <p>按 swiped_at 降序,limit 截断防爆(默认 200,够支撑画像统计)。
     */
    @Select("SELECT target_user_id, target_user_type FROM user_swipe_history "
            + "WHERE user_id = #{userId} AND direction IN (2, 3) "
            + "  AND swiped_at >= #{since} AND deleted = false "
            + "ORDER BY swiped_at DESC LIMIT #{limit}")
    List<UserSwipeHistory> selectRecentRightSwipes(@Param("userId") Long userId,
                                                    @Param("since") OffsetDateTime since,
                                                    @Param("limit") int limit);

    /**
     * 取昨天有过任何 swipe 行为的活跃 user_id(D1 scope)。
     * @param sinceUtc 昨日 00:00 UTC
     * @param limit    单次最大处理数量(防爆;>10k 用户分批跑)
     */
    @Select("SELECT DISTINCT user_id FROM user_swipe_history "
            + "WHERE swiped_at >= #{sinceUtc} AND deleted = false "
            + "LIMIT #{limit}")
    List<Long> selectActiveUserIdsSince(@Param("sinceUtc") OffsetDateTime sinceUtc,
                                         @Param("limit") int limit);

    /**
     * 批量查 "哪些 candidate 曾右划过 caller" ── D1 Ranker 的 mutual_like_bonus 信号。
     * @return 曾右划过 caller 的 candidate user_id 子集
     */
    @Select("<script>"
            + "SELECT user_id FROM user_swipe_history "
            + "WHERE target_user_id = #{callerUserId} AND direction IN (2, 3) AND deleted = false "
            + "  AND user_id IN "
            + "  <foreach item='id' collection='candidateIds' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Long> selectCandidatesWhoRightSwipedCaller(@Param("callerUserId") Long callerUserId,
                                                     @Param("candidateIds") List<Long> candidateIds);
}

