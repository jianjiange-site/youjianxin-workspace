package com.dating.mobilegateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// BFF 专用执行器:虚拟线程池,Loom 友好,聚合调用阻塞 IO 时不抢 Tomcat 业务线程。
//
// 不复用 Tomcat 线程池的原因:
//   1. Tomcat worker 已被入站请求占用,在 worker 内 join CompletableFuture.allOf 会死锁风险
//   2. 虚拟线程零成本创建 —— 每次 CompletableFuture.supplyAsync(executor) 直接派一条
//   3. 不复用 ForkJoinPool.commonPool —— commonPool 默认线程数 = CPU-1,IO 密集会被打满
@Slf4j
@Configuration
public class AsyncConfig {

    // Spring 关闭时自动调 ExecutorService.shutdown(),releaseVTrPerTask 内部没有线程驻留,
    // 直接 shutdown 不影响在跑任务。
    @Bean(destroyMethod = "shutdown")
    public ExecutorService bffExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
