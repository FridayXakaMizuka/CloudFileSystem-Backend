package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.Administrator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdministratorMapper {

    @Select("SELECT * FROM administrators WHERE id = #{id}")
    Administrator findById(Integer id);

    @Update("UPDATE administrators SET last_login_at = NOW() WHERE id = #{id}")
    int updateLastLogin(Integer id);
}
