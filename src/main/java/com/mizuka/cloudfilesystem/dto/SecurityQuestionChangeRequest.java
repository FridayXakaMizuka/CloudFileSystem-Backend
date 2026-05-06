package com.mizuka.cloudfilesystem.dto;

/**
 * 修改安全问题请求数据传输对象
 */
public class SecurityQuestionChangeRequest {

    // 会话ID
    private String sessionId;

    // RSA加密后的旧答案
    private String encryptedOldAnswer;

    // 新的安全问题ID
    private Integer newSecurityQuestionId;

    // RSA加密后的新答案
    private String encryptedNewAnswer;

    public SecurityQuestionChangeRequest() {
    }

    public SecurityQuestionChangeRequest(String sessionId, String encryptedOldAnswer, 
                                        Integer newSecurityQuestionId, String encryptedNewAnswer) {
        this.sessionId = sessionId;
        this.encryptedOldAnswer = encryptedOldAnswer;
        this.newSecurityQuestionId = newSecurityQuestionId;
        this.encryptedNewAnswer = encryptedNewAnswer;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getEncryptedOldAnswer() {
        return encryptedOldAnswer;
    }

    public void setEncryptedOldAnswer(String encryptedOldAnswer) {
        this.encryptedOldAnswer = encryptedOldAnswer;
    }

    public Integer getNewSecurityQuestionId() {
        return newSecurityQuestionId;
    }

    public void setNewSecurityQuestionId(Integer newSecurityQuestionId) {
        this.newSecurityQuestionId = newSecurityQuestionId;
    }

    public String getEncryptedNewAnswer() {
        return encryptedNewAnswer;
    }

    public void setEncryptedNewAnswer(String encryptedNewAnswer) {
        this.encryptedNewAnswer = encryptedNewAnswer;
    }
}
