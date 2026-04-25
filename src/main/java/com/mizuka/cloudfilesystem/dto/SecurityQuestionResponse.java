package com.mizuka.cloudfilesystem.dto;

import java.util.List;

/**
 * 安全问题响应数据传输对象
 * 用于返回安全问题列表给前端
 */
public class SecurityQuestionResponse {

    // 响应代码
    private int code;

    // 是否成功
    private boolean success;

    // 响应消息
    private String message;

    // 安全问题数量
    private int count;

    // 安全问题列表
    private List<SecurityQuestionItem> questions;

    /**
     * 安全问题项（只包含id和问题内容）
     */
    public static class SecurityQuestionItem {
        private Integer id;
        private String questionText;

        public SecurityQuestionItem() {
        }

        public SecurityQuestionItem(Integer id, String questionText) {
            this.id = id;
            this.questionText = questionText;
        }

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getQuestionText() {
            return questionText;
        }

        public void setQuestionText(String questionText) {
            this.questionText = questionText;
        }
    }

    public SecurityQuestionResponse() {
    }

    public SecurityQuestionResponse(int code, boolean success, String message, int count, List<SecurityQuestionItem> questions) {
        this.code = code;
        this.success = success;
        this.message = message;
        this.count = count;
        this.questions = questions;
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

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public List<SecurityQuestionItem> getQuestions() {
        return questions;
    }

    public void setQuestions(List<SecurityQuestionItem> questions) {
        this.questions = questions;
    }
}
