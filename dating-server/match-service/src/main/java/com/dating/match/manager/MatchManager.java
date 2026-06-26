package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.match.entity.Match;
import com.dating.match.mapper.MatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * match 表包装。
 *
 * <p>{@link #insertOrFindExisting} 用 INSERT 兜底 UNIQUE 约束:
 * 第一次写入返回新 Match;并发或重复时反查 existing 并打 ERROR 日志(召回过滤链路有 bug),
 * 见 docs §5.3 与 §6.6。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchManager {

    private final MatchMapper mapper;

    /**
     * 试图插入 (low, high) 一行;命中 UNIQUE 则反查 existing 返回。
     *
     * @return Pair: existed=true 表示已 matched(召回过滤链路 bug),false 表示首次创建
     */
    public Result insertOrFindExisting(long uidA, long uidB, String source) {
        long low = Math.min(uidA, uidB);
        long high = Math.max(uidA, uidB);
        Match existing = mapper.selectByPair(low, high);
        if (existing != null) {
            log.error("Duplicate match attempt: pair=({}, {}) existing_id={} existing_source={} new_source={}"
                            + " ── 上游召回过滤可能有 bug,排查 user_swipe_history 与召回 exclude_user_ids 链路",
                    low, high, existing.getId(), existing.getSource(), source);
            return new Result(existing, true);
        }
        Match row = new Match();
        row.setUserIdLow(low);
        row.setUserIdHigh(high);
        row.setMatchedAt(OffsetDateTime.now());
        row.setSource(source);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException dup) {
            log.error("Concurrent createMatch race: pair=({}, {}) source={};DB UNIQUE 兜底 + 反查 existing",
                    low, high, source, dup);
            Match nowExisting = mapper.selectByPair(low, high);
            return new Result(nowExisting, true);
        }
        return new Result(row, false);
    }

    /** 我的匹配列表:按 matchedAt 降序;pageToken 用 last matchedAt epoch ms */
    public List<Match> listByUser(long userId, OffsetDateTime cursor, int pageSize) {
        LambdaQueryWrapper<Match> w = new LambdaQueryWrapper<>();
        w.and(q -> q.eq(Match::getUserIdLow, userId).or().eq(Match::getUserIdHigh, userId));
        if (cursor != null) w.lt(Match::getMatchedAt, cursor);
        w.orderByDesc(Match::getMatchedAt).last("LIMIT " + pageSize);
        return mapper.selectList(w);
    }

    /**
     * 好友 user_id 列表 (post-service fanout 用)。匹配成功即好友;按 matched_at 倒序。
     */
    public List<Long> listFriendUserIds(long userId, int limit) {
        return mapper.selectFriendUserIds(userId, limit);
    }

    public record Result(Match match, boolean existed) {}
}
