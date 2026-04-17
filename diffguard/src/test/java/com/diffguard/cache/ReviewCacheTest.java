package com.diffguard.cache;

import com.diffguard.model.ReviewIssue;
import com.diffguard.model.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewCacheTest {

    @TempDir
    Path tempDir;

    private ReviewCache cache;

    private ReviewIssue makeIssue(Severity severity, String file, String message) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSeverity(severity);
        issue.setFile(file);
        issue.setMessage(message);
        return issue;
    }

    @BeforeEach
    void setUp() {
        cache = ReviewCache.withCustomCacheDir(tempDir, tempDir);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    @DisplayName("put + get 正常工作")
    void putAndGet() {
        List<ReviewIssue> issues = List.of(
                makeIssue(Severity.CRITICAL, "A.java", "SQL注入"),
                makeIssue(Severity.WARNING, "B.java", "冗余代码")
        );

        String key = ReviewCache.buildKey("A.java", "diff content here");
        cache.put(key, issues);

        List<ReviewIssue> cached = cache.get(key);
        assertNotNull(cached);
        assertEquals(2, cached.size());
        assertEquals("SQL注入", cached.get(0).getMessage());
    }

    @Test
    @DisplayName("get 不存在的 key 返回 null")
    void getNonExistent() {
        String key = ReviewCache.buildKey("X.java", "nonexistent");
        assertNull(cache.get(key));
    }

    @Test
    @DisplayName("contains 正确判断")
    void contains() {
        String key = ReviewCache.buildKey("A.java", "content");
        assertFalse(cache.contains(key));

        cache.put(key, List.of(makeIssue(Severity.INFO, "A.java", "ok")));
        assertTrue(cache.contains(key));
    }

    @Test
    @DisplayName("clear 清除所有缓存")
    void clear() {
        cache.put("key1", List.of(makeIssue(Severity.INFO, "A.java", "a")));
        cache.put("key2", List.of(makeIssue(Severity.INFO, "B.java", "b")));

        assertNotNull(cache.get("key1"));
        assertNotNull(cache.get("key2"));

        cache.clear();

        assertNull(cache.get("key1"));
        assertNull(cache.get("key2"));
    }

    @Test
    @DisplayName("buildKey 对相同输入返回相同结果")
    void buildKeyConsistency() {
        String key1 = ReviewCache.buildKey("A.java", "same content");
        String key2 = ReviewCache.buildKey("A.java", "same content");
        assertEquals(key1, key2);
    }

    @Test
    @DisplayName("buildKey 对不同内容返回不同结果")
    void buildKeyDifferent() {
        String key1 = ReviewCache.buildKey("A.java", "content A");
        String key2 = ReviewCache.buildKey("A.java", "content B");
        assertNotEquals(key1, key2);
    }

    @Test
    @DisplayName("buildKey 对不同文件路径返回不同结果")
    void buildKeyDifferentFiles() {
        String key1 = ReviewCache.buildKey("A.java", "same");
        String key2 = ReviewCache.buildKey("B.java", "same");
        assertNotEquals(key1, key2);
    }
}
