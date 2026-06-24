package com.dating.post;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * post-service 入口(post-service-design §4)。
 * <ul>
 *   <li>{@link EnableScheduling} 启用 @Scheduled(LikeFlushJob / CommentFlushJob / FeedScoreJob)</li>
 *   <li>{@link EnableAsync} 启用 @Async(PostFanoutService.fanoutToFollowers)</li>
 *   <li>{@link MapperScan} 把 mapper 接口注入</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@MapperScan("com.dating.post.mapper")
public class PostApplication {

    public static void main(String[] args) {
        // 与容器 TZ=UTC 双保险(红线 5)
        System.setProperty("user.timezone", "UTC");
        SpringApplication.run(PostApplication.class, args);
    }
}
