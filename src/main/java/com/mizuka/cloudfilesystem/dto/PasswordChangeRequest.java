package com.mizuka.cloudfilesystem.dto;

/**
 * 修改密码请求数据传输对象
 */
public class PasswordChangeRequest {

    // RSA会话ID
    private String sessionId;

    // RSA加密后的旧密码
    private String oldPassword;

    // RSA加密后的新密码
    private String newPassword;

    public PasswordChangeRequest() {
    }

    public PasswordChangeRequest(String sessionId, String oldPassword, String newPassword) {
        this.sessionId = sessionId;
        this.oldPassword = oldPassword;
        this.newPassword = newPassword;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOldPassword() {
        return oldPassword;
    }

    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
