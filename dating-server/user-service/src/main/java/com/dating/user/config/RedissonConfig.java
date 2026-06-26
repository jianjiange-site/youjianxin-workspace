package com.dating.user.config;

import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// redisson-spring-boot-starter 已基于 spring.data.redis.* 自动装配 RedissonClient;
// 不在此重复定义,以免和 autoconfig 冲突。本类只保留 customizer 钩子,后续可在此切 codec / 线程数。
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonAutoConfigurationCustomizer redissonCustomizer() {
        return cfg -> {
            // 默认 JsonJackson codec 即可;锁场景对 codec 不敏感。
        };
    }
}
