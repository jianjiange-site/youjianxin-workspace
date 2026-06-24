package com.dating.post.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * Redisson 客户端配置。
 * <p>
 * 用途(design §6.1):
 * <ul>
 *   <li>分布式锁 {@code <prefix>:lock:post:*}</li>
 *   <li>已读去重 BloomFilter {@code <prefix>:user:read:bloom:{user_id}}(误判率 1%,容量 5000)</li>
 * </ul>
 * 与 Spring Data Redis 共用同一个连接配置(host/port/password 从同样的 yml 段读)。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setPassword(password == null || password.isEmpty() ? null : password);
        return Redisson.create(config);
    }
}
