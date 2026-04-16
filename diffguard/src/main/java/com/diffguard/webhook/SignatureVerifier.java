package com.diffguard.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * GitHub Webhook HMAC-SHA256 签名校验。
 * 使用常量时间比较防止时序攻击。
 */
public class SignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String secret;

    public SignatureVerifier(String secret) {
        this.secret = secret;
    }

    /**
     * 校验 webhook 请求的 HMAC-SHA256 签名。
     *
     * @param payload   请求体原文
     * @param signature X-Hub-Signature-256 头的值（格式：sha256=hex）
     * @return 签名是否有效
     */
    public boolean verify(String payload, String signature) {
        if (secret == null || secret.isBlank()) {
            log.warn("Webhook secret 未配置，跳过签名校验（仅限开发环境使用）");
            return true;
        }

        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }

        String expectedHex = computeHmac(payload);
        String providedHex = signature.substring("sha256=".length());

        return constantTimeEquals(expectedHex, providedHex);
    }

    private String computeHmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 初始化失败", e);
        }
    }

    /**
     * 常量时间字符串比较，防止时序攻击。
     */
    static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
