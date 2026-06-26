package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.MatchOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MatchOutboxMapper extends BaseMapper<MatchOutbox> {

    /**
     * 取 PENDING + 到期记录,供 MatchOutboxRetry 消费。
     * 不走 SELECT FOR UPDATE(高并发会阻塞);多实例由 ShedLock 互斥。
     */
    @Select("SELECT * FROM match_outbox "
            + "WHERE status = 'PENDING' AND next_retry_at <= NOW() AND deleted = false "
            + "ORDER BY next_retry_at ASC LIMIT #{limit}")
    List<MatchOutbox> selectPendingDue(@Param("limit") int limit);

    /**
     * 标记成功(DONE)。
     */
    @Update("UPDATE match_outbox SET status = 'DONE', updated_at = NOW() WHERE id = #{id}")
    int markDone(@Param("id") Long id);

    /**
     * 重试失败后回写:attempts++ + next_retry_at exp backoff + status(PENDING / DEAD)。
     */
    @Update("UPDATE match_outbox SET attempts = #{attempts}, next_retry_at = #{nextRetryAtSeconds}::timestamptz, "
            + "status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateRetry(@Param("id") Long id,
                    @Param("attempts") int attempts,
                    @Param("nextRetryAtSeconds") String nextRetryAtSeconds,
                    @Param("status") String status);
}
