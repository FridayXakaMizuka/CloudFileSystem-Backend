package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.User;
import org.apache.ibatis.annotations.*;

/**
 * 用户Mapper接口
 * 用于操作数据库中的 users 表
 */
@Mapper
public interface UserMapper {

    /**
     * 插入新用户
     * @param user 用户对象
     * @return 影响的行数
     */
    @Insert("INSERT INTO users (nickname, password, email, phone, storage_quota, storage_used, status, security_question_id, security_answer) " +
            "VALUES (#{nickname}, #{password}, #{email}, #{phone}, #{storageQuota}, #{storageUsed}, #{status}, #{securityQuestionId}, #{securityAnswer})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUser(User user);

    /**
     * 根据邮箱查询用户
     * @param email 邮箱地址
     * @return 用户对象
     */
    @Select("SELECT * FROM users WHERE email = #{email}")
    User findByEmail(String email);

    /**
     * 根据手机号查询用户
     * @param phone 手机号
     * @return 用户对象
     */
    @Select("SELECT * FROM users WHERE phone = #{phone}")
    User findByPhone(String phone);

    /**
     * 根据昵称查询用户
     * @param nickname 昵称
     * @return 用户对象
     */
    @Select("SELECT * FROM users WHERE nickname = #{nickname}")
    User findByNickname(String nickname);
}
