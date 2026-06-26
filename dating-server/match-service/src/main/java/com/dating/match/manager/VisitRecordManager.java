package com.dating.match.manager;

import com.dating.match.constant.VisitSource;
import com.dating.match.entity.VisitRecord;
import com.dating.match.mapper.VisitRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * visit_record 单表读写。
 *
 * <p>真人路径走 {@link #recordVisit}(source=PROFILE_VIEW DEFAULT);
 * DH 计划路径走 {@link #upsertDhVisit}(显式 source=DH_PLAN_*)。
 * 读由 MatchGrpcService.listVisitsOfMe 用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitRecordManager {

    private final VisitRecordMapper mapper;

    /**
     * UPSERT 主页访问;首次 INSERT visit_count=1 + source=PROFILE_VIEW,重复 UPDATE 累加。
     */
    public void recordVisit(long viewerUserId, long targetUserId) {
        mapper.upsertVisit(viewerUserId, targetUserId);
    }

    /**
     * DH 计划路径 UPSERT:source 必须是 DH_PLAN_ONLINE / DH_PLAN_OFFLINE。
     * 由 LikeVisitorTaskExecutor 在独立短事务里调用,docs §6.3.3。
     */
    public void upsertDhVisit(long fromDhUserId, long toUserId, String source) {
        mapper.upsertVisitWithSource(fromDhUserId, toUserId, source);
    }

    /** "Visits of me" 分页(last_visited_at 倒序)。 */
    public List<VisitRecord> listToMe(long userId, OffsetDateTime cursor, int pageSize) {
        return mapper.selectVisitsToUser(userId, cursor, pageSize);
    }

    /** 6.4 cap 检查:目标 BH 在最近 24h 内已被 DH 计划 visit 的行数。 */
    public long countRecentDhVisits(long toUserId, int lookbackHours) {
        return mapper.countRecentBySource(toUserId, Arrays.asList(VisitSource.DH_SOURCES), lookbackHours);
    }

    /** generator exclude:该 BH 已被哪些 from_user_id visit 过。 */
    public List<Long> visitedFromIdsOf(long toUserId) {
        return mapper.selectFromUserIdsByTo(toUserId);
    }
}
