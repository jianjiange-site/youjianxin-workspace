package com.dating.match.it;

import com.dating.match.constant.DhTaskAction;
import com.dating.match.constant.DhTaskScene;
import com.dating.match.entity.DhInteractionTask;
import com.dating.match.manager.DhInteractionTaskManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dh_interaction_task 的 mapper / manager 集成测试(直连 dev PG)。
 */
class DhInteractionTaskMapperITTests extends DevInfraITSupport {

    @Autowired DhInteractionTaskManager taskManager;

    @Test
    void scanDue_returns_only_past_executeTime_ordered_by_executeTime() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long bh = ID_RANGE_LO;
        long dh1 = ID_RANGE_LO + 1;
        long dh2 = ID_RANGE_LO + 2;
        long dh3 = ID_RANGE_LO + 3;

        taskManager.insert(buildTask(dh1, bh, DhTaskAction.VISIT, DhTaskScene.ONLINE, now.minusMinutes(2), null));
        taskManager.insert(buildTask(dh2, bh, DhTaskAction.LIKE, DhTaskScene.ONLINE, now.minusSeconds(30), "你好"));
        // 未来任务不应被扫到
        taskManager.insert(buildTask(dh3, bh, DhTaskAction.VISIT, DhTaskScene.OFFLINE, now.plusMinutes(15), null));

        List<DhInteractionTask> due = taskManager.scanDue(10);
        // 同 BH 可能其他 IT case 也落数据,这里只筛我们 IT 范围内的
        List<DhInteractionTask> mine = due.stream()
                .filter(t -> t.getToUserId() == bh)
                .toList();
        assertThat(mine).hasSize(2);
        // 按 execute_time ASC
        assertThat(mine.get(0).getFromUserId()).isEqualTo(dh1);
        assertThat(mine.get(1).getFromUserId()).isEqualTo(dh2);
        // like_content 正确读出
        assertThat(mine.get(1).getLikeContent()).isEqualTo("你好");
        assertThat(mine.get(1).getAction()).isEqualTo(DhTaskAction.LIKE);
    }

    @Test
    void findExistingToUserIdsByScene_filters_by_scene() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long bhA = ID_RANGE_LO + 10;
        long bhB = ID_RANGE_LO + 11;
        long bhC = ID_RANGE_LO + 12;
        long dh = ID_RANGE_LO + 20;

        taskManager.insert(buildTask(dh, bhA, DhTaskAction.LIKE, DhTaskScene.ONLINE, now.plusMinutes(5), "x"));
        taskManager.insert(buildTask(dh, bhB, DhTaskAction.LIKE, DhTaskScene.OFFLINE, now.plusMinutes(5), "y"));
        // bhC 无任务

        Set<Long> onlineHit = taskManager.findExistingToUserIdsByScene(List.of(bhA, bhB, bhC), DhTaskScene.ONLINE);
        Set<Long> offlineHit = taskManager.findExistingToUserIdsByScene(List.of(bhA, bhB, bhC), DhTaskScene.OFFLINE);

        assertThat(onlineHit).containsExactly(bhA);
        assertThat(offlineHit).containsExactly(bhB);
    }

    @Test
    void deleteByIdHard_removes_row() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DhInteractionTask t = buildTask(ID_RANGE_LO + 30, ID_RANGE_LO + 31,
                DhTaskAction.VISIT, DhTaskScene.ONLINE, now, null);
        taskManager.insert(t);
        assertThat(t.getId()).isNotNull();

        int affected = taskManager.deleteByIdHard(t.getId());
        assertThat(affected).isEqualTo(1);

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dh_interaction_task WHERE id = ?", Long.class, t.getId());
        assertThat(count).isZero();
    }

    private static DhInteractionTask buildTask(long fromDh, long toBh, short action, short scene,
                                               OffsetDateTime executeAt, String likeContent) {
        DhInteractionTask t = new DhInteractionTask();
        t.setFromUserId(fromDh);
        t.setToUserId(toBh);
        t.setAction(action);
        t.setScene(scene);
        t.setExecuteTime(executeAt);
        t.setLikeContent(likeContent);
        return t;
    }
}
