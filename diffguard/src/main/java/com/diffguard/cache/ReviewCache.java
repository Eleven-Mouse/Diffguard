package com.diffguard.cache;

import com.diffguard.model.ReviewIssue;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

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
     * Generate a cache key from file path + diff content hash.
     */
    public static String buildKey(String filePath, String diffContent) {
        return filePath + ":" + Integer.toHexString(diffContent.hashCode());
    }

    /**
     * Get cached review result, or null if not cached.
     */
    public List<ReviewIssue> get(String key) {
        return cache.getIfPresent(key);
    }

    /**
     * Cache review result.
     */
    public void put(String key, List<ReviewIssue> issues) {
        cache.put(key, issues);
    }

    /**
     * Check if key is cached.
     */
    public boolean contains(String key) {
        return cache.getIfPresent(key) != null;
    }

    public void clear() {
        cache.invalidateAll();
    }
}
