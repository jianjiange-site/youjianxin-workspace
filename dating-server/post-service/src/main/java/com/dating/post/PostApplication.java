package com.dating.post;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * post-service 入口(post-service-design §4)。
 * <ul>
 *   <li>{@link EnableScheduling} 启用 @Scheduled(LikeFlushJob / CommentFlushJob / FeedScoreJob)</li>
 *   <li>{@link MapperScan} 把 mapper 接口注入</li>
 * </ul>
 * 写扩散走 RocketMQ {@code mq/producer/PostFanoutProducer + mq/consumer/PostFanoutConsumer},
 * 不再需要 {@code @EnableAsync}(详见 post-service-design §10.2.2)。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.dating.post.mapper")
public class PostApplication {

    public static void main(String[] args) {
        // 与容器 TZ=UTC 双保险(红线 5)
        System.setProperty("user.timezone", "UTC");
        SpringApplication.run(PostApplication.class, args);
    }
}
