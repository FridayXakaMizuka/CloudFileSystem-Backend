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
                                Long expirationSeconds) {

        long expSeconds = (expirationSeconds != null && expirationSeconds > 0)
                ? expirationSeconds : defaultExpiration;

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expSeconds * 1000);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("nickname", nickname)
                .claim("userType", userType)
                .claim("homeDirectory", "user".equals(userType) ? "/users/" + userId : "/admin/")
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
}
