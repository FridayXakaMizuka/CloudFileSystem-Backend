package com.mizuka.cloudfilesystem.dto;

/**
 * 密码验证响应数据传输对象
 * 返回密码验证结果
 */
public class PasswordVerificationResponse {

    // 响应代码（200:成功, 400:请求错误, 401:密码错误, 500:服务器错误等）
    private int code;

    // 验证是否成功
    private boolean success;

    // 响应消息
    private String message;

    public PasswordVerificationResponse() {
    }

    public PasswordVerificationResponse(int code, boolean success, String message) {
        this.code = code;
        this.success = success;
        this.message = message;
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
}
