package com.mizuka.cloudfilesystem.dto;

/**
 * 密保问题答案验证请求数据传输对象
 * 用于重置密码时验证密保问题答案
 */
public class ResetPasswordSecurityVerifyRequest {

    // 会话ID，第三步生成的新SessionId
    private String sessionId;

    // 用户ID（从第一步获取）
    private Long userId;

    // RSA加密的密保答案
    private String encryptedSecurityAnswer;

    public ResetPasswordSecurityVerifyRequest() {
    }

    public ResetPasswordSecurityVerifyRequest(String sessionId, Long userId, String encryptedSecurityAnswer) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.encryptedSecurityAnswer = encryptedSecurityAnswer;
    }

    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getEncryptedSecurityAnswer() {
        return encryptedSecurityAnswer;
    }

    public void setEncryptedSecurityAnswer(String encryptedSecurityAnswer) {
        this.encryptedSecurityAnswer = encryptedSecurityAnswer;
    }
}
