package com.dating.post.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.ZoneOffset;

/**
 * ShedLock 配置(design §6.5)。
 * <p>
 * 用 JDBC Provider 把锁状态存到 PG 的 {@code shedlock} 表,部署多实例时让
 * @{@link net.javacrumbs.shedlock.spring.annotation.SchedulerLock} 生效:
 * 同名 Job 同一时刻全集群只有一个实例跑。
 * <p>
 * 学员阶段单实例开发期也能跑(SELECT/INSERT 开销可忽略)。
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTimeZone(ZoneOffset.UTC)
                        .usingDbTime()
                        .build()
        );
    }
}
