package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.dto.AvatarResponse;
import com.mizuka.cloudfilesystem.entity.Administrator;
import com.mizuka.cloudfilesystem.entity.User;
import com.mizuka.cloudfilesystem.mapper.AdministratorMapper;
import com.mizuka.cloudfilesystem.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 头像服务类
 * 处理用户头像的获取、设置和缓存管理
 * 使用独立的Redis实例（端口6380）存储头像数据
 */
@Service
public class AvatarService {

    private static final Logger logger = LoggerFactory.getLogger(AvatarService.class);

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AdministratorMapper administratorMapper;

    @Autowired
    @Qualifier("avatarRedisTemplate")
    private RedisTemplate<String, String> avatarRedisTemplate;

    @Autowired
    private com.mizuka.cloudfilesystem.util.JwtUtil jwtUtil;

    // Redis中存储头像的key前缀
    private static final String AVATAR_CACHE_PREFIX = "avatar:";

    /**
     * 获取用户头像
     * 优先从Redis缓存获取，缓存未命中则从数据库获取并缓存
     *
     * @param token JWT令牌
     * @return 头像响应对象
     */
    public AvatarResponse getAvatar(String token) {
        try {
            // 1. 验证JWT令牌并获取用户ID
            Claims claims = jwtUtil.parseToken(token);
            Long userId = claims.get("userId", Long.class);

            if (userId == null) {
                return new AvatarResponse(401, false, "无效的用户ID", null);
            }

            // 2. 构建Redis缓存key
            String cacheKey = AVATAR_CACHE_PREFIX + userId;

            // 3. 尝试从Redis缓存获取头像（使用6380端口）
            String cachedAvatar = avatarRedisTemplate.opsForValue().get(cacheKey);
            if (cachedAvatar != null) {
                logger.info("[获取头像] 缓存命中 - UserId: {}", userId);

                // 更新缓存有效期（与JWT令牌剩余有效期一致）
                long remainingSeconds = getRemainingTokenExpiration(token);
                if (remainingSeconds > 0) {
                    avatarRedisTemplate.expire(cacheKey, remainingSeconds, TimeUnit.SECONDS);
                    logger.debug("[获取头像] 更新缓存过期时间 - UserId: {}, 剩余时间: {}秒", userId, remainingSeconds);
                }

                return new AvatarResponse(200, true, "获取成功（来自缓存）", cachedAvatar);
            }

            // 4. 缓存未命中，从数据库获取
            logger.info("[获取头像] 缓存未命中，查询数据库 - UserId: {}", userId);

            String avatarUrl = null;

            // 判断是管理员还是普通用户
            if (userId >= 1 && userId <= 9999) {
                // 管理员
                Administrator admin = administratorMapper.findById(userId.intValue());
                if (admin != null && admin.getAvatar() != null && !admin.getAvatar().isEmpty()) {
                    avatarUrl = admin.getAvatar();
                }
            } else {
                // 普通用户
                User user = userMapper.findById(userId);
                if (user != null && user.getAvatar() != null && !user.getAvatar().isEmpty()) {
                    avatarUrl = user.getAvatar();
                }
            }

            // 5. 将头像URL存入Redis缓存（使用6380端口）
            if (avatarUrl != null) {
                long expirationSeconds = getRemainingTokenExpiration(token);
                if (expirationSeconds > 0) {
                    avatarRedisTemplate.opsForValue().set(cacheKey, avatarUrl, expirationSeconds, TimeUnit.SECONDS);
                    logger.info("[获取头像] 缓存已设置 - UserId: {}, 过期时间: {}秒", userId, expirationSeconds);
                } else {
                    // 如果无法获取剩余时间，使用默认7天
                    avatarRedisTemplate.opsForValue().set(cacheKey, avatarUrl, 7, TimeUnit.DAYS);
                    logger.info("[获取头像] 缓存已设置（默认7天） - UserId: {}", userId);
                }
            }

            if (avatarUrl != null) {
                return new AvatarResponse(200, true, "获取成功（来自数据库）", avatarUrl);
            } else {
                return new AvatarResponse(200, true, "用户没有设置头像", null);
            }

        } catch (Exception e) {
            logger.error("[获取头像] 失败 - {}", e.getMessage());
            e.printStackTrace();
            return new AvatarResponse(500, false, "获取头像失败：" + e.getMessage(), null);
        }
    }

    /**
     * 获取JWT令牌的剩余有效期（秒）
     *
     * @param token JWT令牌
     * @return 剩余秒数
     */
    private long getRemainingTokenExpiration(String token) {
        try {
            Claims claims = jwtUtil.parseToken(token);
            long expirationTime = claims.getExpiration().getTime();
            long currentTime = System.currentTimeMillis();
            long remainingMillis = expirationTime - currentTime;

            if (remainingMillis > 0) {
                return remainingMillis / 1000; // 转换为秒
            }
            return 0;
        } catch (Exception e) {
            logger.warn("[获取令牌有效期] 失败 - {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 设置用户头像
     * 
     * @param token JWT令牌
     * @param avatarUrl 头像URL或Base64编码
     * @return 操作结果消息
     */
    public String setAvatar(String token, String avatarUrl) {
        try {
            // 1. 验证JWT令牌并获取用户ID
            Long userId = jwtUtil.getUserIdFromToken(token);
            
            if (userId == null) {
                throw new IllegalArgumentException("无效的用户ID");
            }

            // 2. 判断是管理员还是普通用户，更新数据库
            int affectedRows;
            String userType;
            
            if (userId >= 1 && userId <= 9999) {
                // 管理员
                userType = "administrator";
                affectedRows = administratorMapper.updateAvatar(userId.intValue(), avatarUrl);
            } else {
                // 普通用户
                userType = "user";
                affectedRows = userMapper.updateAvatar(userId, avatarUrl);
            }

            if (affectedRows == 0) {
                throw new RuntimeException("更新头像失败，用户不存在");
            }

            // 3. 更新Redis中的头像缓存（如果存在）
            String cacheKey = AVATAR_CACHE_PREFIX + userId;
            String cachedAvatar = avatarRedisTemplate.opsForValue().get(cacheKey);
            
            if (cachedAvatar != null) {
                // 缓存存在，更新为新头像
                long remainingSeconds = getRemainingTokenExpiration(token);
                if (remainingSeconds > 0) {
                    avatarRedisTemplate.opsForValue().set(cacheKey, avatarUrl, remainingSeconds, TimeUnit.SECONDS);
                    logger.info("[设置头像] Redis缓存已更新 - UserId: {}, 剩余时间: {}秒", userId, remainingSeconds);
                } else {
                    // 如果无法获取剩余时间，使用默认7天
                    avatarRedisTemplate.opsForValue().set(cacheKey, avatarUrl, 7, TimeUnit.DAYS);
                    logger.info("[设置头像] Redis缓存已更新（默认7天） - UserId: {}", userId);
                }
            } else {
                // 缓存不存在，不预加载，等待下次获取时再缓存
                logger.debug("[设置头像] Redis缓存不存在，将在下次获取时缓存 - UserId: {}", userId);
            }
            
            logger.info("[设置头像] 成功 - UserId: {}, UserType: {}, AvatarUrl: {}", 
                userId, userType, avatarUrl);

            return "头像设置成功";

        } catch (Exception e) {
            logger.error("[设置头像] 失败 - {}", e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("设置头像失败：" + e.getMessage());
        }
    }
}
