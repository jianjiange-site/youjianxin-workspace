-- =====================================================================
-- V5__add_beauty_race.sql
-- user-service:user_info 加 2 个召回维度字段(beauty_score / race)
--                + 召回主索引(覆盖 user_type + gender + city_id + age + beauty_score 5 维)
--
-- 背景:
--   服务于新增 match-service 的 D0/D1 召回与排序。
--   详见 dating-server/docs/match-service-prd-tech.md §4.1 / §4.2 / §6.7。
--
--   - beauty_score:0-100 颜值分,D1 打分公式 0.30 权重。短期 DB DEFAULT 50(中位数);
--                  长期由 vision-agent 异步推断 + 回写。
--   - race:Asian / Black / White / Latino / Other 等;D0 字典序排序"同人种优先";
--          用 VARCHAR 不用 enum,运营随时增减分类不发版。可空(用户未填写)。
--
--   - 召回索引:覆盖 listDhCandidates / nearbyUsers 的核心过滤维度。
--             PG partial index WHERE deleted = false 跳过软删行。
--
-- 全部 DDL 用 IF NOT EXISTS 写成幂等;新环境跑 V1 已同步回填(保持"全量 schema"语义),
-- 已上线环境靠 V5 增量补齐。
-- =====================================================================

SET TIME ZONE 'UTC';

-- =====================================================================
-- 1. user_info  ---------- 追加 beauty_score / race 两列
-- =====================================================================
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS beauty_score SMALLINT NOT NULL DEFAULT 50
    CHECK (beauty_score BETWEEN 0 AND 100);
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS race         VARCHAR(20);

COMMENT ON COLUMN user_info.beauty_score IS '颜值分 0-100;DEFAULT 50(中位数兜底);vision-agent 异步推断 + 回写;D1 打分公式 0.30 权重';
COMMENT ON COLUMN user_info.race         IS '人种(Asian / Black / White / Latino / Other ...);可空 = 用户未填写;D0 字典序"同人种优先";VARCHAR 不 enum,运营随时增减分类不发版';


-- =====================================================================
-- 2. 召回主索引  ---------- 覆盖 listDhCandidates / nearbyUsers 核心过滤维度
-- =====================================================================
-- 设计:
--   - 列序按"过滤选择性 + 范围扫"经验放:
--       user_type → gender → city_id(高基数等值过滤)
--       age → beauty_score(范围扫)
--   - WHERE deleted = false 让索引只包含活跃用户,缩小体积
--   - 不放 last_open_at(更新频繁会刷脏索引);活跃过滤在 service 层做
CREATE INDEX IF NOT EXISTS idx_user_info_recall
    ON user_info (user_type, gender, city_id, age, beauty_score)
    WHERE deleted = false;

COMMENT ON INDEX idx_user_info_recall IS 'match-service D0/D1 召回主索引:user_type + gender + city_id + age + beauty_score;partial index 跳过软删';
