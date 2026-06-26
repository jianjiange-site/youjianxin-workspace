-- =====================================================================
-- V1__init_match_tables.sql
-- match-service:划卡历史 / 匹配关系 / 副作用 outbox 三张表
--
-- 详见 dating-server/docs/match-service-prd-tech.md §6.2
--
-- 设计原则:
-- - 单表无 JOIN(CLAUDE.md 红线 1);跨表数据在 service 层拼装
-- - 全 UTC TIMESTAMPTZ;雪花 id 由 DB bigserial 提供;deleted 软删
-- - 配额 / 队列 / 已 swipe 集合都在 Redis(见 docs 6.3);PG 只保留三张权威表
-- =====================================================================

SET TIME ZONE 'UTC';

-- =====================================================================
-- 1. user_swipe_history  ── 划卡历史(权威记录,召回阶段 exclude 的源头)
-- =====================================================================
-- 高写入:活跃用户 N 张/天;按 user_id hash 分区暂不做,先单表(MVP 量级)
-- UNIQUE (user_id, target_user_id) 保证幂等 + 防止"已 swipe 重复出现"的 DB 兜底
CREATE TABLE IF NOT EXISTS user_swipe_history (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    target_user_id    BIGINT       NOT NULL,
    -- 1=BH 2=DH;来自 user-service.user_type
    target_user_type  SMALLINT     NOT NULL,
    -- 1=LEFT(不喜欢) 2=RIGHT(喜欢) 3=SUPER_HI
    direction         SMALLINT     NOT NULL,
    swiped_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_swipe_user_target UNIQUE (user_id, target_user_id)
);

-- "我最近 30 天划过谁"用于 D1 偏好建模
CREATE INDEX IF NOT EXISTS idx_swipe_user_time
    ON user_swipe_history (user_id, swiped_at DESC)
    WHERE deleted = false;

-- "对方曾右划过我"用于 BH-BH 互划立即匹配判定 + D1 mutual_like_bonus
CREATE INDEX IF NOT EXISTS idx_swipe_target_dir
    ON user_swipe_history (target_user_id, direction)
    WHERE deleted = false;

COMMENT ON TABLE  user_swipe_history                IS '划卡历史(权威记录)。召回阶段排除 + Swipe 幂等 + D1 偏好建模 + mutual_like 反查';
COMMENT ON COLUMN user_swipe_history.direction      IS '1=LEFT(不喜欢) 2=RIGHT(喜欢) 3=SUPER_HI;Swipe RPC 不收 SUPER_HI,由 SuperHi RPC 独立写入';
COMMENT ON COLUMN user_swipe_history.target_user_type IS '1=BH 2=DH;Swipe service 层调 user-service.user_type 写入,避免后续 join';


-- =====================================================================
-- 2. match  ── 匹配关系(user_id_low / user_id_high 规范化,一对人一行)
-- =====================================================================
CREATE TABLE IF NOT EXISTS match (
    id             BIGSERIAL    PRIMARY KEY,
    user_id_low    BIGINT       NOT NULL,   -- min(uid1, uid2)
    user_id_high   BIGINT       NOT NULL,   -- max(uid1, uid2)
    matched_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- 入口动作维度:SWIPE_MATCH / SWIPE_SUPER_HI(详见 match.proto + docs §5.1)
    source         VARCHAR(30)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted        BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_match_pair UNIQUE (user_id_low, user_id_high),
    CONSTRAINT ck_match_order CHECK (user_id_low < user_id_high)
);

-- 我的匹配列表:user_id_low=我 或 user_id_high=我,各建一索引
CREATE INDEX IF NOT EXISTS idx_match_low_time
    ON match (user_id_low, matched_at DESC) WHERE deleted = false;
CREATE INDEX IF NOT EXISTS idx_match_high_time
    ON match (user_id_high, matched_at DESC) WHERE deleted = false;

COMMENT ON TABLE  match              IS '匹配关系。UNIQUE 兜底重复 match(召回过滤 bug 时打 ERROR 日志,详见 docs §5.3)';
COMMENT ON COLUMN match.source       IS '入口动作:SWIPE_MATCH(BH 互划 / DH 延迟回调) / SWIPE_SUPER_HI;BH/DH 维度由 user-service.user_type 反查,不冗余';
COMMENT ON COLUMN match.user_id_low  IS '较小一方 user_id(规范化);ck_match_order 保证 user_id_low < user_id_high';


-- =====================================================================
-- 3. match_outbox  ── match 副作用 outbox(IM 建会话 / 系统消息 / DH 开场白)
-- =====================================================================
-- 详见 docs §5.3:createMatch 在同一事务里写 match + outbox,
-- 后台 MatchOutboxRetry 异步消费 + 失败重试 + 终态 DEAD 报警
CREATE TABLE IF NOT EXISTS match_outbox (
    id             BIGSERIAL    PRIMARY KEY,
    match_id       BIGINT       NOT NULL,
    -- ENSURE_CONVERSATION / SYSTEM_MSG / DH_OPENING
    action         VARCHAR(40)  NOT NULL,
    payload_json   JSONB        NOT NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    next_retry_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- PENDING / DONE / DEAD
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted        BOOLEAN      NOT NULL DEFAULT FALSE
);

-- retry worker 扫描:PENDING + 已到下次重试时刻
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON match_outbox (next_retry_at)
    WHERE status = 'PENDING' AND deleted = false;

-- 按 match_id 反查所有副作用(运营审计)
CREATE INDEX IF NOT EXISTS idx_outbox_match
    ON match_outbox (match_id) WHERE deleted = false;

COMMENT ON TABLE  match_outbox          IS 'match 创建后副作用 outbox(IM 建会话 / 系统消息 / DH 开场白);事务一致 + 异步重试 + 终态 DEAD 报警';
COMMENT ON COLUMN match_outbox.action   IS 'ENSURE_CONVERSATION / SYSTEM_MSG / DH_OPENING';
COMMENT ON COLUMN match_outbox.status   IS 'PENDING(待消费) / DONE(成功) / DEAD(超过最大重试次数,人工介入)';
