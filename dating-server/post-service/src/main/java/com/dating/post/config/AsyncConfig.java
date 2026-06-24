package com.dating.post.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * {@code @Async} 线程池配置(post-service-design §9.1)。
 * <p>
 * 主要用于 {@code PostFanoutService.fanoutToFollowers} 的写扩散:
 * 拿到关注者列表后批量 ZADD 各人 timeline,这一步在事务外异步做,
 * 失败不回滚 DB(5 分钟后 FeedScoreJob 重建池兜底)。
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "fanoutExecutor")
    public Executor fanoutExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(1024);
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("fanout-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(10);
        exec.initialize();
        return exec;
    }

    @Override
    public Executor getAsyncExecutor() {
        return fanoutExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Async task failed, method={}", method.getName(), ex);
    }
}
