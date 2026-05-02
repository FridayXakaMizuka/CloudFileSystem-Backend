package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.Administrator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdministratorMapper {

    @Select("SELECT * FROM administrators WHERE id = #{id}")
    Administrator findById(Integer id);

    @Update("UPDATE administrators SET last_login_at = NOW() WHERE id = #{id}")
    int updateLastLogin(Integer id);

    /**
     * 更新管理员头像
     * @param id 管理员ID
     * @param avatar 头像路径（Base64编码或文件路径）
     * @return 影响的行数
     */
    @Update("UPDATE administrators SET avatar = #{avatar} WHERE id = #{id}")
    int updateAvatar(@Param("id") Integer id, @Param("avatar") String avatar);

    /**
     * 更新管理员密码
     * @param id 管理员ID
     * @param password BCrypt加密后的密码
     * @return 影响的行数
     */
    @Update("UPDATE administrators SET password = #{password} WHERE id = #{id}")
    int updatePassword(@Param("id") Integer id, @Param("password") String password);

    /**
     * 更新管理员昵称
     * @param id 管理员ID
     * @param nickname 新昵称
     * @return 影响的行数
     */
    @Update("UPDATE administrators SET nickname = #{nickname} WHERE id = #{id}")
    int updateNickname(@Param("id") Integer id, @Param("nickname") String nickname);
}
