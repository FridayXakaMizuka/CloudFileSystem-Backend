package com.mizuka.cloudfilesystem.dto;

/**
 * 邮箱修改请求数据传输对象
 * 接收前端发送的邮箱修改信息
 */
public class EmailChangeRequest {

    // 会话ID，用于获取RSA密钥对和验证验证码
    private String sessionId;

    // RSA加密后的新邮箱地址
    private String encryptedEmail;

    // 邮箱验证码
    private String verificationCode;

    public EmailChangeRequest() {
    }

    public EmailChangeRequest(String sessionId, String encryptedEmail, String verificationCode) {
        this.sessionId = sessionId;
        this.encryptedEmail = encryptedEmail;
        this.verificationCode = verificationCode;
    }

    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getEncryptedEmail() {
        return encryptedEmail;
    }

    public void setEncryptedEmail(String encryptedEmail) {
        this.encryptedEmail = encryptedEmail;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }
}
