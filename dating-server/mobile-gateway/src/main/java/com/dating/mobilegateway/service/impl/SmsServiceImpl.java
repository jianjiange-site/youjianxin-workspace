package com.dating.mobilegateway.service.impl;

import com.dating.mobilegateway.exception.BizException;
import com.dating.mobilegateway.exception.ErrorCodes;
import com.dating.mobilegateway.service.SmsService;
import com.dating.mobilegateway.vo.SendSmsCodeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

// 短信验证码占位实现:
//   - Redis key:gateway:auth:sms:code:<phoneE164> = 6 位数字,TTL 5min
//   - cooldown key:gateway:auth:sms:cooldown:<phoneE164> = "1",TTL 60s,期内拒发
//   - gateway.sms.enabled=false (默认):不调真渠道,把验证码打日志 + mockCode 回吐到 SendSmsCodeVO
//   - gateway.sms.enabled=true:目前仍未接真渠道,日志 WARN 但走相同 Redis 落码 (后续接腾讯 / 阿里)
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsServiceImpl implements SmsService {

    private static final String CODE_KEY_PREFIX = "gateway:auth:sms:code:";
    private static final String COOLDOWN_KEY_PREFIX = "gateway:auth:sms:cooldown:";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${gateway.sms.enabled:false}")
    private boolean smsEnabled;

    @Override
    public SendSmsCodeVO issue(String phoneE164) {
        if (phoneE164 == null || phoneE164.isBlank()) {
            throw new BizException(ErrorCodes.INVALID_ARGUMENT, "phone required");
        }
        String cooldownKey = COOLDOWN_KEY_PREFIX + phoneE164;
        Boolean firstTouch = redis.opsForValue().setIfAbsent(cooldownKey, "1", COOLDOWN_TTL);
        if (Boolean.FALSE.equals(firstTouch)) {
            Long left = redis.getExpire(cooldownKey);
            throw new BizException(ErrorCodes.TOO_MANY_REQUESTS,
                    "sms code cooldown, retry in " + (left == null ? COOLDOWN_TTL.toSeconds() : left) + "s");
        }
        String code = generateCode();
        redis.opsForValue().set(CODE_KEY_PREFIX + phoneE164, code, CODE_TTL);
        if (smsEnabled) {
            log.warn("sms.enabled=true but real upstream not wired yet, phone={}, code={}", phoneE164, code);
        } else {
            log.info("[mock-sms] phone={} code={} ttlSec={}", phoneE164, code, CODE_TTL.toSeconds());
        }
        return new SendSmsCodeVO((int) COOLDOWN_TTL.toSeconds(), smsEnabled ? null : code);
    }

    @Override
    public boolean verify(String phoneE164, String code) {
        if (phoneE164 == null || phoneE164.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        String key = CODE_KEY_PREFIX + phoneE164;
        String stored = redis.opsForValue().get(key);
        if (stored == null) {
            return false;
        }
        if (!stored.equals(code)) {
            return false;
        }
        redis.delete(key);
        return true;
    }

    private String generateCode() {
        int n = secureRandom.nextInt(1_000_000);
        return String.format("%06d", n);
    }
}
