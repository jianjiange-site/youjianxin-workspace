package com.dating.im.model;

/**
 * Outcome of handling an inbound IM callback; the gRPC layer maps it to proto
 * {@code OnRawCallbackResponse}.
 *
 * <p>provider-中立:不携带任何引擎专属码(如 OpenIM nextCode/errCode)。引擎专属翻译由各
 * provider 的 gateway 入口完成。
 *
 * @param success im-service 是否正确处理了这次回调(基础设施层面)
 * @param code    业务决策码:{@code 0} = allow,非 0 = reject 原因码;rewrite 后续扩展
 * @param message 决策文案 / 拒绝原因
 */
public record CallbackResult(boolean success, int code, String message) {

    public static CallbackResult ok() {
        return new CallbackResult(true, 0, "ok");
    }

    public static CallbackResult unsupported(String provider) {
        return new CallbackResult(false, 0, "unsupported provider: " + provider);
    }

    public static CallbackResult error(String message) {
        return new CallbackResult(false, 0, message);
    }

    /** 处理成功但业务拒绝:{@code code} 为非 0 业务原因码,由 gateway 翻译成引擎拦截应答。 */
    public static CallbackResult reject(int code, String message) {
        return new CallbackResult(true, code, message);
    }
}
