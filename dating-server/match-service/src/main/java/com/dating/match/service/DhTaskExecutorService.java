package com.dating.match.service;

import com.dating.match.constant.DhTaskAction;
import com.dating.match.constant.DhTaskScene;
import com.dating.match.constant.LikeSource;
import com.dating.match.constant.VisitSource;
import com.dating.match.entity.DhInteractionTask;
import com.dating.match.manager.DhInteractionTaskManager;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.VisitRecordManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单条 DH 任务的"原子执行":UPSERT 业务表 + 硬删任务行,独立短事务。
 *
 * <p>抽到独立 @Service 是为了让 {@code @Transactional} 走 Spring 代理生效 ── 同类内自调
 * 会绕过代理。被 {@link com.dating.match.scheduler.LikeVisitorTaskExecutor} 在 sweep 循环里
 * 逐条调用。详见 docs §6.3.3。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DhTaskExecutorService {

    private final LikeRecordManager likeRecordManager;
    private final VisitRecordManager visitRecordManager;
    private final DhInteractionTaskManager taskManager;

    /**
     * 独立短事务:UPSERT like/visit_record + DELETE 任务行。
     *
     * <p>任一步抛异常 → 事务回滚 → 任务行保留 → 下一轮 scanDue 仍会取到。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void executeOne(DhInteractionTask task) {
        String source = sourceFor(task.getScene(), task.getAction());

        if (task.getAction() == DhTaskAction.LIKE) {
            likeRecordManager.upsertDhLike(
                    task.getFromUserId(), task.getToUserId(), source, task.getLikeContent());
        } else if (task.getAction() == DhTaskAction.VISIT) {
            visitRecordManager.upsertDhVisit(
                    task.getFromUserId(), task.getToUserId(), source);
        } else {
            log.error("dh-task unknown action: id={} action={};直接硬删避免无限重试",
                    task.getId(), task.getAction());
        }
        taskManager.deleteByIdHard(task.getId());
    }

    private static String sourceFor(short scene, short action) {
        boolean online = scene == DhTaskScene.ONLINE;
        return action == DhTaskAction.LIKE
                ? (online ? LikeSource.DH_PLAN_ONLINE : LikeSource.DH_PLAN_OFFLINE)
                : (online ? VisitSource.DH_PLAN_ONLINE : VisitSource.DH_PLAN_OFFLINE);
    }
}
