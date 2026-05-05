package com.diffguard.domain.ast;

import com.diffguard.domain.ast.model.ASTAnalysisResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ASTCacheTest {

    private final ASTCache cache = new ASTCache();

    @Test
    void putAndGet() {
        ASTAnalysisResult result = new ASTAnalyzer().analyze("Test.java", "class Test {}");
        String key = ASTCache.computeKey("Test.java", "class Test {}");

        cache.put(key, result);
        ASTAnalysisResult cached = cache.get(key);

        assertNotNull(cached);
        assertTrue(cached.isParseSucceeded());
        assertEquals("Test.java", cached.getFilePath());
    }

    @Test
    void getMiss() {
        assertNull(cache.get("nonexistent-key"));
    }

    @Test
    void differentContentDifferentKey() {
        String key1 = ASTCache.computeKey("Foo.java", "class Foo {}");
        String key2 = ASTCache.computeKey("Foo.java", "class Bar {}");
        assertNotEquals(key1, key2);
    }

    @Test
    void differentPathDifferentKey() {
        String key1 = ASTCache.computeKey("a/Foo.java", "class Foo {}");
        String key2 = ASTCache.computeKey("b/Foo.java", "class Foo {}");
        assertNotEquals(key1, key2);
    }

    @Test
    void sameContentSameKey() {
        String key1 = ASTCache.computeKey("Foo.java", "class Foo {}");
        String key2 = ASTCache.computeKey("Foo.java", "class Foo {}");
        assertEquals(key1, key2);
    }

    @Test
    void sizeTracking() {
        assertEquals(0, cache.size());

        cache.put("k1", new ASTAnalyzer().analyze("A.java", "class A {}"));
        cache.put("k2", new ASTAnalyzer().analyze("B.java", "class B {}"));

        assertEquals(2, cache.size());
    }
}
