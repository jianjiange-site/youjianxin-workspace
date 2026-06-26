package com.dating.payment.service;

import com.dating.youjianxin.proto.payment.BaseResponse;

/**
 * 通用响应构造工具。
 */
final class Responses {

    private Responses() {}

    static BaseResponse notImplemented() {
        return BaseResponse.newBuilder()
                .setCode(1000)
                .setMessage("暂未实现")
                .build();
    }

    static BaseResponse ok() {
        return BaseResponse.newBuilder()
                .setCode(0)
                .setMessage("OK")
                .build();
    }
}
