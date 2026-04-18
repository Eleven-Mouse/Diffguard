package com.diffguard.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 缓存键生成器。
 * <p>
 * 根据文件路径、差异内容和审查上下文（模型/规则/语言/流水线模式）
 * 生成唯一且稳定的 SHA-256 缓存键。
 */
public final class CacheKeyGenerator {

    private CacheKeyGenerator() {}

    /**
     * 根据文件路径和差异内容生成缓存键（不含审查上下文，向后兼容）。
     */
    public static String buildKey(String filePath, String diffContent) {
        return buildKey(filePath, diffContent, null);
    }

    /**
     * 根据文件路径、差异内容和审查上下文生成缓存键。
     */
    public static String buildKey(String filePath, String diffContent, String contextHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(filePath.getBytes(StandardCharsets.UTF_8));
            digest.update(diffContent.getBytes(StandardCharsets.UTF_8));
            if (contextHash != null && !contextHash.isEmpty()) {
                digest.update(contextHash.getBytes(StandardCharsets.UTF_8));
            }
            return hexEncode(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((filePath + diffContent + (contextHash != null ? contextHash : "")).hashCode());
        }
    }

    /**
     * 根据审查配置计算上下文哈希，用于区分不同模型/规则/语言下的审查结果。
     */
    public static String computeContextHash(String model, List<String> enabledRules,
                                            String language, boolean pipelineEnabled) {
        String raw = model
                + "|rules=" + String.join(",", enabledRules != null ? enabledRules : List.of())
                + "|lang=" + (language != null ? language : "")
                + "|pipeline=" + pipelineEnabled;
        return sha256(raw);
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hexEncode(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }
}
