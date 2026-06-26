-- =====================================================================
-- V4__dh_interaction_task.sql
-- match-service:DH 模拟互动计划任务表 + 配套字段/索引
--
-- 详见 docs/match-service-prd-tech.md §6.3:
--   - OnlinePlanGenerator(每 1 min)+ OfflinePlanGenerator(每 20 min)
--     写本表;LikeVisitorTaskExecutor(每 1 min)按 execute_time 到期消费,
--     成功后硬删本表行(短生命周期、不软删)
--   - like_record 加 like_content(DH 任务带文案,真人 swipe 为 NULL)
--   - visit_record 加 source(PROFILE_VIEW / DH_PLAN_ONLINE / DH_PLAN_OFFLINE)
--   - 两张 record 表各加一个 (to_user_id, source, *_at) 索引,
--     供 6.4 「单 BH 24h 内 DH like/visit 上限」查询走索引
--
-- 与 V1/V2/V3 一致:单表无 JOIN / 全 UTC TIMESTAMPTZ / bigserial
-- =====================================================================

SET TIME ZONE 'UTC';

-- =====================================================================
-- 1. dh_interaction_task  ── DH 模拟互动计划任务(短生命周期)
-- =====================================================================
-- generator 写、executor 读 + 硬删;表内任意时刻只装"未执行 + 失败重试中"两类行
-- 不软删、不审计:任务表是过渡数据,留 30d 归档无价值
CREATE TABLE IF NOT EXISTS dh_interaction_task (
    id            BIGSERIAL    PRIMARY KEY,
    from_user_id  BIGINT       NOT NULL,                  -- DH user_id(发起方)
    to_user_id    BIGINT       NOT NULL,                  -- 真人 user_id(接收方)
    action        SMALLINT     NOT NULL,                  -- 1=LIKE 2=VISIT(见 DhTaskAction)
    scene         SMALLINT     NOT NULL,                  -- 1=ONLINE 2=OFFLINE(见 DhTaskScene)
    execute_time  TIMESTAMPTZ  NOT NULL,                  -- 计划执行时刻;executor 扫 WHERE execute_time <= now()
    like_content  VARCHAR(200),                           -- action=LIKE 才填;VISIT 为 NULL
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- executor 扫表主索引:WHERE execute_time <= NOW() ORDER BY execute_time LIMIT 1000
CREATE INDEX IF NOT EXISTS idx_dh_task_execute_time
    ON dh_interaction_task (execute_time);

-- generator 去重索引:WHERE to_user_id = ? AND scene = ? LIMIT 1
CREATE INDEX IF NOT EXISTS idx_dh_task_to_user_scene
    ON dh_interaction_task (to_user_id, scene);

COMMENT ON TABLE  dh_interaction_task              IS 'DH 模拟互动计划任务(短生命周期,executor 执行后硬删);见 docs §6.3';
COMMENT ON COLUMN dh_interaction_task.from_user_id IS 'DH user_id(发起方);真人为 BH';
COMMENT ON COLUMN dh_interaction_task.to_user_id   IS '真人 BH user_id(接收方);DH 不接收 like/visit';
COMMENT ON COLUMN dh_interaction_task.action       IS '1=LIKE(落 like_record) 2=VISIT(落 visit_record)';
COMMENT ON COLUMN dh_interaction_task.scene        IS '1=ONLINE(Online 计划) 2=OFFLINE(Offline 计划)';
COMMENT ON COLUMN dh_interaction_task.execute_time IS '计划执行时刻;由 generator 在 [now, now+30min] 内均匀随机分布,模拟真人节奏';
COMMENT ON COLUMN dh_interaction_task.like_content IS 'LIKE 文案(从 Nacos match.dh_plan.like_content_templates 抽);VISIT 为 NULL';


-- =====================================================================
-- 2. like_record:加 like_content(DH 任务携带的文案;真人 swipe 为 NULL)
-- =====================================================================
ALTER TABLE like_record
    ADD COLUMN IF NOT EXISTS like_content VARCHAR(200);

COMMENT ON COLUMN like_record.like_content IS 'DH 计划生成 LIKE 时携带的文案;真人 RIGHT_SWIPE / SUPER_HI 留 NULL';

-- 6.4 「单 BH 24h 内 DH like 上限 15 条」 走的查询:
--   SELECT COUNT(*) FROM like_record
--   WHERE to_user_id=? AND source IN ('DH_PLAN_ONLINE','DH_PLAN_OFFLINE')
--     AND liked_at >= NOW() - interval '24h' AND deleted = false
-- 复用 source 列差分 BH/DH;不再新增 from_user_type 列(避免冗余)
CREATE INDEX IF NOT EXISTS idx_like_to_source_time
    ON like_record (to_user_id, source, liked_at DESC)
    WHERE deleted = false;


-- =====================================================================
-- 3. visit_record:加 source(区分 PROFILE_VIEW 与 DH_PLAN_* 来源)
-- =====================================================================
ALTER TABLE visit_record
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'PROFILE_VIEW';

COMMENT ON COLUMN visit_record.source IS 'PROFILE_VIEW(真人主页访问) / DH_PLAN_ONLINE / DH_PLAN_OFFLINE(DH 计划生成)';

-- 6.4 「单 BH 24h 内 DH visit 上限 25 条」 走的查询同上,(to_user_id, source, last_visited_at DESC)
CREATE INDEX IF NOT EXISTS idx_visit_to_source_time
    ON visit_record (to_user_id, source, last_visited_at DESC)
    WHERE deleted = false;
