-- biz_id_seq: 业务主键序号表
-- 每张业务表每天一行,seq 自增,配合应用层 BizIdGenerator 拼装最终业务 ID
-- 拷贝到调用服务的 Flyway 目录:src/main/resources/db/migration/V<n>__biz_id_seq.sql

CREATE TABLE IF NOT EXISTS biz_id_seq (
    table_name VARCHAR(64) NOT NULL,
    date_part  INTEGER     NOT NULL,
    seq        BIGINT      NOT NULL,
    PRIMARY KEY (table_name, date_part)
);

COMMENT ON TABLE  biz_id_seq            IS '业务主键序号表;每张业务表每天一行,seq 自增';
COMMENT ON COLUMN biz_id_seq.table_name IS '业务表名,如 user_info';
COMMENT ON COLUMN biz_id_seq.date_part  IS 'YYMMDD 整数,如 260527';
COMMENT ON COLUMN biz_id_seq.seq        IS '当天已分配序号(从 1 开始;默认 4 位,超 9999 自动扩位)';
