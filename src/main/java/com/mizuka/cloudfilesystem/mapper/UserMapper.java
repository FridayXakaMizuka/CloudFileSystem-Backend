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

    @Select("SELECT * FROM users WHERE id = #{id}")
    User findById(Long id);

    @Update("UPDATE users SET last_login_at = NOW() WHERE id = #{id}")
    int updateLastLogin(Long id);

    /**
     * 更新用户头像
     * @param id 用户ID
     * @param avatar 头像路径（Base64编码或文件路径）
     * @return 影响的行数
     */
    @Update("UPDATE users SET avatar = #{avatar} WHERE id = #{id}")
    int updateAvatar(@Param("id") Long id, @Param("avatar") String avatar);

    /**
     * 更新用户密码
     * @param id 用户ID
     * @param password BCrypt加密后的密码
     * @return 影响的行数
     */
    @Update("UPDATE users SET password = #{password} WHERE id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /**
     * 更新用户昵称
     * @param id 用户ID
     * @param nickname 新昵称
     * @return 影响的行数
     */
    @Update("UPDATE users SET nickname = #{nickname} WHERE id = #{id}")
    int updateNickname(@Param("id") Long id, @Param("nickname") String nickname);

    /**
     * 更新用户邮箱
     * @param id 用户ID
     * @param email 新邮箱
     * @return 影响的行数
     */
    @Update("UPDATE users SET email = #{email} WHERE id = #{id}")
    int updateEmail(@Param("id") Long id, @Param("email") String email);

    /**
     * 更新用户手机号
     * @param id 用户ID
     * @param phone 新手机号
     * @return 影响的行数
     */
    @Update("UPDATE users SET phone = #{phone} WHERE id = #{id}")
    int updatePhone(@Param("id") Long id, @Param("phone") String phone);
}
