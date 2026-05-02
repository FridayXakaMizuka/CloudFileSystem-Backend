package com.mizuka.cloudfilesystem.dto;

/**
 * 短信验证码请求数据传输对象
 */
public class SmsVerificationRequest {

    // 会话ID（前端生成）
    private String sessionId;

    // 手机号
    private String phoneNumber;

    public SmsVerificationRequest() {
    }

    public SmsVerificationRequest(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public SmsVerificationRequest(String sessionId, String phoneNumber) {
        this.sessionId = sessionId;
        this.phoneNumber = phoneNumber;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }
}
