package com.mizuka.cloudfilesystem.dto;

/**
 * 修改昵称请求数据传输对象
 */
public class NicknameChangeRequest {

    // 新昵称
    private String nickname;

    public NicknameChangeRequest() {
    }

    public NicknameChangeRequest(String nickname) {
        this.nickname = nickname;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
}
