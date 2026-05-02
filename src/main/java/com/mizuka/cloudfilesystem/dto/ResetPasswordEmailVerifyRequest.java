package com.mizuka.cloudfilesystem.dto;

/**
 * 邮箱验证码验证请求数据传输对象
 * 用于重置密码时验证邮箱验证码
 */
public class ResetPasswordEmailVerifyRequest {

    // 会话ID，第三步生成的新SessionId
    private String sessionId;

    // 用户邮箱（明文）
    private String email;

    // 6位验证码（明文）
    private String verificationCode;

    public ResetPasswordEmailVerifyRequest() {
    }

    public ResetPasswordEmailVerifyRequest(String sessionId, String email, String verificationCode) {
        this.sessionId = sessionId;
        this.email = email;
        this.verificationCode = verificationCode;
    }

    // Getters and Setters
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

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }
}
