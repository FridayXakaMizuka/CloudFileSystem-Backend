package com.mizuka.cloudfilesystem.dto;

/**
 * 注册请求数据传输对象
 * 接收前端发送的注册信息
 */
public class RegisterRequest {

    // 会话ID，用于从Redis中获取RSA密钥对
    private String sessionId;

    // 注册数据数组（通常只有一个元素）
    private RegisterData[] data;

    /**
     * 注册数据内部类
     */
    public static class RegisterData {
        private String nickname;      // 昵称
        private String email;         // 邮箱
        private String emailVfCode;   // 邮箱验证码
        private String phone;         // 手机号（非必填）
        private String phoneVfCode;   // 手机验证码
        private String encryptedPassword;  // RSA加密后的密码
        private Integer securityQuestion;  // 安全问题ID
        private String securityAnswer;     // 安全问题答案

        public RegisterData() {
        }

        // Getters and Setters
        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getEmailVfCode() {
            return emailVfCode;
        }

        public void setEmailVfCode(String emailVfCode) {
            this.emailVfCode = emailVfCode;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getPhoneVfCode() {
            return phoneVfCode;
        }

        public void setPhoneVfCode(String phoneVfCode) {
            this.phoneVfCode = phoneVfCode;
        }

        public String getEncryptedPassword() {
            return encryptedPassword;
        }

        public void setEncryptedPassword(String encryptedPassword) {
            this.encryptedPassword = encryptedPassword;
        }

        public Integer getSecurityQuestion() {
            return securityQuestion;
        }

        public void setSecurityQuestion(Integer securityQuestion) {
            this.securityQuestion = securityQuestion;
        }

        public String getSecurityAnswer() {
            return securityAnswer;
        }

        public void setSecurityAnswer(String securityAnswer) {
            this.securityAnswer = securityAnswer;
        }
    }

    public RegisterRequest() {
    }

    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public RegisterData[] getData() {
        return data;
    }

    public void setData(RegisterData[] data) {
        this.data = data;
    }
}
