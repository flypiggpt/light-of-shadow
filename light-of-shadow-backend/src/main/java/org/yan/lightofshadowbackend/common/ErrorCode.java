package org.yan.lightofshadowbackend.common;

public enum ErrorCode {
    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止"),
    NOT_FOUND(404, "未找到"),
    TOO_MANY_REQUESTS(429, "请求过于频繁"),
    CODE_INVALID(4011, "验证码无效"),
    SESSION_NOT_FOUND(4002, "会话不存在"),
    SESSION_ENDED(4003, "会话已结束"),
    INTERNAL_SERVER_ERROR(500, "服务器错误");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}

