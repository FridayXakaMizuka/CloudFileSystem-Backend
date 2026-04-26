package com.mizuka.cloudfilesystem.dto;

/**
 * RSA密钥验证响应数据传输对象
 * 返回验证结果和公钥信息
 */
public class RSAValidationResponse {

    // 响应代码
    private int code;

    // 是否成功
    private boolean success;

    // 响应消息
    private String message;

    // RSA密钥是否有效
    private boolean valid;

    // RSA公钥（Base64编码）
    private String publicKey;

    // 会话ID
    private String sessionId;

    // 时间戳
    private long timestamp;

    public RSAValidationResponse() {
    }

    public RSAValidationResponse(int code, boolean success, String message, boolean valid,
                                 String publicKey, String sessionId, long timestamp) {
        this.code = code;
        this.success = success;
        this.message = message;
        this.valid = valid;
        this.publicKey = publicKey;
        this.sessionId = sessionId;
        this.timestamp = timestamp;
    }

    // Getters and Setters
    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
