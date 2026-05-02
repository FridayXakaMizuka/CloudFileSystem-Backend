package com.mizuka.cloudfilesystem.dto;

/**
 * 修改昵称响应数据传输对象
 */
public class NicknameChangeResponse {

    // 响应代码
    private int code;

    // 是否成功
    private boolean success;

    // 响应消息
    private String message;

    // 响应数据（预留，当前为null）
    private Object data;

    public NicknameChangeResponse() {
    }

    public NicknameChangeResponse(int code, boolean success, String message, Object data) {
        this.code = code;
        this.success = success;
        this.message = message;
        this.data = data;
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

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
