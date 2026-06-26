package com.dating.mobilegateway.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// /call/token 出参:1v1 通话的 LiveKit token,App 用 LiveKit SDK 直连 SFU。
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "LiveKit 通话 token 出参 —— App 用 LiveKit SDK 直连 SFU")
public class CallTokenVO {

    @Schema(description = "LiveKit 房间接入 token")
    private String token;
}
