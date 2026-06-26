package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

// 手机号 → 用户绑定;(phone_e164, app_name) 唯一。
// 无 deleted 列,故不加 @TableLogic;手机号一旦绑定永久持有。
@Data
@TableName("user_login_phone")
public class UserLoginPhone {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String phoneE164;
    private Short appName;
    private OffsetDateTime verifiedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
