package com.dating.mobilegateway.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 划卡 feed 卡片(给 App)。
 *
 * <p>photo_keys 是 object_key 列表;App 自拼 ${cdnBaseUrl}/${bucket}/${key}
 * (见 CLAUDE.md "VO/gRPC 出参一律 *_key" 红线)。
 *
 * <p>state / city 居住地展示:
 * <ul>
 *   <li>BH(真人):取自用户 onboarding 时选的 state_code / city</li>
 *   <li>DH(数字人):后端按"同 state 不同 city"动态算 —— state 跟 caller 一致、city 跟 caller 不同
 *       (同一 caller 看同一 DH 跨次稳定;不同 caller 看同一 DH 可能不同 city)</li>
 *   <li>caller 自己没填位置 → 这两字段为空,前端不渲染位置那行</li>
 * </ul>
 *
 * @param targetUserType 1=BH(真人) 2=DH(数字人);客户端按此区分展示
 * @param distanceKm     BH 才有(单位 km);DH = -1
 */
@Schema(description = "划卡 feed 卡片(BH 真人 + DH 数字人统一结构)")
public record MatchCardVO(
        @Schema(description = "目标用户 ID(雪花)")
        Long targetUserId,

        @Schema(description = "目标用户类型:1=BH(真人),2=DH(数字人,AI persona)", example = "1")
        Integer targetUserType,

        @Schema(description = "昵称")
        String nickname,

        @Schema(description = "年龄;0 表示未填写", example = "26")
        Integer age,

        @Schema(description = "头像 object_key 列表。App 自拼 ${cdnBaseUrl}/${bucket}/${key} 展示")
        List<String> photoKeys,

        @Schema(description = "个人简介")
        String bio,

        @Schema(description = "距离 caller 的公里数。BH 才有意义;DH 固定 -1 表示无距离概念",
                example = "5.2")
        Double distanceKm,

        @Schema(description = "美国州缩写 CA / NY ...。BH 取真值;DH 等于 caller 的 state;" +
                "caller 未填位置时为空,前端不渲染",
                example = "CA")
        String stateCode,

        @Schema(description = "城市名。BH 取真值;DH 取 caller 同 state 下的另一个城市(!= caller.city);" +
                "caller 未填位置时为空",
                example = "San Francisco")
        String city
) {}
