package com.mizuka.cloudfilesystem.dto;

/**
 * 修改密码响应数据传输对象
 */
public class PasswordChangeResponse {

    // 响应代码
    private int code;

    // 是否成功
    private boolean success;

    // 响应消息
    private String message;

    public PasswordChangeResponse() {
    }

    public PasswordChangeResponse(int code, boolean success, String message) {
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
