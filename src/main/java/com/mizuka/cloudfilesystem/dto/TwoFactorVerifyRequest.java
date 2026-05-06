package com.mizuka.cloudfilesystem.dto;

/**
 * 二次验证请求DTO
 */
public class TwoFactorVerifyRequest {
    
    private String sessionId;
    private Long userId;
    private String verificationCode;  // 邮箱/手机验证码
    private String encryptedAnswer;   // 加密的密保答案
    private String verifyMethod;      // email/phone/security_answer

    public TwoFactorVerifyRequest() {
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

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }

    public String getEncryptedAnswer() {
        return encryptedAnswer;
    }

    public void setEncryptedAnswer(String encryptedAnswer) {
        this.encryptedAnswer = encryptedAnswer;
    }

    public String getVerifyMethod() {
        return verifyMethod;
    }

    public void setVerifyMethod(String verifyMethod) {
        this.verifyMethod = verifyMethod;
    }

    @Override
    public String toString() {
        return "TwoFactorVerifyRequest{" +
                "sessionId='" + sessionId + '\'' +
                ", userId=" + userId +
                ", verifyMethod='" + verifyMethod + '\'' +
                '}';
    }
}
