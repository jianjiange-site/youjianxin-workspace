package com.dating.mobilegateway.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

// 设备指纹;(user_id, device_id) 唯一(部分唯一索引 WHERE NOT deleted)。
// 登录时 upsert,刷新 token 时 touchLastSeen。
@Data
@TableName("auth_device")
public class AuthDevice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String deviceId;
    private Short platform;
    private String deviceModel;
    private String osVersion;
    private String appVersion;
    private String pushToken;
    private String lastIp;
    private OffsetDateTime lastSeenAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
