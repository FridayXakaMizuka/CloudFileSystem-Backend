package com.mizuka.cloudfilesystem.dto;

/**
 * 查找用户请求数据传输对象
 * 用于重置密码时查找用户信息
 */
public class FindUserRequest {

    // 会话ID，用于获取RSA密钥对
    private String sessionId;

    // RSA加密的用户ID或邮箱地址
    private String encryptedUserIdOrEmail;

    public FindUserRequest() {
    }

    public FindUserRequest(String sessionId, String encryptedUserIdOrEmail) {
        this.sessionId = sessionId;
        this.encryptedUserIdOrEmail = encryptedUserIdOrEmail;
    }

    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getEncryptedUserIdOrEmail() {
        return encryptedUserIdOrEmail;
    }

    public void setEncryptedUserIdOrEmail(String encryptedUserIdOrEmail) {
        this.encryptedUserIdOrEmail = encryptedUserIdOrEmail;
    }
}
