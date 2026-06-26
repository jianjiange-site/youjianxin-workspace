package com.dating.im;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/")
    public String hello() {
        return "hello from dating im-service";
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
