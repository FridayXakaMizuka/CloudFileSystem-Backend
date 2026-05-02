package com.mizuka.cloudfilesystem.dto;

/**
 * 发送邮箱验证码请求数据传输对象
 */
public class EmailVerificationRequest {

    // 会话ID（前端生成）
    private String sessionId;

    // 邮箱地址
    private String email;

    public EmailVerificationRequest() {
    }

    public EmailVerificationRequest(String email) {
        this.email = email;
    }

    public EmailVerificationRequest(String sessionId, String email) {
        this.sessionId = sessionId;
        this.email = email;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
