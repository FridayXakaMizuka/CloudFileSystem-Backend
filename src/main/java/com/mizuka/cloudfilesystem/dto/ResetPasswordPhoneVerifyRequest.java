package com.mizuka.cloudfilesystem.dto;

/**
 * 手机验证码验证请求数据传输对象
 * 用于重置密码时验证手机验证码
 */
public class ResetPasswordPhoneVerifyRequest {

    // 会话ID，第三步生成的新SessionId
    private String sessionId;

    // 用户手机号（明文）
    private String phone;

    // 6位验证码（明文）
    private String verificationCode;

    public ResetPasswordPhoneVerifyRequest() {
    }

    public ResetPasswordPhoneVerifyRequest(String sessionId, String phone, String verificationCode) {
        this.sessionId = sessionId;
        this.phone = phone;
        this.verificationCode = verificationCode;
    }

    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }
}
