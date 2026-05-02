package com.mizuka.cloudfilesystem.dto;

/**
 * 手机号修改请求数据传输对象
 * 接收前端发送的手机号修改信息
 */
public class PhoneChangeRequest {

    // 会话ID，用于获取RSA密钥对和验证验证码
    private String sessionId;

    // RSA加密后的新手机号
    private String encryptedPhone;

    // 手机验证码
    private String verificationCode;

    public PhoneChangeRequest() {
    }

    public PhoneChangeRequest(String sessionId, String encryptedPhone, String verificationCode) {
        this.sessionId = sessionId;
        this.encryptedPhone = encryptedPhone;
        this.verificationCode = verificationCode;
    }

    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getEncryptedPhone() {
        return encryptedPhone;
    }

    public void setEncryptedPhone(String encryptedPhone) {
        this.encryptedPhone = encryptedPhone;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }
}
