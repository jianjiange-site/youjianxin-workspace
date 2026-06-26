package com.dating.mobilegateway.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

// Refresh token 索引;token_hash=SHA-256(明文) hex,明文绝不入库。
// 撤销:revoked=true;过期:expired_at < now();无软删 (无 deleted 列)。
@Data
@TableName("auth_refresh_token")
public class AuthRefreshToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String deviceId;
    private String tokenHash;
    private OffsetDateTime issuedAt;
    private OffsetDateTime expiredAt;
    private OffsetDateTime usedAt;
    private Long rotatedToId;
    private Boolean revoked;
    private OffsetDateTime revokedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
