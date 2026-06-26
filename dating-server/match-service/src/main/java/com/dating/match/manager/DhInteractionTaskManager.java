package com.dating.match.manager;

import com.dating.match.entity.DhInteractionTask;
import com.dating.match.mapper.DhInteractionTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * dh_interaction_task 包装:批量 INSERT(generator)+ 扫表 / 硬删(executor)+ 去重查询。
 *
 * <p>详见 docs §6.3 / V4 migration。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DhInteractionTaskManager {

    private final DhInteractionTaskMapper mapper;

    /**
     * 单条 INSERT;批量场景由 generator 端循环调用即可(每分钟数百条量级,无需 batch insert 优化)。
     */
    public void insert(DhInteractionTask task) {
        mapper.insert(task);
    }

    public List<DhInteractionTask> scanDue(int limit) {
        return mapper.scanDue(limit);
    }

    /** 硬删任务行(executor 执行成功后)。 */
    public int deleteByIdHard(long id) {
        return mapper.deleteByIdHard(id);
    }

    /**
     * 返回入参 {@code toUserIds} 中"已有未执行 + 同 scene 任务"的 user_id 集合。
     * generator 端用于过滤"已经排过本 scene 任务的用户"(单 scene 一次性 dedup)。
     */
    public Set<Long> findExistingToUserIdsByScene(List<Long> toUserIds, short scene) {
        if (toUserIds == null || toUserIds.isEmpty()) return Collections.emptySet();
        List<Long> hit = mapper.findExistingToUserIdsByScene(toUserIds, scene);
        return new HashSet<>(hit);
    }

    /** 积压监控:超过 5min 未消费的到期任务数。 */
    public long countOverdue() {
        return mapper.countOverdue();
    }
}
