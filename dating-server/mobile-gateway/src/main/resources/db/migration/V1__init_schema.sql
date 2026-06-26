-- =====================================================================
-- mobile-gateway 鉴权域建表脚本 V1 (PostgreSQL 14+)
-- 与 docs/sql/mobile-gateway.sql 等价;权威源在 docs/,Flyway 用本副本。
--
-- 2 张表:
--   1. auth_device         — 设备指纹
--   2. auth_refresh_token  — refresh token 索引 (SHA-256 hash 后存)
-- 红线:不写存储过程/触发器/外键;updated_at 走 MybatisMetaObjectHandler。
-- =====================================================================

SET TIME ZONE 'Asia/Shanghai';

-- 1. auth_device -------------------------------------------------------
CREATE TABLE auth_device (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    device_id    VARCHAR(128) NOT NULL,
    platform     SMALLINT     NOT NULL,
    device_model VARCHAR(128),
    os_version   VARCHAR(64),
    app_version  VARCHAR(32),
    push_token   VARCHAR(256),
    last_ip      VARCHAR(64),
    last_seen_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_auth_device_user_device
    ON auth_device (user_id, device_id) WHERE NOT deleted;

CREATE INDEX idx_auth_device_last_seen_at
    ON auth_device (last_seen_at DESC) WHERE NOT deleted;

COMMENT ON TABLE  auth_device IS '设备指纹;(user_id, device_id) 唯一';
COMMENT ON COLUMN auth_device.id           IS '设备记录 ID(主键)';
COMMENT ON COLUMN auth_device.user_id      IS '关联 user_info.id(应用层维护,不写外键)';
COMMENT ON COLUMN auth_device.device_id    IS 'App 上报的设备唯一标识(iOS IDFV / Android SSAID 派生)';
COMMENT ON COLUMN auth_device.platform     IS '平台:1=iOS 2=Android 3=Web';
COMMENT ON COLUMN auth_device.device_model IS '设备型号文本';
COMMENT ON COLUMN auth_device.os_version   IS '操作系统版本号';
COMMENT ON COLUMN auth_device.app_version  IS 'App 版本号';
COMMENT ON COLUMN auth_device.push_token   IS '推送 token(APNs/FCM)';
COMMENT ON COLUMN auth_device.last_ip      IS '最近一次登录 IP';
COMMENT ON COLUMN auth_device.last_seen_at IS '最近一次活跃时间';
COMMENT ON COLUMN auth_device.created_at   IS '设备首次注册时间(MybatisMetaObjectHandler 填值)';
COMMENT ON COLUMN auth_device.updated_at   IS '设备记录最后更新时间(MybatisMetaObjectHandler 填值)';
COMMENT ON COLUMN auth_device.deleted      IS '软删标志(@TableLogic)';

-- 2. auth_refresh_token ------------------------------------------------
CREATE TABLE auth_refresh_token (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    device_id     VARCHAR(128) NOT NULL,
    token_hash    VARCHAR(128) NOT NULL,
    issued_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expired_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ,
    rotated_to_id BIGINT,
    revoked       BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_auth_refresh_token_hash      ON auth_refresh_token (token_hash);
CREATE INDEX        idx_auth_refresh_token_user_device ON auth_refresh_token (user_id, device_id);
CREATE INDEX        idx_auth_refresh_token_expired_at  ON auth_refresh_token (expired_at);

COMMENT ON TABLE  auth_refresh_token IS 'Refresh token 索引;DB 只存 hash';
COMMENT ON COLUMN auth_refresh_token.id            IS 'token 记录 ID;rotated_to_id 串联轮换链';
COMMENT ON COLUMN auth_refresh_token.user_id       IS '关联 user_info.id';
COMMENT ON COLUMN auth_refresh_token.device_id     IS '签发设备 ID;刷新时必须与请求来源设备一致';
COMMENT ON COLUMN auth_refresh_token.token_hash    IS 'refresh token SHA-256 hex(64 chars);明文绝不入库';
COMMENT ON COLUMN auth_refresh_token.issued_at     IS '签发时间;轮换时新 token 重置';
COMMENT ON COLUMN auth_refresh_token.expired_at    IS '过期时间(签发 + TTL,默认 7d)';
COMMENT ON COLUMN auth_refresh_token.used_at       IS '被消费换新 token 的时间;二次使用应告警';
COMMENT ON COLUMN auth_refresh_token.rotated_to_id IS '轮换出的下一个 token id';
COMMENT ON COLUMN auth_refresh_token.revoked       IS '是否主动撤销';
COMMENT ON COLUMN auth_refresh_token.revoked_at    IS '撤销时间(与 revoked=true 配对)';
COMMENT ON COLUMN auth_refresh_token.created_at    IS '记录创建时间(MybatisMetaObjectHandler 填值)';
COMMENT ON COLUMN auth_refresh_token.updated_at    IS '记录最后更新时间(MybatisMetaObjectHandler 填值)';
