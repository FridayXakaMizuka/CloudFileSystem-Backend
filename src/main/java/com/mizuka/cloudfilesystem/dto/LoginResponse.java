package com.mizuka.cloudfilesystem.dto;

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
}
