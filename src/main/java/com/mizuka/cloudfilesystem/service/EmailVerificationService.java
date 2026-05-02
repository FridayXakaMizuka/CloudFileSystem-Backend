package com.mizuka.cloudfilesystem.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 邮箱验证码服务类
 * 处理邮箱验证码的生成、发送和验证
 */
@Service
public class EmailVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(EmailVerificationService.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    // 发件人邮箱（从配置中读取）
    @Value("${spring.mail.username}")
    private String fromEmail;

    // Redis中存储邮箱验证码的key前缀
    private static final String EMAIL_CODE_PREFIX = "email:code:";

    // 验证码有效期（单位：分钟）
    private static final long CODE_EXPIRE_MINUTES = 5;

    // 验证码长度
    private static final int CODE_LENGTH = 6;

    /**
     * 生成6位数字验证码
     * @return 验证码字符串
     */
    private String generateVerificationCode() {
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

    /**
     * 发送邮箱验证码
     * @param email 邮箱地址
     * @param sessionId 前端传来的会话ID
     * @return 是否发送成功
     */
    public boolean sendVerificationCode(String email, String sessionId) {
        try {
            // 1. 验证邮箱格式
            if (!isValidEmail(email)) {
                logger.warn("[发送验证码] 失败 - 邮箱格式不正确: {}", email);
                return false;
            }

            // 2. 生成6位数字验证码
            String code = generateVerificationCode();
            logger.info("[发送验证码] 生成验证码 - Email: {}, SessionId: {}, Code: {}", email, sessionId, code);

            // 3. 存储验证码到Redis（使用sessionId + email作为key，5分钟过期）
            String redisKey = EMAIL_CODE_PREFIX + sessionId + ":" + email;
            redisTemplate.opsForValue().set(redisKey, code, CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);
            logger.debug("[发送验证码] 验证码已存入Redis - Key: {}, TTL: {}分钟", redisKey, CODE_EXPIRE_MINUTES);

            // 4. 发送邮件
            if (mailSender != null) {
                sendEmail(email, code);
                logger.info("[发送验证码] 邮件发送成功 - Email: {}", email);
            } else {
                logger.warn("[发送验证码] 邮件服务未配置，仅存储验证码到Redis - Email: {}", email);
            }

            // 5. 返回成功
            return true;

        } catch (Exception e) {
            logger.error("[发送验证码] 异常 - Email: {}, Error: {}", email, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 验证邮箱验证码
     * @param sessionId 会话ID
     * @param email 邮箱地址
     * @param code 用户输入的验证码
     * @return 是否验证成功
     */
    public boolean verifyCode(String sessionId, String email, String code) {
        try {
            // 1. 验证参数
            if (sessionId == null || sessionId.trim().isEmpty() || 
                email == null || email.trim().isEmpty() ||
                code == null || code.trim().isEmpty()) {
                logger.warn("[验证验证码] 失败 - 参数为空");
                return false;
            }

            // 2. 从Redis获取验证码（使用sessionId + email作为key）
            String redisKey = EMAIL_CODE_PREFIX + sessionId + ":" + email;
            String storedCode = (String) redisTemplate.opsForValue().get(redisKey);

            if (storedCode == null) {
                logger.warn("[验证验证码] 失败 - 验证码不存在或已过期 - SessionId: {}, Email: {}", sessionId, email);
                return false;
            }

            // 3. 比较验证码
            if (storedCode.equals(code.trim())) {
                logger.info("[验证验证码] 成功 - SessionId: {}, Email: {}", sessionId, email);
                // 验证成功后删除验证码（一次性使用）
                redisTemplate.delete(redisKey);
                return true;
            } else {
                logger.warn("[验证验证码] 失败 - 验证码错误 - SessionId: {}, Email: {}", sessionId, email);
                return false;
            }

        } catch (Exception e) {
            logger.error("[验证验证码] 异常 - SessionId: {}, Email: {}, Error: {}", sessionId, email, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 验证邮箱格式
     * @param email 邮箱地址
     * @return 是否有效
     */
    private boolean isValidEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }
        // 简单的邮箱格式验证
        String emailRegex = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
        return email.matches(emailRegex);
    }

    /**
     * 发送邮件
     * @param to 收件人邮箱
     * @param code 验证码
     */
    private void sendEmail(String to, String code) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);  // 使用配置的邮箱作为发件人
            message.setTo(to);
            message.setSubject("CloudFileSystem - 邮箱验证码");
            message.setText("您好！\n\n您的验证码是：" + code + "\n\n验证码有效期为5分钟，请勿泄露给他人。\n\n如果这不是您本人的操作，请忽略此邮件。\n\nCloudFileSystem");
            
            mailSender.send(message);
            logger.info("[邮件发送] 成功 - From: {}, To: {}", fromEmail, to);
        } catch (Exception e) {
            logger.error("[邮件发送] 失败 - From: {}, To: {}, Error: {}", fromEmail, to, e.getMessage(), e);
            throw new RuntimeException("邮件发送失败: " + e.getMessage());
        }
    }
}
