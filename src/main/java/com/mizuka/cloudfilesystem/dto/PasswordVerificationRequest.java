package com.mizuka.cloudfilesystem.dto;

/**
 * 密码验证请求数据传输对象
 * 用于验证用户原密码是否正确
 */
public class PasswordVerificationRequest {

    // 会话ID，用于从Redis中获取对应的密钥对
    private String sessionId;

    // RSA加密后的密码
    private String encryptedPassword;

    public PasswordVerificationRequest() {
    }

    public PasswordVerificationRequest(String sessionId, String encryptedPassword) {
        this.sessionId = sessionId;
        this.encryptedPassword = encryptedPassword;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = encryptedPassword;
    }
}
