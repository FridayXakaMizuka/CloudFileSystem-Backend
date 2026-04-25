package com.mizuka.cloudfilesystem.dto;

public class RSAPublicKeyResponse {
    private String publicKey;
    private String sessionId;
    private long timestamp;
    
    public RSAPublicKeyResponse() {
    }
    
    public RSAPublicKeyResponse(String publicKey, String sessionId, long timestamp) {
        this.publicKey = publicKey;
        this.sessionId = sessionId;
        this.timestamp = timestamp;
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
