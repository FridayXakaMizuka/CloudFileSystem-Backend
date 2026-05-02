package com.mizuka.cloudfilesystem.dto;

/**
 * 重置密码请求数据传输对象
 * 用于重置密码最后一步：设置新密码
 */
public class ResetPasswordRequest {

    // 会话ID，第四步生成的新SessionId
    private String sessionId;

    // RSA加密的新密码
    private String encryptedNewPassword;

    public ResetPasswordRequest() {
    }

    public ResetPasswordRequest(String sessionId, String encryptedNewPassword) {
        this.sessionId = sessionId;
        this.encryptedNewPassword = encryptedNewPassword;
    }

    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getEncryptedNewPassword() {
        return encryptedNewPassword;
    }

    public void setEncryptedNewPassword(String encryptedNewPassword) {
        this.encryptedNewPassword = encryptedNewPassword;
    }
}
