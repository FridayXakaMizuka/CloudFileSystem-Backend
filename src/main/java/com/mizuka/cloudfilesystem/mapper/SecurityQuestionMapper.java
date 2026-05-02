package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.SecurityQuestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 安全问题Mapper接口
 * 用于操作数据库中的 security_questions 表
 */
@Mapper
public interface SecurityQuestionMapper {

    /**
     * 查询所有安全问题
     * @return 安全问题列表
     */
    @Select("SELECT id, question_text, created_at FROM security_questions ORDER BY id ASC")
    List<SecurityQuestion> selectAllQuestions();

    /**
     * 根据ID查询安全问题
     * @param id 问题ID
     * @return 安全问题对象
     */
    @Select("SELECT id, question_text, created_at FROM security_questions WHERE id = #{id}")
    SecurityQuestion selectById(Integer id);
}
