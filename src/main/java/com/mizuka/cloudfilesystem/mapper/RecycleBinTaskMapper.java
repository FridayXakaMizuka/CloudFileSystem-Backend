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
     * 更新任务操作类型和状态（用于从删除切换到恢复）
     */
    @Update("UPDATE recycle_bin_tasks SET operation_type = #{operationType}, status = #{status}, " +
            "error_message = #{errorMessage}, processed_count = #{processedCount}, " +
            "total_count = #{totalCount}, completed_at = #{completedAt} WHERE batch_id = #{batchId}")
    void updateTaskOperationType(@Param("batchId") String batchId,
                                  @Param("operationType") Integer operationType,
                                  @Param("status") Integer status,
                                  @Param("errorMessage") String errorMessage,
                                  @Param("processedCount") Integer processedCount,
                                  @Param("totalCount") Integer totalCount,
                                  @Param("completedAt") LocalDateTime completedAt);
    
    /**
     * 更新进度
     */
    @Update("UPDATE recycle_bin_tasks SET processed_count = #{processedCount}, " +
            "total_count = #{totalCount} WHERE batch_id = #{batchId}")
    void updateProgress(@Param("batchId") String batchId,
                        @Param("processedCount") Integer processedCount,
                        @Param("totalCount") Integer totalCount);
    
    /**
     * 仅更新任务状态
     */
    @Update("UPDATE recycle_bin_tasks SET status = #{status} WHERE batch_id = #{batchId}")
    void updateTaskStatus(@Param("batchId") String batchId,
                          @Param("status") Integer status);
    
    /**
     * 仅更新操作类型
     */
    @Update("UPDATE recycle_bin_tasks SET operation_type = #{operationType} WHERE batch_id = #{batchId}")
    void updateOperationType(@Param("batchId") String batchId,
                             @Param("operationType") Integer operationType);
    
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
    
    /**
     * 根据根节点ID查询任务
     */
    @Select("SELECT * FROM recycle_bin_tasks WHERE root_node_id = #{rootNodeId} ORDER BY created_at DESC LIMIT 1")
    RecycleBinTask findByRootNodeId(@Param("rootNodeId") Long rootNodeId);
    
    /**
     * 查询所有需要重建的任务（用于Redis重建）
     * 
     * 需要重建的条件：
     * 1. operation_type=0（删除任务，在回收站中）- 所有状态都需要重建
     * 2. operation_type=1（恢复任务）&& (status=0 OR status=2)（进行中或失败）
     * 3. operation_type=2（彻底删除任务）&& (status=0 OR status=2)（进行中或失败）
     */
    @Select("SELECT * FROM recycle_bin_tasks WHERE " +
            "(operation_type = 0) OR " +
            "(operation_type IN (1, 2) AND status IN (0, 2)) " +
            "ORDER BY id ASC")
    List<RecycleBinTask> findAllIncompleteTasks();
    
    /**
     * 从游标位置后查询需要重建的任务（用于断点续传）
     * 
     * @param lastTaskId 上次处理的任务ID
     * @return 任务列表
     */
    @Select("SELECT * FROM recycle_bin_tasks WHERE " +
            "((operation_type = 0) OR (operation_type IN (1, 2) AND status IN (0, 2))) " +
            "AND id > #{lastTaskId} ORDER BY id ASC")
    List<RecycleBinTask> findIncompleteTasksAfterCursor(@Param("lastTaskId") Long lastTaskId);
    
}
