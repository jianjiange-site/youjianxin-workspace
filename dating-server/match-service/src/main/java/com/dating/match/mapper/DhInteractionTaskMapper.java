package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.DhInteractionTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * dh_interaction_task 单表 CRUD。
 *
 * <p>generator 用 {@link #findExistingToUserIdsByScene} 去重 + BaseMapper insert 批量写;
 * executor 用 {@link #scanDue} 拉到期任务 + {@link #deleteById} 硬删。
 */
@Mapper
public interface DhInteractionTaskMapper extends BaseMapper<DhInteractionTask> {

    /**
     * 扫到期任务:execute_time &lt;= NOW(),按 execute_time 升序;走 idx_dh_task_execute_time。
     * 不锁行(多实例由 ShedLock 互斥,见 docs §6.6)。
     */
    @Select("SELECT * FROM dh_interaction_task "
            + "WHERE execute_time <= NOW() ORDER BY execute_time ASC LIMIT #{limit}")
    List<DhInteractionTask> scanDue(@Param("limit") int limit);

    /**
     * generator 去重查询:返回入参 {@code toUserIds} 中,已有"未执行 + 同 scene"任务行的 to_user_id 子集。
     * 走 idx_dh_task_to_user_scene 索引,IN 列表大小由调用方控制(每轮上限 5000)。
     *
     * <p>OnlinePlanGenerator / OfflinePlanGenerator 各取自己的 scene 调用;
     * ONLINE 的任务行不参与 OFFLINE 去重,反之亦然(单次离线期最多 1 个 OFFLINE 计划 + 2h cooldown
     * 已经在 generator 层管控,这里只兜底任务表内不要堆积同 scene 重复任务)。
     */
    @Select({
            "<script>",
            "SELECT DISTINCT to_user_id FROM dh_interaction_task ",
            "WHERE scene = #{scene} AND to_user_id IN ",
            "<foreach collection='toUserIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"
    })
    List<Long> findExistingToUserIdsByScene(@Param("toUserIds") List<Long> toUserIds,
                                            @Param("scene") short scene);

    /**
     * executor 执行成功后硬删任务行(不软删 — 短生命周期表)。
     */
    @Delete("DELETE FROM dh_interaction_task WHERE id = #{id}")
    int deleteByIdHard(@Param("id") Long id);

    /**
     * 积压监控:超过 5min 未消费的到期任务数,供 LikeVisitorTaskExecutor 打 WARN。
     */
    @Select("SELECT COUNT(*) FROM dh_interaction_task "
            + "WHERE execute_time <= NOW() - interval '5 minutes'")
    long countOverdue();
}
