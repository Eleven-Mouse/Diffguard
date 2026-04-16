package com.diffguard.cache;

import com.diffguard.model.ReviewIssue;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ReviewCache {

    private final Cache<String, List<ReviewIssue>> cache;

    public ReviewCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    /**
     * 根据文件路径和差异内容的 SHA-256 哈希生成缓存键。
     */
    public static String buildKey(String filePath, String diffContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(diffContent.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return filePath + ":" + hexString;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在所有标准 JVM 中都可用，回退到 hashCode
            return filePath + ":" + Integer.toHexString(diffContent.hashCode());
        }
    }

    /**
     * 获取缓存的审查结果，如果未缓存则返回null。
     */
    public List<ReviewIssue> get(String key) {
        return cache.getIfPresent(key);
    }

    /**
     * 缓存审查结果。
     */
    public void put(String key, List<ReviewIssue> issues) {
        cache.put(key, issues);
    }

    /**
     * 检查键是否已缓存。
     */
    public boolean contains(String key) {
        return cache.getIfPresent(key) != null;
    }

    public void clear() {
        cache.invalidateAll();
    }
}
