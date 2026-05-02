package com.mizuka.cloudfilesystem.dto;

/**
 * 手机号修改响应数据传输对象
 * 返回手机号修改结果给前端
 */
public class PhoneChangeResponse {

    private int code;                    // 响应代码
    private boolean success;             // 是否成功
    private String message;              // 响应消息

    public PhoneChangeResponse() {
    }

    public PhoneChangeResponse(int code, boolean success, String message) {
        this.code = code;
        this.success = success;
        this.message = message;
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
}
