package com.mizuka.cloudfilesystem.dto;

/**
 * 登录请求数据传输对象
 * 接收前端发送的登录信息
 */
public class LoginRequest {

    // 会话ID，用于从Redis中获取对应的密钥对
    private String sessionId;

    // 用户ID或邮箱
    private String userIdOrEmail;

    // RSA加密后的密码
    private String encryptedPassword;

    // 登录成功后，返回给前端的Token的过期时间（秒），默认7天（604800秒）
    private Long tokenExpiration;

    public LoginRequest() {
    }

    public LoginRequest(String sessionId, String userIdOrEmail, String encryptedPassword) {
        this.sessionId = sessionId;
        this.userIdOrEmail = userIdOrEmail;
        this.encryptedPassword = encryptedPassword;
    }

    public LoginRequest(String sessionId, String userIdOrEmail, String encryptedPassword, Long tokenExpiration) {
        this.sessionId = sessionId;
        this.userIdOrEmail = userIdOrEmail;
        this.encryptedPassword = encryptedPassword;
        this.tokenExpiration = tokenExpiration;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserIdOrEmail() {
        return userIdOrEmail;
    }

    public void setUserIdOrEmail(String userIdOrEmail) {
        this.userIdOrEmail = userIdOrEmail;
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = encryptedPassword;
    }

    public Long getTokenExpiration() {
        return tokenExpiration;
    }

    public void setTokenExpiration(Long tokenExpiration) {
        this.tokenExpiration = tokenExpiration;
    }

}
