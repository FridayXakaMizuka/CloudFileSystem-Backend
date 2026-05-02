package com.mizuka.cloudfilesystem.dto;

/**
 * 用户个人信息响应数据传输对象
 */
public class UserProfileResponse {

    // 响应代码
    private int code;

    // 是否成功
    private boolean success;

    // 响应消息
    private String message;

    // 用户数据
    private UserData data;

    /**
     * 用户数据内部类
     */
    public static class UserData {
        private String avatar;           // 头像URL
        private String nickname;         // 昵称
        private String email;            // 邮箱（脱敏）
        private String phone;            // 手机号（脱敏）
        private Long storageUsed;        // 已使用空间（字节）
        private Long storageQuota;       // 空间配额（字节）

        public UserData() {
        }

        public UserData(String avatar, String nickname, String email, String phone,
                       Long storageUsed, Long storageQuota) {
            this.avatar = avatar;
            this.nickname = nickname;
            this.email = email;
            this.phone = phone;
            this.storageUsed = storageUsed;
            this.storageQuota = storageQuota;
        }

        public String getAvatar() {
            return avatar;
        }

        public void setAvatar(String avatar) {
            this.avatar = avatar;
        }

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

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public Long getStorageUsed() {
            return storageUsed;
        }

        public void setStorageUsed(Long storageUsed) {
            this.storageUsed = storageUsed;
        }

        public Long getStorageQuota() {
            return storageQuota;
        }

        public void setStorageQuota(Long storageQuota) {
            this.storageQuota = storageQuota;
        }
    }

    public UserProfileResponse() {
    }

    public UserProfileResponse(int code, boolean success, String message, UserData data) {
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

    public UserData getData() {
        return data;
    }

    public void setData(UserData data) {
        this.data = data;
    }
}
