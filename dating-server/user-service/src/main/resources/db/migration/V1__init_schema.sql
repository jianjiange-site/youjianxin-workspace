-- =====================================================================
-- V1__init_schema.sql
-- user-service 全量 schema (PostgreSQL 14+)
--
-- 服务尚未上线,无历史数据:本文件是 user-service 的唯一 schema 真相,
-- 所有建表 / 加列 / 加索引都汇总在这里,保持永远是最新的全量 SQL,不再拆分多个 V 版本。
-- 全部 DDL 用 IF NOT EXISTS 写成幂等:手动贴库 / Flyway 自动迁移 谁先跑都不冲突,可反复执行。
--
-- 5 张业务表:
--   1. user_info                      — 用户主表(含活跃时间 + 居住地)
--   2. user_login_phone               — 手机号 ↔ 用户绑定
--   3. user_third_party_registration  — 第三方账号 ↔ 用户绑定
--   4. user_device_registration       — 设备 ↔ 用户绑定(快速登录用)
--   5. user_interest                  — 兴趣标签
-- 1 张字典表:
--   6. geo_city                       — 美国城市字典(user_info.city_id 引用)
-- 1 张基础表:
--   7. biz_id_seq                     — 业务主键序号表(dating-common BizIdGenerator 用)
--
-- 约定(与 CLAUDE.md 对齐):
--   • 全系统时区统一 UTC:DB session SET TIME ZONE 'UTC',所有 TIMESTAMPTZ 以 UTC 存取
--   • 每张表物理主键统一叫 id (BIGSERIAL)
--   • 业务主键 user_id(env+YYMMDD+当天序号,日期段按 UTC)与物理 id 并存,BizIdGenerator 生成
--   • 软删用 deleted BOOLEAN,配 MyBatis-Plus @TableLogic
--   • 图片只存对象存储 object_key,不存完整 URL、不存 BLOB
--   • 不写外键 / 不写触发器,关联与 updated_at 由应用层维护
--   • 每个字段必须有 COMMENT
-- =====================================================================

SET TIME ZONE 'UTC';

-- =====================================================================
-- 1. user_info  ---------- 用户主表
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_info (
    id                       BIGSERIAL    PRIMARY KEY,

    -- 业务主键:env(1测试/2生产)+YYMMDD(UTC)+当天序号,BizIdGenerator 生成,与物理 id 并存
    user_id                  BIGINT,

    -- 基本资料
    username                 VARCHAR(64),
    nickname                 VARCHAR(64),
    age                      SMALLINT,
    birthday                 VARCHAR(16),
    gender                   SMALLINT     NOT NULL DEFAULT 0,
    height                   SMALLINT,
    bio                      TEXT,
    profession               VARCHAR(128),
    education                VARCHAR(128),

    -- 业务维度
    app_name                 SMALLINT     NOT NULL DEFAULT 0,
    user_type                SMALLINT,
    phone_number             VARCHAR(32),
    email                    VARCHAR(255),
    preferred_location       VARCHAR(128),
    zip_code                 VARCHAR(16),

    -- 居住地(美国):state→city 联动下拉,city_id 引用 geo_city 字典,lat/lng 为城市中心点冗余
    state_code               CHAR(2),
    city                     VARCHAR(80),
    city_id                  BIGINT,
    lat                      NUMERIC(9,6),
    lng                      NUMERIC(9,6),

    -- 召回 / 匹配维度(服务于 match-service D0/D1,见 V5 + dating-server/docs/match-service-prd-tech.md)
    beauty_score             SMALLINT     NOT NULL DEFAULT 50
                                          CHECK (beauty_score BETWEEN 0 AND 100),
    race                     VARCHAR(20),

    locale                   BIGINT,
    platform                 SMALLINT,
    condition                SMALLINT,

    -- 头像 / 图片(一律存对象存储 object_key,不存 URL)
    custom_avatar            JSONB,
    ins_id                   VARCHAR(64),
    ins_avatar_url           TEXT,

    -- 合规
    regulation_status        SMALLINT     NOT NULL DEFAULT 0,
    pending                  BOOLEAN      NOT NULL DEFAULT TRUE,

    -- 活跃
    last_open_at             TIMESTAMPTZ,

    -- 时间戳 / 软删
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted                  BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_user_info_app_name        ON user_info (app_name)               WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS idx_user_info_phone_number    ON user_info (phone_number)           WHERE NOT deleted AND phone_number IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_user_info_email           ON user_info (email)                  WHERE NOT deleted AND email IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_user_info_last_open_at    ON user_info (last_open_at DESC)      WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS idx_user_info_regulation      ON user_info (regulation_status)      WHERE NOT deleted AND regulation_status <> 0;
CREATE INDEX IF NOT EXISTS idx_user_info_city_id          ON user_info (city_id)                WHERE NOT deleted AND city_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_user_info_recall           ON user_info (user_type, gender, city_id, age, beauty_score)
                                                          WHERE NOT deleted;

-- 业务主键唯一索引;允许多个 NULL(PG 语义),不阻塞 placeholder 暂未回填的存量行
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_info_user_id   ON user_info (user_id);

COMMENT ON TABLE  user_info IS '用户主表:身份解析后产生的最小可用用户,onboarding 后补齐资料字段';
COMMENT ON COLUMN user_info.id                       IS '物理主键(BIGSERIAL),系统内全局唯一,外键引用以此为准';
COMMENT ON COLUMN user_info.user_id                  IS '业务主键:环境位(1测试/2生产)+YYMMDD(UTC)+当天序号;BizIdGenerator 生成,与物理主键 id 并存,跨环境可读识别用';
COMMENT ON COLUMN user_info.username                 IS '登录用户名(目前未启用,保留字段);非空则全局唯一由应用层校验';
COMMENT ON COLUMN user_info.nickname                 IS '展示昵称,长度 ≤ 64,前后空格 service 层裁剪;placeholder 用户默认 User_${id}';
COMMENT ON COLUMN user_info.age                      IS '年龄,UI 直接展示;来源为 onboarding 填报,与 birthday 并存';
COMMENT ON COLUMN user_info.birthday                 IS '生日字符串(yyyy-MM-dd 或 yyyy/MM/dd),沿用 sitin 历史格式,新业务以 age 为准';
COMMENT ON COLUMN user_info.gender                   IS '性别枚举:0=unknown 1=male 2=female;由 UpsertOnboarding 写入,定后不可改';
COMMENT ON COLUMN user_info.height                   IS '身高,单位 cm,SMALLINT(0-300);为空表示用户未填写';
COMMENT ON COLUMN user_info.bio                      IS '个人简介,长度 ≤ 500(service 层卡)';
COMMENT ON COLUMN user_info.profession               IS '职业(UI 显示 Occupation,DB 仍叫 profession),长度 ≤ 128;MapStruct converter 做名字映射';
COMMENT ON COLUMN user_info.education                IS '教育背景,自由文本,长度 ≤ 128';
COMMENT ON COLUMN user_info.app_name                 IS 'App 枚举(0=default,见 AppName 枚举),用于多 App 复用同一份 schema';
COMMENT ON COLUMN user_info.user_type                IS 'sitin 历史字段:用户分类(0=普通 / 1=运营内部等),保留兼容,业务侧不暴露写';
COMMENT ON COLUMN user_info.phone_number             IS '冗余手机号(展示用),真实手机绑定见 user_login_phone 表;不作为登录凭证';
COMMENT ON COLUMN user_info.email                    IS '邮箱(联系方式语义),不作为登录凭证;长度 ≤ 255';
COMMENT ON COLUMN user_info.preferred_location       IS '偏好城市/地区文本(例:北京市),长度 ≤ 128';
COMMENT ON COLUMN user_info.zip_code                 IS '邮编,sitin 历史字段,保留但 MVP 不暴露';
COMMENT ON COLUMN user_info.state_code               IS '美国州 USPS 2 字母缩写(CA/NY/...);与 geo_city.state_code 同源,展示+冗余;非美场景为 NULL';
COMMENT ON COLUMN user_info.city                     IS '城市名(展示快照,与 geo_city.city 一致);取自注册时下拉候选,防止用户自由输入漂移';
COMMENT ON COLUMN user_info.city_id                  IS '关联 geo_city.id(应用层维护,不写外键);权威键,同城判定/统计/筛选以此为准;空=未设置居住地';
COMMENT ON COLUMN user_info.lat                      IS '居住城市中心点纬度(NUMERIC 9,6 ~ 0.11m 精度);冗余自 geo_city,Haversine 距离匹配用';
COMMENT ON COLUMN user_info.lng                      IS '居住城市中心点经度(NUMERIC 9,6);冗余自 geo_city,Haversine 距离匹配用';
COMMENT ON COLUMN user_info.locale                   IS '区域设置 ID(BIGINT 编码),sitin 历史字段,保留兼容';
COMMENT ON COLUMN user_info.platform                 IS '注册平台枚举(iOS/Android/Web 等),sitin 历史字段';
COMMENT ON COLUMN user_info.condition                IS 'sitin 历史字段:用户状态扩展位,语义已废弃,保留兼容';
COMMENT ON COLUMN user_info.custom_avatar            IS '自定义头像 JSON:{originalKey, minKey, midKey, width, height},全部为对象存储 object_key,不存 URL';
COMMENT ON COLUMN user_info.ins_id                   IS 'Instagram 账号 ID(展示绑定状态用),不作为登录凭证';
COMMENT ON COLUMN user_info.ins_avatar_url           IS 'Instagram 头像 URL —— 例外保留完整 URL(非对象存储资产,来自 Instagram CDN)';
COMMENT ON COLUMN user_info.regulation_status        IS '合规状态:0=Active 1=KGroup 2=Banned 3=Admin 4=Collaborator 5=Suspended 6=Reported;2/5 命中即视为封禁';
COMMENT ON COLUMN user_info.pending                  IS 'onboarding 未完成标志:true=placeholder(仅手机/三方绑定,资料未补齐);UpsertOnboarding 成功后置 false';
COMMENT ON COLUMN user_info.last_open_at             IS '最近活跃时间;ResolveOrCreate 命中现有用户时触发更新,在线状态展示用';
COMMENT ON COLUMN user_info.beauty_score             IS '颜值分 0-100;DEFAULT 50(中位数兜底);vision-agent 异步推断 + 回写;match-service D1 打分 0.30 权重';
COMMENT ON COLUMN user_info.race                     IS '人种(Asian / Black / White / Latino / Other ...);可空 = 用户未填写;match-service D0 字典序"同人种优先";VARCHAR 不 enum,运营随时增减分类不发版';
COMMENT ON COLUMN user_info.created_at               IS '记录创建时间,应用层 MybatisMetaObjectHandler 填值';
COMMENT ON COLUMN user_info.updated_at               IS '记录最后更新时间,应用层 MybatisMetaObjectHandler 在 insert/update 时填值';
COMMENT ON COLUMN user_info.deleted                  IS '软删标志(MyBatis-Plus @TableLogic);true 后所有读路径都跳过该行';


-- =====================================================================
-- 2. user_login_phone  ---------- 手机号 ↔ 用户绑定
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_login_phone (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    phone_e164   VARCHAR(32)  NOT NULL,
    app_name     SMALLINT     NOT NULL,
    verified_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (phone_e164, app_name)
);

CREATE INDEX IF NOT EXISTS idx_user_login_phone_user_id ON user_login_phone (user_id);

COMMENT ON TABLE  user_login_phone IS '手机号 → 用户绑定;(phone_e164, app_name) 唯一,支持同一手机号在多个 App 各有用户';
COMMENT ON COLUMN user_login_phone.id          IS '绑定记录 ID(主键)';
COMMENT ON COLUMN user_login_phone.user_id     IS '关联的 user_info.id(应用层维护,不写外键)';
COMMENT ON COLUMN user_login_phone.phone_e164  IS '手机号 E.164 标准格式,含 + 号(libphonenumber 规范化后写入)';
COMMENT ON COLUMN user_login_phone.app_name    IS 'App 枚举,与 user_info.app_name 对齐;同号可在不同 App 各自绑定';
COMMENT ON COLUMN user_login_phone.verified_at IS '短信验证通过时间;为空表示历史导入或未走验证流程';
COMMENT ON COLUMN user_login_phone.created_at  IS '绑定创建时间,应用层 MybatisMetaObjectHandler 填值';
COMMENT ON COLUMN user_login_phone.updated_at  IS '绑定最后更新时间,应用层 MybatisMetaObjectHandler 填值';


-- =====================================================================
-- 3. user_third_party_registration  ---------- 第三方账号绑定
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_third_party_registration (
    id                        BIGSERIAL    PRIMARY KEY,
    user_id                   BIGINT       NOT NULL,
    third_party_login_user_id VARCHAR(128) NOT NULL,
    platform                  SMALLINT     NOT NULL,
    google_email              VARCHAR(255),
    registered_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted                   BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 软删后允许同 (third_party_id, platform) 重新绑定,所以唯一约束是 partial index
CREATE UNIQUE INDEX IF NOT EXISTS uq_third_party_active
    ON user_third_party_registration (third_party_login_user_id, platform)
    WHERE NOT deleted;

CREATE INDEX IF NOT EXISTS idx_third_party_user_id ON user_third_party_registration (user_id);

COMMENT ON TABLE  user_third_party_registration IS '第三方账号 → 用户绑定;同 (third_party_login_user_id, platform) 在 deleted=false 下唯一,软删后允许重新绑定';
COMMENT ON COLUMN user_third_party_registration.id                        IS '绑定记录 ID(主键)';
COMMENT ON COLUMN user_third_party_registration.user_id                   IS '关联的 user_info.id(应用层维护,不写外键)';
COMMENT ON COLUMN user_third_party_registration.third_party_login_user_id IS '第三方平台返回的用户 ID(Google sub / Apple sub / Facebook id 等)';
COMMENT ON COLUMN user_third_party_registration.platform                  IS '第三方平台枚举(Google/Apple/Facebook/...),与 ResolveOrCreateByThirdParty 入参一致';
COMMENT ON COLUMN user_third_party_registration.google_email              IS 'Google 登录时携带的邮箱;Apple/Facebook 一律留空';
COMMENT ON COLUMN user_third_party_registration.registered_at             IS '绑定首次注册时间,应用层 MybatisMetaObjectHandler 填值';
COMMENT ON COLUMN user_third_party_registration.updated_at                IS '绑定最后更新时间,应用层 MybatisMetaObjectHandler 填值';
COMMENT ON COLUMN user_third_party_registration.deleted                   IS '软删标志(MyBatis-Plus @TableLogic);软删后该绑定释放,允许同 (third_party, platform) 重新落';


-- =====================================================================
-- 4. user_device_registration  ---------- 设备 ↔ 用户绑定(快速登录)
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_device_registration (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    device_id     VARCHAR(128) NOT NULL,
    platform      SMALLINT     NOT NULL,
    app_name      SMALLINT     NOT NULL DEFAULT 0,
    registered_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- 软删后允许同 (device_id, platform, app_name) 重新绑定,所以唯一约束是 partial index
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_device_active
    ON user_device_registration (device_id, platform, app_name)
    WHERE NOT deleted;

CREATE INDEX IF NOT EXISTS idx_user_device_user_id ON user_device_registration (user_id);

COMMENT ON TABLE  user_device_registration IS '设备 → 用户绑定(快速登录用);同 (device_id, platform, app_name) 在 deleted=false 下唯一,软删后允许重新绑定';
COMMENT ON COLUMN user_device_registration.id            IS '绑定记录 ID(主键)';
COMMENT ON COLUMN user_device_registration.user_id      IS '关联的 user_info.id(应用层维护,不写外键)';
COMMENT ON COLUMN user_device_registration.device_id    IS 'App 上报的设备唯一标识(iOS IDFV / Android SSAID 派生),长度 ≤ 128';
COMMENT ON COLUMN user_device_registration.platform     IS '平台枚举:1=iOS 2=Android 3=Web(与 mobile-gateway.auth_device.platform 对齐)';
COMMENT ON COLUMN user_device_registration.app_name     IS 'App 枚举(与 user_info.app_name 对齐);同设备可在不同 App 各自绑定';
COMMENT ON COLUMN user_device_registration.registered_at IS '绑定首次注册时间,应用层 MybatisMetaObjectHandler 填值';
COMMENT ON COLUMN user_device_registration.updated_at   IS '绑定最后更新时间,应用层 MybatisMetaObjectHandler 填值';
COMMENT ON COLUMN user_device_registration.deleted      IS '软删标志(MyBatis-Plus @TableLogic);快速登录用户绑定手机号/三方后,旧设备绑定可保留或软删';


-- =====================================================================
-- 5. user_interest  ---------- 兴趣标签(1:N)
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_interest (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    tab_key     VARCHAR(64)  NOT NULL,
    tag_key     VARCHAR(128) NOT NULL,
    pic_key     VARCHAR(256),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_interest_user_id ON user_interest (user_id);

COMMENT ON TABLE  user_interest IS '用户兴趣标签(1:N);ReplaceUserInterests 用事务 DELETE+INSERT 全量替换';
COMMENT ON COLUMN user_interest.id         IS '标签记录 ID(主键)';
COMMENT ON COLUMN user_interest.user_id    IS '关联的 user_info.id(应用层维护,不写外键)';
COMMENT ON COLUMN user_interest.tab_key    IS '一级分类 key(运营字典里的分组名)';
COMMENT ON COLUMN user_interest.tag_key    IS '标签值 key(运营字典里的具体标签)';
COMMENT ON COLUMN user_interest.pic_key    IS '非空=图片标签的对象存储 object_key(业务侧限 9 个);空=文字标签(业务侧限 50 个)';
COMMENT ON COLUMN user_interest.created_at IS '记录创建时间,应用层 MybatisMetaObjectHandler 填值';
COMMENT ON COLUMN user_interest.updated_at IS '记录最后更新时间,应用层 MybatisMetaObjectHandler 填值';


-- =====================================================================
-- 6. geo_city  ---------- 美国城市字典(reference table)
-- =====================================================================
-- 数据源:SimpleMaps US Cities Basic (https://simplemaps.com/data/us-cities)
-- 许可:CC-BY 4.0,公网入口需 attribution 链接(由产品/前端承接)
-- 字段子集:city / state_id→state_code / state_name / lat / lng / population / id→source_id
-- 数据由 V4__seed_geo_city.sql 单独灌入(~28k 行),本表只负责结构。
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

CREATE UNIQUE INDEX IF NOT EXISTS uk_geo_city_city_state_country
    ON geo_city (city, state_code, country_code);
CREATE INDEX IF NOT EXISTS idx_geo_city_state_code
    ON geo_city (state_code);
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


-- =====================================================================
-- 7. biz_id_seq  ---------- 业务主键序号表(dating-common BizIdGenerator)
-- =====================================================================
-- 配合应用层 BizIdGenerator 拼装业务主键:每张业务表每天一行,seq 原子自增。
-- CREATE ... IF NOT EXISTS 幂等:多服务共用同一 PG 库时谁先建都行,各自独立
-- Flyway 历史表(user-service 走 flyway_history_user),重复执行无副作用。
CREATE TABLE IF NOT EXISTS biz_id_seq (
    table_name VARCHAR(64) NOT NULL,
    date_part  INTEGER     NOT NULL,
    seq        BIGINT      NOT NULL,
    PRIMARY KEY (table_name, date_part)
);

COMMENT ON TABLE  biz_id_seq            IS '业务主键序号表;每张业务表每天一行,seq 自增';
COMMENT ON COLUMN biz_id_seq.table_name IS '业务表名,如 user_info';
COMMENT ON COLUMN biz_id_seq.date_part  IS 'YYMMDD 整数(UTC),如 260527';
COMMENT ON COLUMN biz_id_seq.seq        IS '当天已分配序号(从 1 开始;默认 4 位,超 9999 自动扩位)';


-- =====================================================================
-- updated_at 由应用层维护
-- =====================================================================
-- CLAUDE.md 红线:"不写存储过程、不写触发器、不在 DB 层放业务逻辑"。
-- updated_at 由 com.dating.user.config.MybatisMetaObjectHandler 在 insert/update 时填值。
-- =====================================================================
