package com.mizuka.cloudfilesystem.entity;

import java.time.LocalDateTime;

/**
 * 用户实体类
 * 对应数据库中的 users 表
 */
public class User {

    private Long id;                      // 用户ID（从10001开始）
    private String nickname;              // 用户昵称
    private String password;              // 密码（BCrypt加密）
    private String avatar;                // 头像图片路径（URL或Base64）
    private String email;                 // 邮箱地址
    private String phone;                 // 手机号码
    private Long storageQuota;            // 空间配额（字节），默认10GB
    private Long storageUsed;             // 已使用空间（字节）
    private Integer status;               // 账号状态：0-禁用，1-正常，2-锁定
    private Integer securityQuestionId;   // 安全问题编号
    private String securityAnswer;        // 安全问题答案
    private LocalDateTime registeredAt;   // 注册时间
    private LocalDateTime lastLoginAt;    // 最后登录时间

    public User() {
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Long getStorageQuota() {
        return storageQuota;
    }

    public void setStorageQuota(Long storageQuota) {
        this.storageQuota = storageQuota;
    }

    public Long getStorageUsed() {
        return storageUsed;
    }

    public void setStorageUsed(Long storageUsed) {
        this.storageUsed = storageUsed;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSecurityQuestionId() {
        return securityQuestionId;
    }

    public void setSecurityQuestionId(Integer securityQuestionId) {
        this.securityQuestionId = securityQuestionId;
    }

    public String getSecurityAnswer() {
        return securityAnswer;
    }

    public void setSecurityAnswer(String securityAnswer) {
        this.securityAnswer = securityAnswer;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(LocalDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}
