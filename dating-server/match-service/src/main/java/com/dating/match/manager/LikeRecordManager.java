package com.dating.match.manager;

import com.dating.match.constant.LikeSource;
import com.dating.match.entity.LikeRecord;
import com.dating.match.mapper.LikeRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * like_record 单表读写。
 *
 * <p>真人 swipe 走 {@link #insertIfAbsent}(swipe 层已幂等,DB UNIQUE 兜底);
 * DH 计划走 {@link #upsertDhLike}(PG ON CONFLICT 覆写 source/likedAt/likeContent)。
 * 读取由 MatchGrpcService.listLikesOfMe 用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeRecordManager {

    private final LikeRecordMapper mapper;

    /**
     * 真人路径:落 like_record;UNIQUE 冲突静默吞掉(swipe 表层幂等已先一步拦,这里 DB 兜底防御)。
     */
    public void insertIfAbsent(long fromUserId, long toUserId, String source) {
        LikeRecord row = new LikeRecord();
        row.setFromUserId(fromUserId);
        row.setToUserId(toUserId);
        row.setSource(source);
        row.setLikedAt(OffsetDateTime.now());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException dup) {
            log.debug("like_record duplicate from={} to={} source={} ── swipe 幂等已拦,DB 兜底正常",
                    fromUserId, toUserId, source);
        }
    }

    /**
     * DH 计划路径 UPSERT:source 必须是 DH_PLAN_ONLINE / DH_PLAN_OFFLINE,likeContent 可为 null。
     * 由 LikeVisitorTaskExecutor 在独立短事务里调用,docs §6.3.3。
     */
    public void upsertDhLike(long fromDhUserId, long toUserId, String source, String likeContent) {
        mapper.upsertLike(fromDhUserId, toUserId, source, likeContent);
    }

    /** "Likes of me" 分页(liked_at 倒序)。 */
    public List<LikeRecord> listToMe(long userId, OffsetDateTime cursor, int pageSize) {
        return mapper.selectLikesToUser(userId, cursor, pageSize);
    }

    /**
     * 6.4 cap 检查:目标 BH 在最近 24h 内已被 DH 计划 like 的次数。
     */
    public long countRecentDhLikes(long toUserId, int lookbackHours) {
        return mapper.countRecentBySource(toUserId, Arrays.asList(LikeSource.DH_SOURCES), lookbackHours);
    }

    /** generator exclude:该 BH 已被哪些 from_user_id like 过。 */
    public List<Long> likedFromIdsOf(long toUserId) {
        return mapper.selectFromUserIdsByTo(toUserId);
    }
}
