-- =====================================================================
-- V3__add_geo_fields.sql
-- user-service:user_info 加 5 个地理列(state_code/city/city_id/lat/lng)
--                + 新建 geo_city 字典表(美国城市 ~28k 行,SimpleMaps Basic)
--
-- 背景:
--   注册让用户从 state→city 联动下拉里选候选(防止自由输入漂移),
--   DB 同时存 state_code/city 文本(展示+排查)、city_id 外引 geo_city
--   (权威键)、lat/lng 冗余城市中心点(Haversine 距离匹配)。
--   同城判定直接比 city_id;大都市内部全显示同城是已知 trade-off,可接受。
--
-- 本迁移只建结构,不灌 geo_city 数据 —— 数据由 V4__seed_geo_city.sql 单独负责。
--
-- 全部 DDL 用 IF NOT EXISTS 写成幂等;V1 已同步回填(保持"全量 schema"语义),
-- 新环境跑 V1 即可建到位,已上线环境靠 V3 增量补齐。
-- =====================================================================

SET TIME ZONE 'UTC';

-- =====================================================================
-- 1. user_info  ---------- 追加 5 个地理列 + city_id 索引
-- =====================================================================
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS state_code CHAR(2);
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS city       VARCHAR(80);
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS city_id    BIGINT;
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS lat        NUMERIC(9,6);
ALTER TABLE user_info ADD COLUMN IF NOT EXISTS lng        NUMERIC(9,6);

CREATE INDEX IF NOT EXISTS idx_user_info_city_id
    ON user_info (city_id)
    WHERE NOT deleted AND city_id IS NOT NULL;

COMMENT ON COLUMN user_info.state_code IS '美国州 USPS 2 字母缩写(CA/NY/...);与 geo_city.state_code 同源,展示+冗余;非美场景为 NULL';
COMMENT ON COLUMN user_info.city       IS '城市名(展示快照,与 geo_city.city 一致);取自注册时下拉候选,防止用户自由输入漂移';
COMMENT ON COLUMN user_info.city_id    IS '关联 geo_city.id(应用层维护,不写外键);权威键,同城判定/统计/筛选以此为准;空=未设置居住地';
COMMENT ON COLUMN user_info.lat        IS '居住城市中心点纬度(NUMERIC 9,6 ~ 0.11m 精度);冗余自 geo_city,Haversine 距离匹配用';
COMMENT ON COLUMN user_info.lng        IS '居住城市中心点经度(NUMERIC 9,6);冗余自 geo_city,Haversine 距离匹配用';


-- =====================================================================
-- 2. geo_city  ---------- 美国城市字典(reference table)
-- =====================================================================
-- 数据源:SimpleMaps US Cities Basic (https://simplemaps.com/data/us-cities)
-- 许可:CC-BY 4.0,公网入口需 attribution 链接(由产品/前端承接)
-- 字段子集:city / state_id→state_code / state_name / lat / lng / population / id→source_id
--
-- reference table 与业务表对待方式不同:
--   • 不需要软删(deleted) —— 城市不会被业务"删除",字典刷新通过 source_id 做 upsert
--   • 不需要 updated_at —— 字典刷新整批替换,审计意义弱;保留 created_at 用于排查灌库时间
--   • 不写外键(对齐 CLAUDE.md "不写外键 / 不写触发器")
CREATE TABLE IF NOT EXISTS geo_city (
    id           BIGSERIAL    PRIMARY KEY,
    city         VARCHAR(80)  NOT NULL,
    state_code   CHAR(2)      NOT NULL,
    state_name   VARCHAR(40)  NOT NULL,
    country_code CHAR(2)      NOT NULL DEFAULT 'US',
    lat          NUMERIC(9,6) NOT NULL,
    lng          NUMERIC(9,6) NOT NULL,
    population   BIGINT,
    source_id    VARCHAR(32),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 同 (city, state_code, country_code) 唯一;防止重复灌库
CREATE UNIQUE INDEX IF NOT EXISTS uk_geo_city_city_state_country
    ON geo_city (city, state_code, country_code);

-- 按州过滤(联动下拉第二步,先选州再列城市)
CREATE INDEX IF NOT EXISTS idx_geo_city_state_code
    ON geo_city (state_code);

-- autocomplete 按人口降序排候选 —— 用户搜"san"先弹 San Francisco / San Antonio / San Diego
CREATE INDEX IF NOT EXISTS idx_geo_city_population
    ON geo_city (population DESC NULLS LAST);

COMMENT ON TABLE  geo_city               IS '美国城市字典(SimpleMaps US Cities Basic ~28k 行,CC-BY 4.0);user_info.city_id 引用此表;reference table,无软删';
COMMENT ON COLUMN geo_city.id            IS '物理主键(BIGSERIAL),user_info.city_id 引用此列';
COMMENT ON COLUMN geo_city.city          IS '城市名,与 state_code 联合唯一;来源 SimpleMaps city 列';
COMMENT ON COLUMN geo_city.state_code    IS 'USPS 2 字母州缩写(CA/NY/...);来源 SimpleMaps state_id';
COMMENT ON COLUMN geo_city.state_name    IS '州全名(California 等),展示用;来源 SimpleMaps state_name';
COMMENT ON COLUMN geo_city.country_code  IS 'ISO 3166-1 alpha-2,当前固定 US,为后续国际化预留';
COMMENT ON COLUMN geo_city.lat           IS '城市中心点纬度(NUMERIC 9,6)';
COMMENT ON COLUMN geo_city.lng           IS '城市中心点经度(NUMERIC 9,6)';
COMMENT ON COLUMN geo_city.population    IS '人口估算(SimpleMaps population);autocomplete 按此降序排候选;NULL=未提供';
COMMENT ON COLUMN geo_city.source_id     IS 'SimpleMaps 原表 id,用于将来再次导入时 upsert 对齐';
COMMENT ON COLUMN geo_city.created_at    IS '记录创建时间(灌库时间),默认 NOW()';
