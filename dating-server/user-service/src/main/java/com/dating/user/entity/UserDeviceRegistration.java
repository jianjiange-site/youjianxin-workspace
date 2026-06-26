package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

// 设备 → 用户绑定(快速登录);软删后允许同 (device_id, platform, app_name) 重新绑定。
// 时间字段叫 registered_at;platform 与 mobile-gateway.auth_device.platform 对齐(1=iOS 2=Android 3=Web)。
@Data
@TableName("user_device_registration")
public class UserDeviceRegistration {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String deviceId;
    private Short platform;
    private Short appName;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime registeredAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
