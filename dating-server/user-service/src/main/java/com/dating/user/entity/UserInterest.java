package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

// 用户兴趣标签(1:N);ReplaceUserInterests 用事务 DELETE+INSERT 全量替换。
// 无 deleted 列,故不加 @TableLogic;pic_key 非空=图片标签,空=文字标签。
@Data
@TableName("user_interest")
public class UserInterest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String tabKey;
    private String tagKey;
    private String picKey;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
