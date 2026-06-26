package com.dating.mobilegateway.dto;

/**
 * OpenIM callback response format.
 *
 * <pre>
 * {"actionCode": 0, "errCode": 0, "errMsg": "", "errDlt": "", "nextCode": 0}
 * </pre>
 */
public class CallbackResponse {

    private int actionCode;
    private int errCode;
    private String errMsg;
    private String errDlt;
    private int nextCode;

    private CallbackResponse(int actionCode, int errCode, String errMsg, String errDlt, int nextCode) {
        this.actionCode = actionCode;
        this.errCode = errCode;
        this.errMsg = errMsg;
        this.errDlt = errDlt;
        this.nextCode = nextCode;
    }

    public static CallbackResponse ok() {
        return new CallbackResponse(0, 0, "", "", 0);
    }

    public static CallbackResponse fail(int errCode, String errMsg) {
        return new CallbackResponse(0, errCode, errMsg, "", 0);
    }

    /** 全字段工厂:业务自行设置 actionCode / nextCode 等,灵活处理回调返回值。 */
    public static CallbackResponse of(int actionCode, int errCode, String errMsg, String errDlt, int nextCode) {
        return new CallbackResponse(actionCode, errCode, errMsg, errDlt, nextCode);
    }

    /** 拒绝继续执行:actionCode=0(回调本身执行成功),nextCode=1 拦截该消息继续下发,errCode 取 5000-9999 自定义错误码。 */
    public static CallbackResponse reject(int errCode, String errMsg) {
        return new CallbackResponse(0, errCode, errMsg, "", 1);
    }

    public int getActionCode() { return actionCode; }
    public int getErrCode() { return errCode; }
    public String getErrMsg() { return errMsg; }
    public String getErrDlt() { return errDlt; }
    public int getNextCode() { return nextCode; }
}
