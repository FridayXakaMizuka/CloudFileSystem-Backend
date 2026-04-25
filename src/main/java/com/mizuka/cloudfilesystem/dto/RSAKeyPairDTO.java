package com.mizuka.cloudfilesystem.dto;

import java.io.Serializable;

/**
 * RSA密钥对数据传输对象
 * 用于在Redis中存储公钥和私钥
 */
public class RSAKeyPairDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 公钥（Base64编码）
    private String publicKey;

    // 私钥（Base64编码）
    private String privateKey;

    // 创建时间戳
    private long timestamp;

    public RSAKeyPairDTO() {
    }

    public RSAKeyPairDTO(String publicKey, String privateKey, long timestamp) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.timestamp = timestamp;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
