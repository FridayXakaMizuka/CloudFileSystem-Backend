package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.dto.SecurityQuestionResponse;
import com.mizuka.cloudfilesystem.entity.SecurityQuestion;
import com.mizuka.cloudfilesystem.mapper.SecurityQuestionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 安全问题服务类
 * 处理安全问题相关的业务逻辑
 */
@Service
public class SecurityQuestionService {

    // 注入安全问题Mapper
    @Autowired
    private SecurityQuestionMapper securityQuestionMapper;

    /**
     * 获取所有安全问题
     * @return 包含安全问题列表的响应对象
     */
    public SecurityQuestionResponse getAllQuestions() {
        try {
            // 从数据库查询所有安全问题
            List<SecurityQuestion> questions = securityQuestionMapper.selectAllQuestions();

            // 将实体对象转换为响应DTO对象（只包含id和questionText）
            List<SecurityQuestionResponse.SecurityQuestionItem> items = questions.stream()
                    .map(q -> new SecurityQuestionResponse.SecurityQuestionItem(
                            q.getId(),
                            q.getQuestionText()
                    ))
                    .collect(Collectors.toList());

            // 创建并返回成功响应
            return new SecurityQuestionResponse(
                    200,
                    true,
                    "获取安全问题成功",
                    items.size(),
                    items
            );

        } catch (Exception e) {
            // 捕获异常，返回错误响应
            e.printStackTrace();
            return new SecurityQuestionResponse(
                    500,
                    false,
                    "获取安全问题失败：" + e.getMessage(),
                    0,
                    null
            );
        }
    }
}
