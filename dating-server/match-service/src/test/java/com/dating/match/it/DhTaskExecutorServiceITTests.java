package com.dating.match.it;

import com.dating.match.constant.DhTaskAction;
import com.dating.match.constant.DhTaskScene;
import com.dating.match.constant.LikeSource;
import com.dating.match.constant.VisitSource;
import com.dating.match.entity.DhInteractionTask;
import com.dating.match.manager.DhInteractionTaskManager;
import com.dating.match.service.DhTaskExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LikeVisitorTaskExecutor 单步执行链路(executeOne 短事务)端到端 IT。
 *
 * <p>覆盖 docs §6.3.3 主路径:任务行 → UPSERT like/visit_record → 硬删任务行。
 */
class DhTaskExecutorServiceITTests extends DevInfraITSupport {

    @Autowired DhTaskExecutorService taskExecutorService;
    @Autowired DhInteractionTaskManager taskManager;

    @Test
    void executeOne_LIKE_writes_like_record_and_deletes_task() {
        long dh = ID_RANGE_LO;
        long bh = ID_RANGE_LO + 1;
        DhInteractionTask task = newTask(dh, bh, DhTaskAction.LIKE, DhTaskScene.ONLINE, "hi");
        taskManager.insert(task);
        Long taskId = task.getId();

        taskExecutorService.executeOne(task);

        // like_record 落了一行,source = DH_PLAN_ONLINE
        String source = jdbc.queryForObject(
                "SELECT source FROM like_record WHERE from_user_id = ? AND to_user_id = ?",
                String.class, dh, bh);
        assertThat(source).isEqualTo(LikeSource.DH_PLAN_ONLINE);
        String content = jdbc.queryForObject(
                "SELECT like_content FROM like_record WHERE from_user_id = ? AND to_user_id = ?",
                String.class, dh, bh);
        assertThat(content).isEqualTo("hi");

        // 任务行被硬删
        Long left = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dh_interaction_task WHERE id = ?", Long.class, taskId);
        assertThat(left).isZero();
    }

    @Test
    void executeOne_VISIT_OFFLINE_writes_visit_record_with_source() {
        long dh = ID_RANGE_LO + 5;
        long bh = ID_RANGE_LO + 6;
        DhInteractionTask task = newTask(dh, bh, DhTaskAction.VISIT, DhTaskScene.OFFLINE, null);
        taskManager.insert(task);

        taskExecutorService.executeOne(task);

        Integer cnt = jdbc.queryForObject(
                "SELECT visit_count FROM visit_record WHERE from_user_id = ? AND to_user_id = ?",
                Integer.class, dh, bh);
        assertThat(cnt).isEqualTo(1);
        String src = jdbc.queryForObject(
                "SELECT source FROM visit_record WHERE from_user_id = ? AND to_user_id = ?",
                String.class, dh, bh);
        assertThat(src).isEqualTo(VisitSource.DH_PLAN_OFFLINE);

        // 任务行被硬删
        Long left = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dh_interaction_task WHERE id = ?", Long.class, task.getId());
        assertThat(left).isZero();
    }

    @Test
    void executeOne_VISIT_twice_increments_visit_count() {
        long dh = ID_RANGE_LO + 10;
        long bh = ID_RANGE_LO + 11;
        // 第一次
        DhInteractionTask t1 = newTask(dh, bh, DhTaskAction.VISIT, DhTaskScene.ONLINE, null);
        taskManager.insert(t1);
        taskExecutorService.executeOne(t1);
        // 第二次(generator 一般不会安排同 from/to 重复,但 UPSERT 兜底语义要正确)
        DhInteractionTask t2 = newTask(dh, bh, DhTaskAction.VISIT, DhTaskScene.OFFLINE, null);
        taskManager.insert(t2);
        taskExecutorService.executeOne(t2);

        Integer cnt = jdbc.queryForObject(
                "SELECT visit_count FROM visit_record WHERE from_user_id = ? AND to_user_id = ?",
                Integer.class, dh, bh);
        assertThat(cnt).isEqualTo(2);
    }

    private static DhInteractionTask newTask(long from, long to, short action, short scene, String content) {
        DhInteractionTask t = new DhInteractionTask();
        t.setFromUserId(from);
        t.setToUserId(to);
        t.setAction(action);
        t.setScene(scene);
        t.setExecuteTime(OffsetDateTime.now(ZoneOffset.UTC));
        t.setLikeContent(content);
        return t;
    }
}
