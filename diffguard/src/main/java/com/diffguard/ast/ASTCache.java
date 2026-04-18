package com.diffguard.ast;

import com.diffguard.ast.model.ASTAnalysisResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/**
 * AST 分析结果的内存缓存。
 * <p>
 * 基于 Caffeine，以文件内容 hash 为 key，纯内存不落盘。
 * 避免同一 review 会话中对未变更文件的重复解析。
 */
public class ASTCache {

    private static final int MAX_ENTRIES = 200;
    private static final long EXPIRE_HOURS = 2;

    private final Cache<String, ASTAnalysisResult> cache;

    public ASTCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(EXPIRE_HOURS, TimeUnit.HOURS)
                .build();
    }

    public ASTAnalysisResult get(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, ASTAnalysisResult result) {
        cache.put(key, result);
    }

    public long size() {
        return cache.estimatedSize();
    }

    /**
     * 基于文件路径 + 文件内容计算缓存 key。
     */
    public static String computeKey(String filePath, String fileContent) {
        String raw = filePath + ":" + (fileContent == null ? "" : fileContent);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }
}
