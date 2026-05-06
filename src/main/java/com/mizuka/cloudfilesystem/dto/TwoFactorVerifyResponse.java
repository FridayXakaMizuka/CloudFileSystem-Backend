package com.mizuka.cloudfilesystem.dto;

/**
 * 二次验证响应DTO
 */
public class TwoFactorVerifyResponse {
    
    private Integer code;
    private Boolean success;
    private String message;
    private String token;          // JWT令牌
    private Long userId;
    private String userType;
    private String homeDirectory;

    public TwoFactorVerifyResponse() {
    }

    public TwoFactorVerifyResponse(Integer code, Boolean success, String message, 
                                   String token, Long userId, String userType, String homeDirectory) {
        this.code = code;
        this.success = success;
        this.message = message;
        this.token = token;
        this.userId = userId;
        this.userType = userType;
        this.homeDirectory = homeDirectory;
    }

    // Getters and Setters
    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public String getHomeDirectory() {
        return homeDirectory;
    }

    public void setHomeDirectory(String homeDirectory) {
        this.homeDirectory = homeDirectory;
    }

    @Override
    public String toString() {
        return "TwoFactorVerifyResponse{" +
                "code=" + code +
                ", success=" + success +
                ", message='" + message + '\'' +
                ", userId=" + userId +
                ", userType='" + userType + '\'' +
                '}';
    }
}
