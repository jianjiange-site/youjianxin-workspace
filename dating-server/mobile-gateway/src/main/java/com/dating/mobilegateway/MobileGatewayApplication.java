package com.dating.mobilegateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.dating.mobilegateway.mapper")
public class MobileGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MobileGatewayApplication.class, args);
    }
}
