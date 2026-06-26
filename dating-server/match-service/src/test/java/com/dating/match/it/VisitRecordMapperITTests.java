package com.dating.match.it;

import com.dating.match.constant.VisitSource;
import com.dating.match.manager.VisitRecordManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * visit_record 双路径 UPSERT + 24h cap 集成测试(直连 dev PG)。
 */
class VisitRecordMapperITTests extends DevInfraITSupport {

    @Autowired VisitRecordManager visitRecordManager;

    @Test
    void upsertDhVisit_insert_then_increment_visit_count() {
        long dh = ID_RANGE_LO;
        long bh = ID_RANGE_LO + 1;

        visitRecordManager.upsertDhVisit(dh, bh, VisitSource.DH_PLAN_ONLINE);
        assertThat(rowVisitCount(dh, bh)).isEqualTo(1);
        assertThat(rowSource(dh, bh)).isEqualTo(VisitSource.DH_PLAN_ONLINE);

        visitRecordManager.upsertDhVisit(dh, bh, VisitSource.DH_PLAN_OFFLINE);
        assertThat(rowVisitCount(dh, bh)).isEqualTo(2);
        // source 首次创建后不动(尊重首次来源,docs 注释里有取舍)
        assertThat(rowSource(dh, bh)).isEqualTo(VisitSource.DH_PLAN_ONLINE);
    }

    @Test
    void recordVisit_uses_default_PROFILE_VIEW_source() {
        long viewer = ID_RANGE_LO + 5;
        long target = ID_RANGE_LO + 6;

        visitRecordManager.recordVisit(viewer, target);
        assertThat(rowVisitCount(viewer, target)).isEqualTo(1);
        assertThat(rowSource(viewer, target)).isEqualTo(VisitSource.PROFILE_VIEW);
    }

    @Test
    void countRecentDhVisits_only_counts_DH_sources() {
        long bh = ID_RANGE_LO + 10;
        long dh1 = ID_RANGE_LO + 11;
        long dh2 = ID_RANGE_LO + 12;
        long realViewer = ID_RANGE_LO + 13;

        visitRecordManager.upsertDhVisit(dh1, bh, VisitSource.DH_PLAN_ONLINE);
        visitRecordManager.upsertDhVisit(dh2, bh, VisitSource.DH_PLAN_OFFLINE);
        visitRecordManager.recordVisit(realViewer, bh);

        assertThat(visitRecordManager.countRecentDhVisits(bh, 24)).isEqualTo(2L);
    }

    @Test
    void visitedFromIdsOf_returns_all_visitors() {
        long bh = ID_RANGE_LO + 20;
        long dh1 = ID_RANGE_LO + 21;
        long real = ID_RANGE_LO + 22;
        visitRecordManager.upsertDhVisit(dh1, bh, VisitSource.DH_PLAN_ONLINE);
        visitRecordManager.recordVisit(real, bh);
        assertThat(visitRecordManager.visitedFromIdsOf(bh)).containsExactlyInAnyOrder(dh1, real);
    }

    private int rowVisitCount(long from, long to) {
        Integer n = jdbc.queryForObject(
                "SELECT visit_count FROM visit_record WHERE from_user_id = ? AND to_user_id = ?",
                Integer.class, from, to);
        return n == null ? -1 : n;
    }

    private String rowSource(long from, long to) {
        return jdbc.queryForObject(
                "SELECT source FROM visit_record WHERE from_user_id = ? AND to_user_id = ?",
                String.class, from, to);
    }
}
