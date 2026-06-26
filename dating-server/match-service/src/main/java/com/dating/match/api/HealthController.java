package com.dating.match.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅提供根路径方便浏览器肉眼验证服务起没起来。
 * 真正的健康检查走 Actuator: /actuator/health（含 liveness / readiness probe）。
 * 业务能力一律走 gRPC（MatchService），对外 REST 由 mobile-gateway 承接。
 */
@RestController
public class HealthController {

    @GetMapping("/")
    public String index() {
        return "match-service ok";
    }
}
