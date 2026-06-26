package com.dating.user.service.dto;

// ResolveOrCreate 三种入口共享的内部出参;ProtoMapper.buildResolveOrCreateResponse 转 proto。
public record ResolveOrCreateResult(long userId, boolean pending, boolean newlyCreated) {
}
