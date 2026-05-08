package com.diffguard.adapter.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
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
            log.error("Webhook secret 未配置，签名校验拒绝（请在配置中设置 webhook secret）");
            return false;
        }

        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }

        byte[] expected = computeHmacBytes(payload);
        byte[] provided;
        try {
            provided = hexToBytes(signature.substring("sha256=".length()));
        } catch (IllegalArgumentException e) {
            return false;
        }

        // 使用 MessageDigest.isEqual 进行常量时间比较
        return MessageDigest.isEqual(expected, provided);
    }

    private byte[] computeHmacBytes(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 初始化失败", e);
        }
    }

    /**
     * 将十六进制字符串转换为字节数组。
     */
    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string length");
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hex character");
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }
}
