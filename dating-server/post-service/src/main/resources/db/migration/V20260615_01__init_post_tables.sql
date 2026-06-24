-- =============================================================================
-- post-service 初始建表
--
-- 设计原则(详见 docs/post-service-design.md §5):
--   1. 业务主键 <entity>_id(雪花 ID),不对外暴露内部 id(红线 12)
--   2. 时间列一律 TIMESTAMPTZ,默认 NOW();禁用 TIMESTAMP(无时区)(红线 8)
--   3. deleted SMALLINT 配合 MyBatis-Plus @TableLogic
--   4. Mapper 一张表一个,禁多表 JOIN(红线 1);跨表数据在 service 层拼装
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 5.1 posts:帖子主表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS posts (
    id          BIGSERIAL PRIMARY KEY,
    post_id     BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    content     VARCHAR(1024) NOT NULL,
    status      SMALLINT NOT NULL DEFAULT 1,  -- 0=已删 / 1=正常 / 2=审核中
    deleted     SMALLINT NOT NULL DEFAULT 0,  -- 逻辑删除
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_posts_post_id ON posts (post_id);
-- 「我的动态」分页:user_id + created_at DESC 复合索引
CREATE INDEX IF NOT EXISTS idx_posts_user_created
    ON posts (user_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- 5.2 post_images:帖子图片(主键含 post_id,为未来分区铺路)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS post_images (
    post_id     BIGINT NOT NULL,
    sort_order  SMALLINT NOT NULL,            -- 0..8
    image_key   VARCHAR(128) NOT NULL,         -- 对象存储 key,不存 URL
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (post_id, sort_order)
);

-- -----------------------------------------------------------------------------
-- 5.3 post_stats:计数底座(只存"已刷盘"部分;实时值 = 底座 + Redis 增量)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS post_stats (
    post_id        BIGINT PRIMARY KEY,
    like_count     INTEGER NOT NULL DEFAULT 0,
    comment_count  INTEGER NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_post_stats_like_desc
    ON post_stats (like_count DESC);
CREATE INDEX IF NOT EXISTS idx_post_stats_comment_desc
    ON post_stats (comment_count DESC);

-- -----------------------------------------------------------------------------
-- 5.4 post_likes:点赞幂等记录(故意无自增 ID,联合主键 + UPDATE status 复用同一行)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS post_likes (
    user_id     BIGINT NOT NULL,
    post_id     BIGINT NOT NULL,
    status      SMALLINT NOT NULL DEFAULT 1,  -- 1=已赞 / 0=已取消
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, post_id)
);
-- 反查「谁赞了这帖」,partial index 省空间
CREATE INDEX IF NOT EXISTS idx_post_likes_post_status1
    ON post_likes (post_id) WHERE status = 1;

-- -----------------------------------------------------------------------------
-- 5.5 post_comments:评论(预留楼中楼字段,升级时数据库零改动)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS post_comments (
    id                BIGSERIAL PRIMARY KEY,
    comment_id        BIGINT NOT NULL,
    post_id           BIGINT NOT NULL,
    user_id           BIGINT NOT NULL,
    root_id           BIGINT NOT NULL DEFAULT 0,   -- 根评论 ID(自身是根则 0)
    parent_id         BIGINT NOT NULL DEFAULT 0,   -- 直接父评论
    reply_to_user_id  BIGINT NOT NULL DEFAULT 0,
    content           VARCHAR(512) NOT NULL,
    status            SMALLINT NOT NULL DEFAULT 1,
    deleted           SMALLINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_post_comments_comment_id
    ON post_comments (comment_id);
-- 一级评论分页(root_id = 0)
CREATE INDEX IF NOT EXISTS idx_post_comments_post_root_created
    ON post_comments (post_id, root_id, created_at DESC);
-- 楼中楼按时间正序展开(为升级铺路)
CREATE INDEX IF NOT EXISTS idx_post_comments_root_created
    ON post_comments (root_id, created_at ASC);

-- -----------------------------------------------------------------------------
-- ShedLock 互斥表(design §6.5):部署多实例时让 @SchedulerLock 生效。
-- 单实例开发期不依赖这张表,但建好不影响。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
