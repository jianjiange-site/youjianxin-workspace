package com.dating.mobilegateway.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// 全局兜底:HTTP 状态码恒为 200,业务结果走 Result.code (CLAUDE.md 通用编码规范)。
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<?> handleBiz(BizException ex) {
        log.warn("mobile-gateway biz error code={} msg={}", ex.getCode(), ex.getMessage());
        return Result.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public Result<?> handleInvalidArgument(Exception ex) {
        log.warn("mobile-gateway invalid argument: {}", ex.getMessage());
        return Result.fail(ErrorCodes.INVALID_ARGUMENT,
                ex.getMessage() == null ? "invalid argument" : ex.getMessage());
    }

    @ExceptionHandler(Throwable.class)
    public Result<?> handleUnexpected(Throwable ex) {
        log.error("mobile-gateway unexpected error", ex);
        return Result.fail(ErrorCodes.SYSTEM_ERROR, "system error");
    }
}
