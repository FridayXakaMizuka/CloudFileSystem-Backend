package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.dto.RecycleBinBrowseResponse;
import com.mizuka.cloudfilesystem.dto.RecycleBinItemDTO;
import com.mizuka.cloudfilesystem.mapper.RecycleBinTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 回收站服务
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class RecycleBinService {
    
    @Autowired
    private RecycleBinTaskMapper recycleBinTaskMapper;
    
    /**
     * 默认每页最大数量
     */
    private static final int DEFAULT_MAX_PAGE_SIZE = 20;
    
    /**
     * 绝对最大每页数量
     */
    private static final int ABSOLUTE_MAX_PAGE_SIZE = 100;
    
    /**
     * 浏览回收站
     * 
     * @param userId 用户ID
     * @param maxPageSize 每页数量
     * @param lastBatchId 游标锚点
     * @return 浏览响应
     */
    public RecycleBinBrowseResponse browseRecycleBin(Long userId, Integer maxPageSize, String lastBatchId) {
        // 1. 参数校验
        if (maxPageSize == null || maxPageSize <= 0) {
            maxPageSize = DEFAULT_MAX_PAGE_SIZE;
        }
        if (maxPageSize > ABSOLUTE_MAX_PAGE_SIZE) {
            maxPageSize = ABSOLUTE_MAX_PAGE_SIZE;
        }
        
        // 2. 查询回收站列表
        List<RecycleBinItemDTO> items = recycleBinTaskMapper.browseRecycleBin(
            userId, maxPageSize, lastBatchId
        );
        
        // 3. 计算分页信息
        String newLastBatchId = null;
        Boolean isEnd = true;
        
        if (!items.isEmpty()) {
            // 获取最后一项的 batchId
            newLastBatchId = items.get(items.size() - 1).getBatchId();
            
            // 检查是否还有更多数据
            isEnd = !hasMoreItems(userId, maxPageSize, newLastBatchId);
        }
        
        // 4. 构建响应
        RecycleBinBrowseResponse.PaginationInfo pagination = 
            new RecycleBinBrowseResponse.PaginationInfo(newLastBatchId, isEnd);
        
        return new RecycleBinBrowseResponse(items, pagination);
    }
    
    /**
     * 检查是否还有更多数据
     */
    private boolean hasMoreItems(Long userId, Integer maxPageSize, String lastBatchId) {
        Boolean result = recycleBinTaskMapper.hasMoreItems(userId, maxPageSize, lastBatchId);
        return result != null && result;
    }
}
