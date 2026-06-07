package com.mizuka.cloudfilesystem.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mizuka.cloudfilesystem.dto.DeleteSession;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;

/**
 * 删除会话管理服务
 * 使用 Redis ZSet 存储和追踪异步删除任务的进度
 */
@Service
public class DeleteSessionService {
    
    private static final Logger log = LoggerFactory.getLogger(DeleteSessionService.class);
    
    private static final String DELETE_SESSION_KEY_PREFIX = "delete_sessions:";
    
    // Session 过期时间：24小时
    private static final long SESSION_EXPIRE_HOURS = 24;
    
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    
    public DeleteSessionService(
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("deleteRedisCommands") RedisAsyncCommands<String, String> deleteRedisCommands) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 创建删除会话并存入 Redis
     * 
     * @param session 删除会话信息
     */
    public void createSession(DeleteSession session) {
        try {
            String redisKey = DELETE_SESSION_KEY_PREFIX + session.getUserId();
            String member = objectMapper.writeValueAsString(session);
            long score = System.currentTimeMillis();
            
            stringRedisTemplate.opsForZSet().add(redisKey, member, score);
            
            log.info("[删除会话] 创建成功 - SessionId: {}, UserId: {}, NodeId: {}", 
                session.getSessionId(), session.getUserId(), session.getNodeId());
            
        } catch (JsonProcessingException e) {
            log.error("[删除会话] 序列化失败", e);
            throw new RuntimeException("创建删除会话失败", e);
        }
    }
    
    /**
     * 获取删除会话
     * 
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @return 删除会话，不存在则返回 null
     */
    public DeleteSession getSession(Long userId, String sessionId) {
        try {
            String redisKey = DELETE_SESSION_KEY_PREFIX + userId;
            Set<String> members = stringRedisTemplate.opsForZSet().range(redisKey, 0, -1);
            
            if (members == null || members.isEmpty()) {
                return null;
            }
            
            for (String member : members) {
                DeleteSession session = objectMapper.readValue(member, DeleteSession.class);
                if (session.getSessionId().equals(sessionId)) {
                    return session;
                }
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("[删除会话] 获取失败 - SessionId: {}", sessionId, e);
            return null;
        }
    }
    
    /**
     * 更新会话状态
     * 
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param status 新状态
     */
    public void updateSessionStatus(Long userId, String sessionId, String status) {
        updateSessionStatus(userId, sessionId, status, null, null, null);
    }
    
    /**
     * 更新会话状态和进度
     * 
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param status 新状态
     * @param processedNodes 已处理节点数
     * @param totalNodes 总节点数
     * @param errorMessage 错误消息
     */
    public void updateSessionStatus(Long userId, String sessionId, String status, 
                                     Integer processedNodes, Integer totalNodes, 
                                     String errorMessage) {
        updateSessionWithCursor(userId, sessionId, status, processedNodes, totalNodes, 
            errorMessage, null, null);
    }
    
    /**
     * 更新会话状态和游标位置（支持断点续传）
     * 
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param status 新状态
     * @param processedNodes 已处理节点数
     * @param totalNodes 总节点数
     * @param errorMessage 错误消息
     * @param currentParentId 当前处理的父文件夹ID
     * @param lastProcessedNodeId 最后处理的子节点ID
     */
    public void updateSessionWithCursor(Long userId, String sessionId, String status,
                                         Integer processedNodes, Integer totalNodes,
                                         String errorMessage, Long currentParentId,
                                         Long lastProcessedNodeId) {
        try {
            String redisKey = DELETE_SESSION_KEY_PREFIX + userId;
            
            // 获取所有会话
            Set<String> members = stringRedisTemplate.opsForZSet().range(redisKey, 0, -1);
            
            if (members == null || members.isEmpty()) {
                return;
            }
            
            for (String member : members) {
                DeleteSession session = objectMapper.readValue(member, DeleteSession.class);
                
                if (session.getSessionId().equals(sessionId)) {
                    // 更新字段
                    session.setStatus(status);
                    if (processedNodes != null) {
                        session.setProcessedNodes(processedNodes);
                    }
                    if (totalNodes != null) {
                        session.setTotalNodes(totalNodes);
                    }
                    if (errorMessage != null) {
                        session.setErrorMessage(errorMessage);
                    }
                    if (currentParentId != null) {
                        session.setCurrentParentId(currentParentId);
                    }
                    if (lastProcessedNodeId != null) {
                        session.setLastProcessedNodeId(lastProcessedNodeId);
                    }
                    
                    // 删除旧记录，添加新记录
                    stringRedisTemplate.opsForZSet().remove(redisKey, member);
                    String updatedMember = objectMapper.writeValueAsString(session);
                    long score = System.currentTimeMillis();
                    stringRedisTemplate.opsForZSet().add(redisKey, updatedMember, score);
                    
                    log.info("[删除会话] 状态更新 - SessionId: {}, Status: {}, Processed: {}/{}", 
                        sessionId, status, processedNodes, totalNodes);
                    
                    break;
                }
            }
            
        } catch (Exception e) {
            log.error("[删除会话] 更新状态失败 - SessionId: {}", sessionId, e);
        }
    }
    
    /**
     * 清理过期的删除会话（定时任务）
     * 每小时执行一次，清理24小时前的会话
     */
    @Scheduled(fixedRate = 3600000) // 1小时
    public void cleanupExpiredSessions() {
        try {
            long cutoffTime = System.currentTimeMillis() - (SESSION_EXPIRE_HOURS * 3600 * 1000);
            
            Set<String> keys = stringRedisTemplate.keys(DELETE_SESSION_KEY_PREFIX + "*");
            
            if (keys == null || keys.isEmpty()) {
                return;
            }
            
            int totalRemoved = 0;
            
            for (String key : keys) {
                Long removed = stringRedisTemplate.opsForZSet()
                    .removeRangeByScore(key, 0, cutoffTime);
                
                if (removed != null && removed > 0) {
                    totalRemoved += removed;
                    log.info("[删除会话] 清理过期会话 - Key: {}, Count: {}", key, removed);
                }
            }
            
            if (totalRemoved > 0) {
                log.info("[删除会话] 清理完成 - 总共清理: {} 个会话", totalRemoved);
            }
            
        } catch (Exception e) {
            log.error("[删除会话] 清理失败", e);
        }
    }
}
