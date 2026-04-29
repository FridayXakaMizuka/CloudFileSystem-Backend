// src/main/java/com/mizuka/cloudfilesystem/mapper/FileMetadataMapper.java
package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.FileMetadata;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FileMetadataMapper {

    @Insert("INSERT INTO file_metadata (file_id, file_name, file_path, file_size, md5, sha256, mime_type, owner_id, status, created_at, updated_at) " +
            "VALUES (#{fileId}, #{fileName}, #{filePath}, #{fileSize}, #{md5}, #{sha256}, #{mimeType}, #{ownerId}, #{status}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FileMetadata metadata);

    @Select("SELECT * FROM file_metadata WHERE md5 = #{md5} AND status = 1 LIMIT 1")
    FileMetadata findByMd5(String md5);

    @Select("SELECT * FROM file_metadata WHERE file_id = #{fileId} LIMIT 1")
    FileMetadata findByFileId(String fileId);

    @Update("UPDATE file_metadata SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);
}
