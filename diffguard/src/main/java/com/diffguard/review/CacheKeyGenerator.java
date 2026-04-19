package com.diffguard.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 缓存键生成器。
 * <p>
 * 根据文件路径、差异内容和审查上下文（模型/规则/语言/流水线模式）
 * 生成唯一且稳定的 SHA-256 缓存键。
 */
public final class CacheKeyGenerator {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private CacheKeyGenerator() {}

    /**
     * 根据文件路径和差异内容生成缓存键（不含审查上下文，向后兼容）。
     */
    public static String buildKey(String filePath, String diffContent) {
        return buildKey(filePath, diffContent, null);
    }

    /**
     * 根据文件路径、差异内容和审查上下文生成缓存键。
     *
     * @throws NullPointerException 如果 filePath 或 diffContent 为 null
     */
    public static String buildKey(String filePath, String diffContent, String contextHash) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(diffContent, "diffContent must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(filePath.getBytes(StandardCharsets.UTF_8));
            digest.update(diffContent.getBytes(StandardCharsets.UTF_8));
            if (contextHash != null && !contextHash.isEmpty()) {
                digest.update(contextHash.getBytes(StandardCharsets.UTF_8));
            }
            return hexEncode(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 根据审查配置计算上下文哈希，用于区分不同模型/规则/语言/模板下的审查结果。
     */
    public static String computeContextHash(String model, List<String> enabledRules,
                                            String language, boolean pipelineEnabled) {
        return computeContextHash(model, enabledRules, language, pipelineEnabled, null);
    }

    /**
     * 根据审查配置和 prompt 模板内容计算上下文哈希。
     */
    public static String computeContextHash(String model, List<String> enabledRules,
                                            String language, boolean pipelineEnabled,
                                            String promptHash) {
        String normalizedRules = enabledRules == null ? ""
                : enabledRules.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .sorted()
                        .collect(Collectors.joining(","));

        String raw = (model != null ? model : "<unset>")
                + "|rules=" + normalizedRules
                + "|lang=" + (language != null ? language : "")
                + "|pipeline=" + pipelineEnabled
                + "|prompt=" + (promptHash != null ? promptHash : "");
        return sha256(raw);
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hexEncode(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String hexEncode(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }
}
