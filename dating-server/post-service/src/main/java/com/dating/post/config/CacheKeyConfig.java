package com.dating.post.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存 key 前缀注入(student-dev-guide §6.2 + design §6.1)。
 * <p>
 * 学员阶段每个人按自己拼音前缀隔离,本仓库统一 {@code youjianxin}。
 * 业务代码取 {@link #getKeyPrefix()} 拼接 Redis key,搬环境时只改这一行配置。
 */
@Configuration
@ConfigurationProperties(prefix = "app.cache")
public class CacheKeyConfig {

    /** 例 {@code youjianxin}。可通过环境变量 {@code REDIS_KEY_PREFIX} 覆盖。 */
    private String keyPrefix;

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
