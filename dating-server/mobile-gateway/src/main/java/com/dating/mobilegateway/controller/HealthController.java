package com.dating.mobilegateway.controller;

import com.dating.mobilegateway.exception.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Health", description = "服务健康检查 (Actuator 之外的业务级 ping)")
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @Operation(summary = "ping", description = "返回 ok,验证 web 层 + Result 包装链路")
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.ok("pong");
    }
}
