package com.mizuka.cloudfilesystem.dto;

/**
 * 登录请求数据传输对象
 * 接收前端发送的登录信息
 */
public class LoginRequest {

    // 会话ID，用于从Redis中获取对应的密钥对
    private String sessionId;

    // 用户ID
    private String userId;

    // RSA加密后的密码
    private String encryptedPassword;

    public LoginRequest() {
    }

    public LoginRequest(String sessionId, String userId, String encryptedPassword) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.encryptedPassword = encryptedPassword;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = encryptedPassword;
    }
}
