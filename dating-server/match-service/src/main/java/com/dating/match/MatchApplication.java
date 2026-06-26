package com.dating.match;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
@MapperScan("com.dating.match.mapper")
public class MatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchApplication.class, args);
    }
}
