package com.mizuka.cloudfilesystem.entity;

import java.time.LocalDateTime;

/**
 * 二次验证日志实体类
 * 记录所有二次验证操作，用于安全审计
 */
public class TwoFactorVerificationLog {
    
    private Long id;
    private Long userId;
    private String deviceUuid;
    private String deviceFingerprint;
    private String verifyMethod;
    private String verifyResult;
    private String failureReason;
    private String clientType;
    private String clientPlatform;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime createdAt;

    public TwoFactorVerificationLog() {
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getDeviceUuid() {
        return deviceUuid;
    }

    public void setDeviceUuid(String deviceUuid) {
        this.deviceUuid = deviceUuid;
    }

    public String getDeviceFingerprint() {
        return deviceFingerprint;
    }

    public void setDeviceFingerprint(String deviceFingerprint) {
        this.deviceFingerprint = deviceFingerprint;
    }

    public String getVerifyMethod() {
        return verifyMethod;
    }

    public void setVerifyMethod(String verifyMethod) {
        this.verifyMethod = verifyMethod;
    }

    public String getVerifyResult() {
        return verifyResult;
    }

    public void setVerifyResult(String verifyResult) {
        this.verifyResult = verifyResult;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getClientType() {
        return clientType;
    }

    public void setClientType(String clientType) {
        this.clientType = clientType;
    }

    public String getClientPlatform() {
        return clientPlatform;
    }

    public void setClientPlatform(String clientPlatform) {
        this.clientPlatform = clientPlatform;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "TwoFactorVerificationLog{" +
                "id=" + id +
                ", userId=" + userId +
                ", verifyMethod='" + verifyMethod + '\'' +
                ", verifyResult='" + verifyResult + '\'' +
                ", clientType='" + clientType + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
