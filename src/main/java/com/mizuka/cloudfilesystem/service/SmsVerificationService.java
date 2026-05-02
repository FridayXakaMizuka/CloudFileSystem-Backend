package com.mizuka.cloudfilesystem.service;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import darabonba.core.client.ClientOverrideConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 短信验证码服务类
 * 使用阿里云短信服务发送验证码
 */
@Service
public class SmsVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(SmsVerificationService.class);

    @Autowired
    @Qualifier("rsaRedisTemplate")
    private RedisTemplate<String, Object> redisTemplate;

    // 阿里云配置
    @Value("${aliyun.sms.region}")
    private String region;

    @Value("${aliyun.sms.endpoint:dypnsapi.aliyuncs.com}")
    private String endpoint;

    @Value("${aliyun.sms.sign-name}")
    private String signName;

    @Value("${aliyun.sms.template-code}")
    private String templateCode;

    @Value("${aliyun.sms.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.sms.access-key-secret}")
    private String accessKeySecret;

    // Redis中存储短信验证码的key前缀
    private static final String SMS_CODE_PREFIX = "sms:code:";
    
    // Redis中存储发送时间的key前缀（用于频率控制）
    private static final String SMS_SEND_TIME_PREFIX = "sms:send_time:";

    // 验证码有效期（单位：分钟）
    private static final long CODE_EXPIRE_MINUTES = 5;
    
    // 发送间隔限制（单位：秒）
    private static final long SEND_INTERVAL_SECONDS = 60;

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
     * 验证手机号格式
     * @param phoneNumber 手机号
     * @return 是否有效
     */
    private boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }
        // 中国大陆手机号正则表达式
        String phoneRegex = "^1[3-9]\\d{9}$";
        return phoneNumber.matches(phoneRegex);
    }

    /**
     * 发送短信验证码
     * @param phoneNumber 手机号
     * @param sessionId 前端传来的会话ID
     * @return 是否发送成功
     */
    public boolean sendSmsVerificationCode(String phoneNumber, String sessionId) {
        try {
            // 1. 验证手机号格式
            if (!isValidPhoneNumber(phoneNumber)) {
                logger.warn("[发送短信验证码] 失败 - 手机号格式不正确: {}", phoneNumber);
                return false;
            }

            // 2. 检查发送频率
            String sendTimeKey = SMS_SEND_TIME_PREFIX + phoneNumber;
            Object lastSendTimeObj = redisTemplate.opsForValue().get(sendTimeKey);
            
            if (lastSendTimeObj != null) {
                long lastSendTime;
                if (lastSendTimeObj instanceof Long) {
                    lastSendTime = (Long) lastSendTimeObj;
                } else if (lastSendTimeObj instanceof Integer) {
                    lastSendTime = ((Integer) lastSendTimeObj).longValue();
                } else {
                    lastSendTime = Long.parseLong(lastSendTimeObj.toString());
                }
                
                long currentTime = System.currentTimeMillis();
                long timeDiff = (currentTime - lastSendTime) / 1000; // 转换为秒
                
                if (timeDiff < SEND_INTERVAL_SECONDS) {
                    long waitTime = SEND_INTERVAL_SECONDS - timeDiff;
                    logger.warn("[发送短信验证码] 频率限制 - Phone: {}, 距离上次发送仅{}秒，还需等待{}秒", 
                        phoneNumber, timeDiff, waitTime);
                    return false;
                }
            }

            // 3. 生成6位数字验证码
            String code = generateVerificationCode();
            logger.info("[发送短信验证码] 生成验证码 - Phone: {}, SessionId: {}, Code: {}", 
                phoneNumber, sessionId, code);

            // 4. 调用阿里云短信服务发送验证码
            boolean sendSuccess = sendSmsViaAliyun(phoneNumber, code);

            if (!sendSuccess) {
                logger.error("[发送短信验证码] 阿里云短信发送失败 - Phone: {}", phoneNumber);
                // 发送失败时，不存储验证码到Redis，也不记录发送时间
                return false;
            }

            // 5. 发送成功后，存储验证码到Redis（使用sessionId + phone作为key，5分钟过期）
            String redisKey = SMS_CODE_PREFIX + sessionId + ":" + phoneNumber;
            redisTemplate.opsForValue().set(redisKey, code, CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);
            logger.debug("[发送短信验证码] 验证码已存入Redis - Key: {}, TTL: {}分钟", redisKey, CODE_EXPIRE_MINUTES);
            
            // 6. 记录发送时间（用于频率控制）- 仅在发送成功后记录
            redisTemplate.opsForValue().set(sendTimeKey, System.currentTimeMillis(), SEND_INTERVAL_SECONDS, TimeUnit.SECONDS);
            logger.debug("[发送短信验证码] 发送时间已记录 - Phone: {}", phoneNumber);

            logger.info("[发送短信验证码] 发送成功 - Phone: {}, SessionId: {}", phoneNumber, sessionId);

            // 7. 返回成功
            return true;

        } catch (Exception e) {
            logger.error("[发送短信验证码] 异常 - Phone: {}, Error: {}", phoneNumber, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 通过阿里云发送短信
     * @param phoneNumber 手机号
     * @param code 验证码
     * @return 是否发送成功
     */
    private boolean sendSmsViaAliyun(String phoneNumber, String code) {
        try {
            // 使用静态凭证提供者，直接从配置中读取AccessKey
            StaticCredentialProvider provider = StaticCredentialProvider.create(
                Credential.builder()
                    .accessKeyId(accessKeyId)
                    .accessKeySecret(accessKeySecret)
                    .build()
            );

            // 创建异步客户端
            try (AsyncClient client = AsyncClient.builder()
                    .region(region)
                    .credentialsProvider(provider)
                    .overrideConfiguration(
                        ClientOverrideConfiguration.create()
                            .setEndpointOverride(endpoint)
                    )
                    .build()) {

                // 使用Gson构建JSON参数（适配模板：${code} 和 ${min}）
                Gson gson = new Gson();
                JsonObject templateParam = new JsonObject();
                templateParam.addProperty("code", code);
                templateParam.addProperty("min", CODE_EXPIRE_MINUTES); // 验证码有效期（分钟）
                String templateParamJson = gson.toJson(templateParam);
                
                logger.debug("[阿里云号码认证] 模板参数 - Phone: {}, TemplateParam: {}", phoneNumber, templateParamJson);

                // 构建请求（使用号码认证服务API）
                SendSmsVerifyCodeRequest request = SendSmsVerifyCodeRequest.builder()
                        .phoneNumber(phoneNumber)
                        .signName(signName)
                        .templateCode(templateCode)
                        .templateParam(templateParamJson)
                        .build();

                // 发送请求并等待结果
                CompletableFuture<SendSmsVerifyCodeResponse> response = client.sendSmsVerifyCode(request);
                SendSmsVerifyCodeResponse resp = response.get();

                // 检查响应
                if (resp != null && resp.getBody() != null && "OK".equals(resp.getBody().getCode())) {
                    logger.info("[阿里云号码认证] 发送成功 - Phone: {}, RequestId: {}", 
                        phoneNumber, resp.getBody().getRequestId());
                    return true;
                } else {
                    String respCode = resp != null && resp.getBody() != null ? resp.getBody().getCode() : "null";
                    String respMessage = resp != null && resp.getBody() != null ? resp.getBody().getMessage() : "null";
                    logger.error("[阿里云号码认证] 发送失败 - Phone: {}, Code: {}, Message: {}", 
                        phoneNumber, respCode, respMessage);
                    return false;
                }
            }

        } catch (Exception e) {
            logger.error("[阿里云短信] 异常 - Phone: {}, Error: {}", phoneNumber, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 验证短信验证码
     * @param sessionId 会话ID
     * @param phoneNumber 手机号
     * @param code 用户输入的验证码
     * @return 是否验证成功
     */
    public boolean verifyCode(String sessionId, String phoneNumber, String code) {
        try {
            // 1. 验证参数
            if (sessionId == null || sessionId.trim().isEmpty() || 
                phoneNumber == null || phoneNumber.trim().isEmpty() ||
                code == null || code.trim().isEmpty()) {
                logger.warn("[验证短信验证码] 失败 - 参数为空");
                return false;
            }

            // 2. 从Redis获取验证码（使用sessionId + phone作为key）
            String redisKey = SMS_CODE_PREFIX + sessionId + ":" + phoneNumber;
            String storedCode = (String) redisTemplate.opsForValue().get(redisKey);

            if (storedCode == null) {
                logger.warn("[验证短信验证码] 失败 - 验证码不存在或已过期 - SessionId: {}, Phone: {}", sessionId, phoneNumber);
                return false;
            }

            // 3. 比较验证码
            if (storedCode.equals(code.trim())) {
                logger.info("[验证短信验证码] 成功 - SessionId: {}, Phone: {}", sessionId, phoneNumber);
                // 验证成功后删除验证码（一次性使用）
                redisTemplate.delete(redisKey);
                return true;
            } else {
                logger.warn("[验证短信验证码] 失败 - 验证码错误 - SessionId: {}, Phone: {}", sessionId, phoneNumber);
                return false;
            }

        } catch (Exception e) {
            logger.error("[验证短信验证码] 异常 - SessionId: {}, Phone: {}, Error: {}", sessionId, phoneNumber, e.getMessage(), e);
            return false;
        }
    }
}
