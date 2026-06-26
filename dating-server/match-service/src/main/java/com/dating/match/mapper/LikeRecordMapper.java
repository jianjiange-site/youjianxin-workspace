package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.LikeRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface LikeRecordMapper extends BaseMapper<LikeRecord> {

    /**
     * "Likes of me" 分页查询。单表无 JOIN。
     *
     * @param toUserId  被点赞方(= caller)
     * @param cursor    上一页最后一条 liked_at;首页传 null
     * @param limit     分页大小
     */
    @Select("<script>"
            + "SELECT * FROM like_record WHERE to_user_id = #{toUserId} AND deleted = false "
            + "<if test='cursor != null'>AND liked_at &lt; #{cursor} </if>"
            + "ORDER BY liked_at DESC LIMIT #{limit}"
            + "</script>")
    List<LikeRecord> selectLikesToUser(@Param("toUserId") Long toUserId,
                                       @Param("cursor") OffsetDateTime cursor,
                                       @Param("limit") int limit);

    /**
     * DH 计划 LIKE 落库 UPSERT(源于 LikeVisitorTaskExecutor 单条 short tx)。
     *
     * <p>命中 UNIQUE (from, to) → 覆写 source / liked_at / like_content / deleted=false,
     * 同 (from, to) 软删过的也复活。real-user swipe 路径仍走 BaseMapper.insert + 异常捕获,
     * 不复用本 UPSERT(swipe 已经表层幂等)。
     */
    @Insert("INSERT INTO like_record (from_user_id, to_user_id, source, like_content) "
          + "VALUES (#{from}, #{to}, #{source}, #{likeContent}) "
          + "ON CONFLICT (from_user_id, to_user_id) DO UPDATE "
          + "SET source = EXCLUDED.source, "
          + "    liked_at = NOW(), "
          + "    like_content = EXCLUDED.like_content, "
          + "    updated_at = NOW(), "
          + "    deleted = false")
    int upsertLike(@Param("from") long fromUserId,
                   @Param("to") long toUserId,
                   @Param("source") String source,
                   @Param("likeContent") String likeContent);

    /**
     * 6.4 「单 BH 24h 内 DH like 上限」查询。走 idx_like_to_source_time。
     *
     * <p>由 DhPlanGeneratorService 在生成前 cap-check 用;sources 入参一般传
     * {@code LikeSource.DH_SOURCES},也兼容运营临时排查其他 source。
     */
    @Select({
            "<script>",
            "SELECT COUNT(*) FROM like_record ",
            "WHERE to_user_id = #{toUserId} AND deleted = false ",
            "AND liked_at >= NOW() - (#{lookbackHours} * interval '1 hour') ",
            "AND source IN ",
            "<foreach collection='sources' item='s' open='(' separator=',' close=')'>#{s}</foreach>",
            "</script>"
    })
    long countRecentBySource(@Param("toUserId") Long toUserId,
                             @Param("sources") List<String> sources,
                             @Param("lookbackHours") int lookbackHours);

    /**
     * 取该用户已被 LIKE 过的所有 from_user_id(generator 排除"重复给同一 BH like"用)。
     * 仅用于 generator exclude_user_ids 拼装,量级在百条以内不会爆;调用方自行去重 UNION。
     */
    @Select("SELECT from_user_id FROM like_record WHERE to_user_id = #{toUserId} AND deleted = false")
    List<Long> selectFromUserIdsByTo(@Param("toUserId") Long toUserId);
}
