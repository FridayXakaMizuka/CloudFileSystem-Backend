package com.mizuka.cloudfilesystem.util;

import javax.crypto.Cipher;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * RSA密钥管理工具类
 * 提供密钥对生成、加解密等功能
 */
public class RSAKeyManager {

    // RSA算法名称（用于密钥生成）
    private static final String RSA_ALGORITHM = "RSA";
    
    // RSA转换字符串（用于加密/解密，明确指定填充模式）
    private static final String RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding";

    // 密钥长度（2048位）
    private static final int KEY_SIZE = 2048;

    /**
     * 生成RSA密钥对
     * @return KeyPair对象，包含公钥和私钥
     * @throws NoSuchAlgorithmException 如果RSA算法不可用
     */
    public static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        keyPairGenerator.initialize(KEY_SIZE);
        return keyPairGenerator.generateKeyPair();
    }

    /**
     * 将公钥转换为Base64编码字符串
     * @param keyPair 密钥对对象
     * @return Base64编码的公钥字符串
     */
    public static String getPublicKeyBase64(KeyPair keyPair) {
        byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
        return Base64.getEncoder().encodeToString(publicKeyBytes);
    }

    /**
     * 将私钥转换为Base64编码字符串
     * @param keyPair 密钥对对象
     * @return Base64编码的私钥字符串
     */
    public static String getPrivateKeyBase64(KeyPair keyPair) {
        byte[] privateKeyBytes = keyPair.getPrivate().getEncoded();
        return Base64.getEncoder().encodeToString(privateKeyBytes);
    }

    /**
     * 使用私钥解密数据
     * @param encryptedData Base64编码的加密数据
     * @param privateKeyBase64 Base64编码的私钥
     * @return 解密后的原始字符串
     * @throws Exception 解密失败时抛出异常
     */
    public static String decryptWithPrivateKey(String encryptedData, String privateKeyBase64) throws Exception {
        try {
            // 解码Base64格式的私钥
            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyBase64);

            // 创建私钥规范
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);

            // 获取KeyFactory实例
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            // 生成私钥对象
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            // 创建Cipher实例，明确指定RSA/ECB/PKCS1Padding
            Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey);

            // 解码Base64格式的加密数据
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedData);

            // 执行解密操作
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

            // 返回解密后的字符串
            return new String(decryptedBytes, "UTF-8");
        } catch (javax.crypto.BadPaddingException e) {
            // 填充错误通常意味着密钥不匹配或数据损坏
            throw new IllegalArgumentException(
                "RSA解密失败：密钥不匹配或数据已损坏。请确认前端使用的公钥与后端私钥匹配。",
                e
            );
        } catch (Exception e) {
            // 其他错误
            throw new Exception("RSA解密失败：" + e.getMessage(), e);
        }
    }
}
