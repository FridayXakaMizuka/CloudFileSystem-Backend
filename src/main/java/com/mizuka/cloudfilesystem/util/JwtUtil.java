package com.mizuka.cloudfilesystem.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtUtil {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${jwt.secret:CloudFileSystemDefaultSecretKeyForJWT2026MinimumLength64CharactersRequired}")
    private String secret;

    @Value("${jwt.default-expiration:604800}")
    private long defaultExpiration;

    private SecretKey getSigningKey() {
        byte[] keyBytes;
        if (secret.length() < 64) {
            keyBytes = Base64.getEncoder().encode(secret.getBytes(StandardCharsets.UTF_8));
        } else {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(Long userId, String nickname, String userType,
                                LocalDateTime registeredAt, Long expirationSeconds) {

        long expSeconds = (expirationSeconds != null && expirationSeconds > 0)
                ? expirationSeconds : defaultExpiration;

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expSeconds * 1000);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("userType", userType)
                .claim("registeredAt", registeredAt != null ? 
                    registeredAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null)
                .claim("homeDirectory", "user".equals(userType) ? "/users/" + userId : "/admin/")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 生成包含设备指纹的JWT令牌
     * @param userId 用户ID
     * @param nickname 昵称
     * @param userType 用户类型
     * @param registeredAt 注册时间
     * @param expirationSeconds 过期时间（秒）
     * @param deviceFingerprint 设备指纹
     * @return JWT令牌
     */
    public String generateTokenWithDeviceFingerprint(Long userId, String nickname, String userType,
                                                     LocalDateTime registeredAt, Long expirationSeconds,
                                                     String deviceFingerprint) {

        long expSeconds = (expirationSeconds != null && expirationSeconds > 0)
                ? expirationSeconds : defaultExpiration;

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expSeconds * 1000);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("userType", userType)
                .claim("registeredAt", registeredAt != null ? 
                    registeredAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null)
                .claim("homeDirectory", "user".equals(userType) ? "/users/" + userId : "/admin/")
                .claim("deviceFingerprint", deviceFingerprint)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            logger.error("[JWT解析] 失败 - {}", e.getMessage());
            throw new IllegalArgumentException("无效的JWT令牌");
        }
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("userId", Long.class);
    }

    /**
     * 从JWT令牌中获取设备指纹
     * @param token JWT令牌
     * @return 设备指纹，如果不存在则返回null
     */
    public String getDeviceFingerprintFromToken(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.get("deviceFingerprint", String.class);
        } catch (Exception e) {
            logger.warn("[JWT解析] 获取设备指纹失败 - {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从resetToken中获取设备指纹
     * @param token resetToken
     * @return 设备指纹，如果不存在则返回null
     */
    public String getDeviceFingerprintFromResetToken(String token) {
        try {
            Claims claims = validateResetToken(token);
            return claims.get("deviceFingerprint", String.class);
        } catch (Exception e) {
            logger.warn("[resetToken解析] 获取设备指纹失败 - {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成重置密码临时令牌（resetToken）
     * @param userId 用户ID
     * @param verifiedBy 验证方式（email/phone/security）
     * @param email 邮箱（可选）
     * @param phone 手机号（可选）
     * @param deviceFingerprint 设备指纹
     * @param expirationSeconds 有效期（秒），默认600秒（10分钟）
     * @return JWT令牌
     */
    public String generateResetToken(Long userId, String verifiedBy, String email, String phone, 
                                     String deviceFingerprint, Long expirationSeconds) {
        long expSeconds = (expirationSeconds != null && expirationSeconds > 0) ? expirationSeconds : 600;

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expSeconds * 1000);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("verifiedBy", verifiedBy)
                .claim("email", email)
                .claim("phone", phone)
                .claim("deviceFingerprint", deviceFingerprint)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 验证并解析resetToken
     * @param token JWT令牌
     * @return Claims对象
     * @throws IllegalArgumentException 如果令牌无效或过期
     */
    public Claims validateResetToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("令牌不能为空");
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // 检查是否包含必要的字段
            if (claims.get("userId") == null) {
                throw new IllegalArgumentException("无效的令牌格式");
            }

            return claims;
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            logger.warn("[resetToken验证] 令牌已过期");
            throw new IllegalArgumentException("验证令牌已过期");
        } catch (Exception e) {
            logger.error("[resetToken验证] 失败 - {}", e.getMessage());
            throw new IllegalArgumentException("验证令牌无效");
        }
    }
}
