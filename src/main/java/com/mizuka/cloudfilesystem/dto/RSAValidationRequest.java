package com.mizuka.cloudfilesystem.dto;

/**
 * RSA密钥验证请求数据传输对象
 * 用于前端验证RSA密钥对是否有效
 */
public class RSAValidationRequest {

    // 会话ID
    private String sessionId;

    // RSA公钥（Base64编码）
    private String publicKey;

    public RSAValidationRequest() {
    }

    public RSAValidationRequest(String sessionId, String publicKey) {
        this.sessionId = sessionId;
        this.publicKey = publicKey;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }
}
