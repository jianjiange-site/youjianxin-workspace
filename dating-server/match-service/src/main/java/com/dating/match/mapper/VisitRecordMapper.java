package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.VisitRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface VisitRecordMapper extends BaseMapper<VisitRecord> {

    /**
     * PG 原生 UPSERT(真人 PROFILE_VIEW 路径)。
     *
     * <p>首次访问 → INSERT(visit_count=1, source='PROFILE_VIEW' DEFAULT)。
     * 重复访问 → UPDATE 累加 visit_count、刷新 last_visited_at;first_visited_at 不变,
     * source 不动(尊重首次创建者:DH 计划在前 → source 留 DH_PLAN_*;此后真人也访问,
     * 仍按"DH 计划造的访客行" 计数,语义上 OK ── 真实 visit_count 还是会正确累加)。
     */
    @Insert("INSERT INTO visit_record (from_user_id, to_user_id) "
          + "VALUES (#{from}, #{to}) "
          + "ON CONFLICT (from_user_id, to_user_id) DO UPDATE "
          + "SET visit_count = visit_record.visit_count + 1, "
          + "    last_visited_at = NOW(), "
          + "    updated_at = NOW(), "
          + "    deleted = false")
    int upsertVisit(@Param("from") long fromUserId, @Param("to") long toUserId);

    /**
     * DH 计划路径 UPSERT(LikeVisitorTaskExecutor 单条 short tx)。
     *
     * <p>显式指定 source = DH_PLAN_ONLINE / DH_PLAN_OFFLINE;首次 INSERT 用此 source,
     * 后续 ON CONFLICT 同样 source 不动(同 {@link #upsertVisit} 的取舍)。
     */
    @Insert("INSERT INTO visit_record (from_user_id, to_user_id, source) "
          + "VALUES (#{from}, #{to}, #{source}) "
          + "ON CONFLICT (from_user_id, to_user_id) DO UPDATE "
          + "SET visit_count = visit_record.visit_count + 1, "
          + "    last_visited_at = NOW(), "
          + "    updated_at = NOW(), "
          + "    deleted = false")
    int upsertVisitWithSource(@Param("from") long fromUserId,
                              @Param("to") long toUserId,
                              @Param("source") String source);

    /**
     * "Visits of me" 分页查询。按 last_visited_at 倒序;单表无 JOIN。
     *
     * @param toUserId  被访问方(= caller)
     * @param cursor    上一页最后一条 last_visited_at;首页传 null
     * @param limit     分页大小
     */
    @Select("<script>"
          + "SELECT * FROM visit_record WHERE to_user_id = #{toUserId} AND deleted = false "
          + "<if test='cursor != null'>AND last_visited_at &lt; #{cursor} </if>"
          + "ORDER BY last_visited_at DESC LIMIT #{limit}"
          + "</script>")
    List<VisitRecord> selectVisitsToUser(@Param("toUserId") Long toUserId,
                                         @Param("cursor") OffsetDateTime cursor,
                                         @Param("limit") int limit);

    /**
     * 6.4 「单 BH 24h 内 DH visit 上限」查询。走 idx_visit_to_source_time。
     */
    @Select({
            "<script>",
            "SELECT COUNT(*) FROM visit_record ",
            "WHERE to_user_id = #{toUserId} AND deleted = false ",
            "AND last_visited_at >= NOW() - (#{lookbackHours} * interval '1 hour') ",
            "AND source IN ",
            "<foreach collection='sources' item='s' open='(' separator=',' close=')'>#{s}</foreach>",
            "</script>"
    })
    long countRecentBySource(@Param("toUserId") Long toUserId,
                             @Param("sources") List<String> sources,
                             @Param("lookbackHours") int lookbackHours);

    /**
     * 取该用户已被 VISIT 过的所有 from_user_id(generator exclude_user_ids 拼装)。
     */
    @Select("SELECT from_user_id FROM visit_record WHERE to_user_id = #{toUserId} AND deleted = false")
    List<Long> selectFromUserIdsByTo(@Param("toUserId") Long toUserId);
}
