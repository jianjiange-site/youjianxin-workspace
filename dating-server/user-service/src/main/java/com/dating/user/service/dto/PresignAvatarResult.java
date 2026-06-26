package com.dating.user.service.dto;

// PresignAvatarUpload 出参:URL + object_key + expires (ms epoch)。
public record PresignAvatarResult(String presignedUrl, String objectKey, long expiresAtMs) {}
