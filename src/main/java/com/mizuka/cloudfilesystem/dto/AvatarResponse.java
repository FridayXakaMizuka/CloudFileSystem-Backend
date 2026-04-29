package com.mizuka.cloudfilesystem.dto;

/**
 * 头像响应数据传输对象
 */
public class AvatarResponse {

    private int code;
    private boolean success;
    private String message;
    private String avatar; // 头像URL路径

    public AvatarResponse() {
    }

    public AvatarResponse(int code, boolean success, String message, String avatar) {
        this.code = code;
        this.success = success;
        this.message = message;
        this.avatar = avatar;
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

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
}
