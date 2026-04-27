package com.mizuka.cloudfilesystem.dto;

import java.time.LocalDateTime;

/**
 * 登录响应数据传输对象
 * 返回登录结果给前端
 */
public class LoginResponse {

    // 响应代码（200:成功, 400:请求错误, 500:服务器错误等）
    private int code;

    // 登录是否成功
    private boolean success;

    // 响应消息
    private String message;

    // 用户ID（登录成功时返回）
    private String userId;

    // 登录令牌（登录成功时返回）
    private String token;

    // 昵称（登录成功时返回）
    private String nickname;

    // 用户类型（登录成功时返回）
    private String userType;

    // 头像（登录成功时返回）
    private String avatar;

    // 主目录（登录成功时返回）
    private String homeDirectory;

    // 注册时间（登录成功时返回）
    private LocalDateTime registeredAt;

    // 过期时间（登录成功时返回）
    private LocalDateTime expiresAt;

    public LoginResponse() {
    }

    public LoginResponse(int code, boolean success, String message) {
        this.code = code;
        this.success = success;
        this.message = message;
    }

    public LoginResponse(int code, boolean success, String message, String userId) {
        this.code = code;
        this.success = success;
        this.message = message;
        this.userId = userId;
    }

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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getHomeDirectory() {
        return homeDirectory;
    }

    public void setHomeDirectory(String homeDirectory) {
        this.homeDirectory = homeDirectory;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(LocalDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
