package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.dto.RecycleBinItemDTO;
import com.mizuka.cloudfilesystem.entity.RecycleBinTask;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回收站任务 Mapper
 */
@Mapper
public interface RecycleBinTaskMapper {
    
    /**
     * 插入任务记录
     */
    @Insert("INSERT INTO recycle_bin_tasks (batch_id, user_id, root_node_id, node_type, operation_type, " +
            "total_count, processed_count, status, error_message, created_at, completed_at) " +
            "VALUES (#{batchId}, #{userId}, #{rootNodeId}, #{nodeType}, #{operationType}, " +
            "#{totalCount}, #{processedCount}, #{status}, #{errorMessage}, #{createdAt}, #{completedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    Long insert(RecycleBinTask task);
    
    /**
     * 根据 batchId 查询任务
     */
    @Select("SELECT * FROM recycle_bin_tasks WHERE batch_id = #{batchId}")
    RecycleBinTask findByBatchId(@Param("batchId") String batchId);
    
    /**
     * 更新任务状态
     */
    @Update("UPDATE recycle_bin_tasks SET status = #{status}, completed_at = #{completedAt}, " +
            "error_message = #{errorMessage}, processed_count = #{processedCount}, " +
            "total_count = #{totalCount} WHERE batch_id = #{batchId}")
    void updateTask(@Param("batchId") String batchId,
                    @Param("status") Integer status,
                    @Param("completedAt") LocalDateTime completedAt,
                    @Param("errorMessage") String errorMessage,
                    @Param("processedCount") Integer processedCount,
                    @Param("totalCount") Integer totalCount);
    
    /**
     * 更新进度
     */
    @Update("UPDATE recycle_bin_tasks SET processed_count = #{processedCount}, " +
            "total_count = #{totalCount} WHERE batch_id = #{batchId}")
    void updateProgress(@Param("batchId") String batchId,
                        @Param("processedCount") Integer processedCount,
                        @Param("totalCount") Integer totalCount);
    
    /**
     * 查询用户的删除任务列表（用于浏览回收站）
     */
    List<RecycleBinItemDTO> browseRecycleBin(@Param("userId") Long userId,
                                             @Param("maxPageSize") Integer maxPageSize,
                                             @Param("lastBatchId") String lastBatchId);
    
    /**
     * 检查是否还有更多数据
     */
    Boolean hasMoreItems(@Param("userId") Long userId,
                         @Param("maxPageSize") Integer maxPageSize,
                         @Param("lastBatchId") String lastBatchId);
    
    /**
     * 查询用户正在进行的恢复任务列表
     * operation_type=1 表示恢复操作，status=0 表示进行中
     */
    @Select("SELECT * FROM recycle_bin_tasks WHERE user_id = #{userId} AND operation_type = 1 AND status = 0 ORDER BY created_at ASC")
    List<RecycleBinTask> findInProgressRestoreTasks(@Param("userId") Long userId);
    
}
