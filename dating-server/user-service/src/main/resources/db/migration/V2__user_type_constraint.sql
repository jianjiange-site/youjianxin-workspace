-- =====================================================================
-- V2__user_type_constraint.sql
-- user-service:user_type 字段语义化为 DH(1)/BH(2),强制非 0
--
-- 背景:
--   V1 注释里 user_type 是 sitin 历史字段(0=普通/1=运营内部),业务侧不暴露写;
--   2026-05-29 起重新定义为「数字人 / 真人」二分,并接入 im-service MessageRouter
--   做 BH/DH 分流。proto 同步落 UserType 枚举(USER_TYPE_DH=1 / USER_TYPE_BH=2)。
--
-- ALTER 顺序(对已上线的 dev/prod 库幂等):
--   1. 旧行回填 BH=2(真人;之前都是真人注册路径,无 DH 数据)
--   2. 加默认值 2,后续 INSERT 不传走默认
--   3. 加 NOT NULL
--   4. 加 CHECK (user_type IN (1, 2)),禁 0 / NULL / 其他值
--   5. 改 COMMENT 对齐新语义
-- =====================================================================

-- 1. 旧行回填 BH=2(NULL / 0 都视为 BH 真人)
UPDATE user_info SET user_type = 2 WHERE user_type IS NULL OR user_type = 0;

-- 2 + 3. 默认值 + 非空(必须先回填再加 NOT NULL,否则 PG 会拒)
ALTER TABLE user_info ALTER COLUMN user_type SET DEFAULT 2;
ALTER TABLE user_info ALTER COLUMN user_type SET NOT NULL;

-- 4. CHECK 约束(仅允许 1 / 2;0 / NULL 由 NOT NULL + CHECK 双保险拦截)
ALTER TABLE user_info
    ADD CONSTRAINT chk_user_info_user_type CHECK (user_type IN (1, 2));

-- 5. COMMENT 对齐新语义
COMMENT ON COLUMN user_info.user_type
    IS '用户类型:1=DH(数字人/AI persona) 2=BH(真人);CHECK 约束强制非 0,默认 2(真人);im-service MessageRouter 据此分流 BH→DH 走 ai-chat';
