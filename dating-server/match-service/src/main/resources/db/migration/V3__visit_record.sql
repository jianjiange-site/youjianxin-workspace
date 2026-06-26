-- =====================================================================
-- V3__visit_record.sql
-- match-service:真人访问别人主页的归档(UPSERT 累加 visit_count)
--
-- 详见 docs/match-service-prd-tech.md §6.2;App 端进入他人主页时
-- 由 mobile-gateway 转 gRPC RecordVisit 调用,服务端 UPSERT。
--
-- 与 V1/V2 一致:单表无 JOIN / 全 UTC TIMESTAMPTZ / bigserial / 逻辑删除
-- =====================================================================

SET TIME ZONE 'UTC';

CREATE TABLE IF NOT EXISTS visit_record (
    id                BIGSERIAL    PRIMARY KEY,
    from_user_id      BIGINT       NOT NULL,
    to_user_id        BIGINT       NOT NULL,
    visit_count       INT          NOT NULL DEFAULT 1,
    first_visited_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_visited_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    -- ON CONFLICT UPSERT 的目标约束
    CONSTRAINT uk_visit_from_to UNIQUE (from_user_id, to_user_id)
);

-- 预备给后续 ListVisitsOfMe;本期没读路径,一次建好避免后续 migrate 加索引锁表
CREATE INDEX IF NOT EXISTS idx_visit_to_time
    ON visit_record (to_user_id, last_visited_at DESC)
    WHERE deleted = false;

COMMENT ON TABLE  visit_record                  IS '真人访问别人主页的归档;UPSERT 累加 visit_count;数据源 App 端 RecordVisit gRPC';
COMMENT ON COLUMN visit_record.visit_count      IS '累计访问次数;每次 UPSERT +1';
COMMENT ON COLUMN visit_record.last_visited_at  IS '最近一次访问时间;UPSERT 时 NOW();UI 列表按此倒序';
COMMENT ON COLUMN visit_record.first_visited_at IS '首次访问时间;INSERT 时设,UPDATE 不变';
