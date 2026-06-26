-- =====================================================================
-- V2__like_record.sql
-- match-service:真人 like 操作归档(RIGHT_SWIPE / SUPER_HI 都落)
--
-- 详见 docs/match-service-prd-tech.md §6.2 + 业务侧偏离决策:
-- 走"归档"语义而非 PRD"暗恋未回应"语义 ── RIGHT_SWIPE / SUPER_HI 都落,
-- 即时 match 形成也不删。App "Likes of me" 列表展示全部,客户端自行去重 / 打 badge。
--
-- 与 V1 一致:单表无 JOIN / 全 UTC TIMESTAMPTZ / bigserial / 逻辑删除
-- =====================================================================

SET TIME ZONE 'UTC';

CREATE TABLE IF NOT EXISTS like_record (
    id            BIGSERIAL    PRIMARY KEY,
    from_user_id  BIGINT       NOT NULL,
    to_user_id    BIGINT       NOT NULL,
    -- SWIPE_RIGHT / SUPER_HI(未来扩 DH_SIM 等)
    source        VARCHAR(20)  NOT NULL,
    liked_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 同一 (from, to) 只允许一条 ── swipe 表层已有幂等,这里 DB 兜底防御
    CONSTRAINT uk_like_from_to UNIQUE (from_user_id, to_user_id)
);

-- ListLikesOfMe 主查询索引:WHERE to_user_id = me AND deleted = false ORDER BY liked_at DESC
CREATE INDEX IF NOT EXISTS idx_like_to_time
    ON like_record (to_user_id, liked_at DESC)
    WHERE deleted = false;

COMMENT ON TABLE  like_record              IS '真人 like 归档:RIGHT_SWIPE / SUPER_HI 都落,即时 match 也不删;App "Likes of me" 列表数据源';
COMMENT ON COLUMN like_record.source       IS 'SWIPE_RIGHT(单向右划,可能后续触发互划 match) / SUPER_HI(立即 match,但 like_record 仍归档)';
COMMENT ON COLUMN like_record.from_user_id IS '点赞方;always 真人 BH(DH 模拟由后续 DH_SIM source 区分)';
