// src/main/java/com/mizuka/cloudfilesystem/mapper/UploadTaskMapper.java
package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.UploadTask;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UploadTaskMapper {

    @Insert("INSERT INTO upload_task (upload_id, file_id, user_id, file_name, file_size, total_chunks, uploaded_chunks, chunk_status, status, created_at, updated_at, expire_at) " +
            "VALUES (#{uploadId}, #{fileId}, #{userId}, #{fileName}, #{fileSize}, #{totalChunks}, #{uploadedChunks}, #{chunkStatus}, #{status}, NOW(), NOW(), #{expireAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UploadTask task);

    @Select("SELECT * FROM upload_task WHERE upload_id = #{uploadId} LIMIT 1")
    UploadTask findByUploadId(String uploadId);

    @Update("UPDATE upload_task SET uploaded_chunks = #{uploadedChunks}, chunk_status = #{chunkStatus}, updated_at = NOW() WHERE upload_id = #{uploadId}")
    int updateProgress(@Param("uploadId") String uploadId,
                       @Param("uploadedChunks") Integer uploadedChunks,
                       @Param("chunkStatus") String chunkStatus);

    @Update("UPDATE upload_task SET status = #{status}, updated_at = NOW() WHERE upload_id = #{uploadId}")
    int updateStatus(@Param("uploadId") String uploadId, @Param("status") Integer status);

    @Delete("DELETE FROM upload_task WHERE upload_id = #{uploadId}")
    int deleteByUploadId(String uploadId);

    @Delete("DELETE FROM upload_task WHERE expire_at < NOW()")
    int deleteExpiredTasks();
}
