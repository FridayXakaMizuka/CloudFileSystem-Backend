package com.mizuka.cloudfilesystem.entity;

import java.time.LocalDateTime;

/**
 * 用户信任设备实体类
 * 存储客户端（Electron/Android/iOS）的永久信任设备信息
 */
public class UserTrustedDevice {
    
    private Long id;
    private Long userId;
    private String deviceUuid;
    private String deviceFingerprint;
    private String hardwareId;
    private String clientType;
    private String clientIdentifier;
    private String platform;
    private String deviceName;
    private String deviceModel;
    private Boolean isTrusted;
    private Integer trustLevel;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
    private String lastLoginLocation;
    private Integer loginCount;
    private LocalDateTime firstSeenAt;
    private LocalDateTime updatedAt;

    public UserTrustedDevice() {
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

    public String getHardwareId() {
        return hardwareId;
    }

    public void setHardwareId(String hardwareId) {
        this.hardwareId = hardwareId;
    }

    public String getClientType() {
        return clientType;
    }

    public void setClientType(String clientType) {
        this.clientType = clientType;
    }

    public String getClientIdentifier() {
        return clientIdentifier;
    }

    public void setClientIdentifier(String clientIdentifier) {
        this.clientIdentifier = clientIdentifier;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    public Boolean getIsTrusted() {
        return isTrusted;
    }

    public void setIsTrusted(Boolean isTrusted) {
        this.isTrusted = isTrusted;
    }

    public Integer getTrustLevel() {
        return trustLevel;
    }

    public void setTrustLevel(Integer trustLevel) {
        this.trustLevel = trustLevel;
    }

    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(LocalDateTime lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    public String getLastLoginIp() {
        return lastLoginIp;
    }

    public void setLastLoginIp(String lastLoginIp) {
        this.lastLoginIp = lastLoginIp;
    }

    public String getLastLoginLocation() {
        return lastLoginLocation;
    }

    public void setLastLoginLocation(String lastLoginLocation) {
        this.lastLoginLocation = lastLoginLocation;
    }

    public Integer getLoginCount() {
        return loginCount;
    }

    public void setLoginCount(Integer loginCount) {
        this.loginCount = loginCount;
    }

    public LocalDateTime getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(LocalDateTime firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "UserTrustedDevice{" +
                "id=" + id +
                ", userId=" + userId +
                ", deviceUuid='" + deviceUuid + '\'' +
                ", clientType='" + clientType + '\'' +
                ", platform='" + platform + '\'' +
                ", deviceName='" + deviceName + '\'' +
                ", isTrusted=" + isTrusted +
                ", trustLevel=" + trustLevel +
                ", loginCount=" + loginCount +
                '}';
    }
}
