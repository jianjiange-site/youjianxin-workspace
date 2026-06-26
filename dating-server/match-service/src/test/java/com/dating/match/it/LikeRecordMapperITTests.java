package com.dating.match.it;

import com.dating.match.constant.LikeSource;
import com.dating.match.entity.LikeRecord;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.mapper.LikeRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * like_record UPSERT(DH 计划路径)+ 24h cap count 的集成测试(直连 dev PG)。
 */
class LikeRecordMapperITTests extends DevInfraITSupport {

    @Autowired LikeRecordManager likeRecordManager;
    @Autowired LikeRecordMapper likeRecordMapper;

    @Test
    void upsertDhLike_insert_then_update_source_and_content() {
        long dh = ID_RANGE_LO;
        long bh = ID_RANGE_LO + 1;

        // 首次 INSERT
        likeRecordManager.upsertDhLike(dh, bh, LikeSource.DH_PLAN_ONLINE, "你好");
        LikeRecord row = oneByFromTo(dh, bh);
        assertThat(row.getSource()).isEqualTo(LikeSource.DH_PLAN_ONLINE);
        assertThat(row.getLikeContent()).isEqualTo("你好");
        OffsetDateTime firstLikedAt = row.getLikedAt();

        // ON CONFLICT 覆写 source / likeContent / 刷新 liked_at
        try { Thread.sleep(20); } catch (InterruptedException ignore) {}
        likeRecordManager.upsertDhLike(dh, bh, LikeSource.DH_PLAN_OFFLINE, "在干嘛");
        LikeRecord updated = oneByFromTo(dh, bh);
        assertThat(updated.getSource()).isEqualTo(LikeSource.DH_PLAN_OFFLINE);
        assertThat(updated.getLikeContent()).isEqualTo("在干嘛");
        assertThat(updated.getLikedAt()).isAfter(firstLikedAt);
        // 不重复创建行
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM like_record WHERE from_user_id = ? AND to_user_id = ?",
                Long.class, dh, bh);
        assertThat(cnt).isEqualTo(1L);
    }

    @Test
    void countRecentDhLikes_only_counts_DH_sources_within_lookback() {
        long bh = ID_RANGE_LO + 5;
        long dh1 = ID_RANGE_LO + 6;
        long dh2 = ID_RANGE_LO + 7;
        long realBh = ID_RANGE_LO + 8;
        // 2 条 DH(should count)+ 1 条真人 SWIPE_RIGHT(should NOT count)
        likeRecordManager.upsertDhLike(dh1, bh, LikeSource.DH_PLAN_ONLINE, "a");
        likeRecordManager.upsertDhLike(dh2, bh, LikeSource.DH_PLAN_OFFLINE, "b");
        likeRecordManager.insertIfAbsent(realBh, bh, LikeSource.SWIPE_RIGHT);

        long dhCount = likeRecordManager.countRecentDhLikes(bh, 24);
        assertThat(dhCount).isEqualTo(2L);
    }

    @Test
    void likedFromIdsOf_returns_all_likers_for_target() {
        long bh = ID_RANGE_LO + 15;
        long dh1 = ID_RANGE_LO + 16;
        long dh2 = ID_RANGE_LO + 17;

        likeRecordManager.upsertDhLike(dh1, bh, LikeSource.DH_PLAN_ONLINE, null);
        likeRecordManager.upsertDhLike(dh2, bh, LikeSource.DH_PLAN_ONLINE, null);

        assertThat(likeRecordManager.likedFromIdsOf(bh)).containsExactlyInAnyOrder(dh1, dh2);
    }

    private LikeRecord oneByFromTo(long from, long to) {
        return jdbc.queryForObject(
                "SELECT id, from_user_id, to_user_id, source, like_content, liked_at "
                        + "FROM like_record WHERE from_user_id = ? AND to_user_id = ?",
                (rs, n) -> {
                    LikeRecord r = new LikeRecord();
                    r.setId(rs.getLong("id"));
                    r.setFromUserId(rs.getLong("from_user_id"));
                    r.setToUserId(rs.getLong("to_user_id"));
                    r.setSource(rs.getString("source"));
                    r.setLikeContent(rs.getString("like_content"));
                    r.setLikedAt(rs.getObject("liked_at", OffsetDateTime.class));
                    return r;
                }, from, to);
    }
}
