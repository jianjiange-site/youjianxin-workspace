package com.dating.mobilegateway.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

// Avatar proto → VO;承载 object_key,App 侧自拼 ${cdnBaseUrl}/${bucket}/${key}(头像走 CDN public)。
// 注意:user-proto 0.2.x stub 字段仍叫 *_url,值已为 object_key —— 转换层负责屏蔽。
@Data
@Schema(description = "头像出参 —— 三档尺寸的 object_key + 原图像素。" +
        "App 自拼 ${cdnBaseUrl}/${bucket}/${key} 展示,服务端不签 URL")
public class AvatarVO {

    @Schema(description = "原图 object_key (未裁剪原稿)。下载体积最大,详情页 / 编辑预览用",
            example = "avatar/12345/202606/8c7a4b2e-orig.jpg")
    private String originalKey;

    @Schema(description = "小图 object_key (头像缩略,通常 ~96px)。列表 / 私聊 / 头像气泡用",
            example = "avatar/12345/202606/8c7a4b2e-min.jpg")
    private String minKey;

    @Schema(description = "中图 object_key (中等尺寸,通常 ~360px)。卡片 / 主推荐流用",
            example = "avatar/12345/202606/8c7a4b2e-mid.jpg")
    private String midKey;

    @Schema(description = "原图宽度 (像素)。App 可据此预算 placeholder 比例避免抖动", example = "1080")
    private Integer width;

    @Schema(description = "原图高度 (像素)。同 width", example = "1440")
    private Integer height;
}
