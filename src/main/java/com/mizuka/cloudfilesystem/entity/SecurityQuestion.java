package com.mizuka.cloudfilesystem.entity;

import java.time.LocalDateTime;

/**
 * 安全问题实体类
 * 对应数据库中的 security_questions 表
 */
public class SecurityQuestion {

    // 主键ID
    private Integer id;

    // 问题内容
    private String questionText;

    // 创建时间
    private LocalDateTime createdAt;

    public SecurityQuestion() {
    }

    public SecurityQuestion(Integer id, String questionText, LocalDateTime createdAt) {
        this.id = id;
        this.questionText = questionText;
        this.createdAt = createdAt;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
