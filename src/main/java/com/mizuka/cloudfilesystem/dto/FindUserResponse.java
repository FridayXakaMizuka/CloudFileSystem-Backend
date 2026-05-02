package com.mizuka.cloudfilesystem.dto;

/**
 * 查找用户响应数据传输对象
 * 返回用户信息和可用的验证方式
 */
public class FindUserResponse {

    private int code;                    // 响应代码
    private boolean success;             // 是否成功
    private String message;              // 响应消息
    private Long id;                     // 用户ID
    private String email;                // 用户邮箱（可能为空字符串）
    private String phone;                // 手机号（可能为空字符串）
    private Integer securityQuestion;    // 密保问题序号（可能为null）
    private String securityQuestionText; // 密保问题文本（可能为空字符串）

    public FindUserResponse() {
    }

    public FindUserResponse(int code, boolean success, String message, 
                           Long id, String email, String phone, 
                           Integer securityQuestion, String securityQuestionText) {
        this.code = code;
        this.success = success;
        this.message = message;
        this.id = id;
        this.email = email;
        this.phone = phone;
        this.securityQuestion = securityQuestion;
        this.securityQuestionText = securityQuestionText;
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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Integer getSecurityQuestion() {
        return securityQuestion;
    }

    public void setSecurityQuestion(Integer securityQuestion) {
        this.securityQuestion = securityQuestion;
    }

    public String getSecurityQuestionText() {
        return securityQuestionText;
    }

    public void setSecurityQuestionText(String securityQuestionText) {
        this.securityQuestionText = securityQuestionText;
    }
}
