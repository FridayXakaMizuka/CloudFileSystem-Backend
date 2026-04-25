package com.mizuka.cloudfilesystem.dto;

import java.util.List;

/**
 * 注册响应数据传输对象
 * 返回注册结果给前端
 */
public class RegisterResponse {

    private int code;                    // 响应代码
    private boolean success;             // 是否成功
    private String message;              // 响应消息
    private List<UserInfo> data;         // 用户信息列表

    /**
     * 用户信息内部类
     */
    public static class UserInfo {
        private Long id;                 // 用户ID
        private String nickname;         // 昵称

        public UserInfo() {
        }

        public UserInfo(Long id, String nickname) {
            this.id = id;
            this.nickname = nickname;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }
    }

    public RegisterResponse() {
    }

    public RegisterResponse(int code, boolean success, String message, List<UserInfo> data) {
        this.code = code;
        this.success = success;
        this.message = message;
        this.data = data;
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

    public List<UserInfo> getData() {
        return data;
    }

    public void setData(List<UserInfo> data) {
        this.data = data;
    }
}
