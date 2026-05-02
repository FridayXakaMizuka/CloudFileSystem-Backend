package com.mizuka.cloudfilesystem.dto;

/**
 * 重置密码验证响应数据传输对象
 * 返回验证结果和resetToken
 */
public class ResetPasswordVerifyResponse {

    private int code;           // 响应代码
    private boolean success;    // 是否成功
    private String message;     // 响应消息
    private String resetToken;  // JWT格式的临时令牌（仅成功时返回）

    public ResetPasswordVerifyResponse() {
    }

    public ResetPasswordVerifyResponse(int code, boolean success, String message, String resetToken) {
        this.code = code;
        this.success = success;
        this.message = message;
        this.resetToken = resetToken;
    }

    // Getters and Setters
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

    public String getResetToken() {
        return resetToken;
    }

    public void setResetToken(String resetToken) {
        this.resetToken = resetToken;
    }
}
