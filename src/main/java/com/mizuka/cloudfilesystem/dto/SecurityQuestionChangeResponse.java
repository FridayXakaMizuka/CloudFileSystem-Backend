package com.mizuka.cloudfilesystem.dto;

/**
 * 修改安全问题响应数据传输对象
 */
public class SecurityQuestionChangeResponse {

    // 是否成功
    private boolean success;

    // 响应消息
    private String message;

    public SecurityQuestionChangeResponse() {
    }

    public SecurityQuestionChangeResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
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
