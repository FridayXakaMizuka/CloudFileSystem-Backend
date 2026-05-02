package com.mizuka.cloudfilesystem.dto;

/**
 * 邮箱验证码响应数据传输对象
 */
public class EmailVerificationResponse {

    // 响应代码
    private int code;

    // 是否成功
    private boolean success;

    // 响应消息
    private String message;

    // 会话ID（用于后续验证）
    private String sessionId;

    public EmailVerificationResponse() {
    }

    public EmailVerificationResponse(int code, boolean success, String message) {
        this.code = code;
        this.success = success;
        this.message = message;
    }

    public EmailVerificationResponse(int code, boolean success, String message, String sessionId) {
        this.code = code;
        this.success = success;
        this.message = message;
        this.sessionId = sessionId;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
