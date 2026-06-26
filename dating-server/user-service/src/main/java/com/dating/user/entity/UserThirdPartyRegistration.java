package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

// 第三方账号 → 用户绑定;软删后允许同 (third_party_id, platform) 重新绑定(partial unique index)。
// 注意时间字段叫 registered_at 不叫 created_at,U4 写 insert 时 MetaObjectHandler 需补一行 registeredAt 填充。
@Data
@TableName("user_third_party_registration")
public class UserThirdPartyRegistration {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String thirdPartyLoginUserId;
    private Short platform;
    private String googleEmail;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime registeredAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
