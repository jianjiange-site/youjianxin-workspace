package com.dating.match.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 应用层自动填充 createdAt / updatedAt。
 * 不依赖 DB 触发器（CLAUDE.md 红线："不写触发器、不在 DB 层放业务逻辑"）。
 */
@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        this.strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
    }
}
