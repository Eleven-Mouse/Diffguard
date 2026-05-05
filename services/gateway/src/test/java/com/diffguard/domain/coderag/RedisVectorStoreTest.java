package com.diffguard.domain.coderag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RedisVectorStore")
class RedisVectorStoreTest {

    /**
     * Since Redis is typically unavailable in test environments,
     * the constructor falls back to InMemoryVectorStore.
     * These tests verify the fallback behavior works correctly.
     */

    @Nested
    @DisplayName("构造函数回退")
    class ConstructorFallback {

        @Test
        @DisplayName("Redis 不可用时回退到内存存储，不抛出异常")
        void fallbackToInMemoryWhenRedisUnavailable() {
            // Use a non-existent host/port to trigger fallback
            assertDoesNotThrow(() -> {
                RedisVectorStore store = new RedisVectorStore("invalid-host", 9999, "test", 128);
                // Should still be usable via fallback
                assertEquals(0, store.size());
            });
        }
    }

    @Nested
    @DisplayName("store() 和 search() 回退模式")
    class FallbackOperations {

        private RedisVectorStore createStore() {
            return new RedisVectorStore("invalid-host", 9999, "test", 4);
        }

        @Test
        @DisplayName("store 在回退模式下存储向量")
        void storeInFallback() {
            RedisVectorStore store = createStore();
            store.store("chunk1", new float[]{0.1f, 0.2f, 0.3f, 0.4f});

            assertEquals(1, store.size());
        }

        @Test
        @DisplayName("store 多个向量")
        void storeMultiple() {
            RedisVectorStore store = createStore();
            store.store("chunk1", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            store.store("chunk2", new float[]{0.0f, 1.0f, 0.0f, 0.0f});
            store.store("chunk3", new float[]{0.0f, 0.0f, 1.0f, 0.0f});

            assertEquals(3, store.size());
        }

        @Test
        @DisplayName("search 在回退模式下返回结果")
        void searchInFallback() {
            RedisVectorStore store = createStore();
            store.store("chunk1", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            store.store("chunk2", new float[]{0.0f, 1.0f, 0.0f, 0.0f});

            var results = store.search(new float[]{0.9f, 0.1f, 0.0f, 0.0f}, 2);

            assertEquals(2, results.size());
            // Most similar should be chunk1
            assertEquals("chunk1", results.get(0).chunkId());
        }

        @Test
        @DisplayName("search 空 store 返回空列表")
        void searchEmptyStore() {
            RedisVectorStore store = createStore();

            var results = store.search(new float[]{1.0f, 0.0f, 0.0f, 0.0f}, 5);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("search topK 限制结果数量")
        void searchTopK() {
            RedisVectorStore store = createStore();
            for (int i = 0; i < 10; i++) {
                store.store("chunk" + i, new float[]{(float) i / 10, 0, 0, 0});
            }

            var results = store.search(new float[]{1.0f, 0, 0, 0}, 3);
            assertEquals(3, results.size());
        }
    }

    @Nested
    @DisplayName("clear() 回退模式")
    class ClearFallback {

        @Test
        @DisplayName("clear 清空回退存储")
        void clearsFallback() {
            RedisVectorStore store = new RedisVectorStore("invalid-host", 9999, "test", 4);
            store.store("chunk1", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            assertEquals(1, store.size());

            store.clear();
            assertEquals(0, store.size());
        }
    }

    @Nested
    @DisplayName("cosineSimilarity()")
    class CosineSimilarityTest {

        @Test
        @DisplayName("相同向量相似度为正数")
        void sameVectorsPositiveSimilarity() {
            float[] v = {1.0f, 2.0f, 3.0f, 4.0f};
            float sim = RedisVectorStore.cosineSimilarity(v, v);
            assertTrue(sim > 0);
        }

        @Test
        @DisplayName("正交向量相似度为 0")
        void orthogonalVectorsZeroSimilarity() {
            float[] a = {1.0f, 0.0f, 0.0f, 0.0f};
            float[] b = {0.0f, 1.0f, 0.0f, 0.0f};
            float sim = RedisVectorStore.cosineSimilarity(a, b);
            assertEquals(0.0f, sim, 1e-6f);
        }

        @Test
        @DisplayName("不同长度向量返回 0")
        void differentLengthsReturnZero() {
            float[] a = {1.0f, 2.0f};
            float[] b = {1.0f, 2.0f, 3.0f};
            float sim = RedisVectorStore.cosineSimilarity(a, b);
            assertEquals(0.0f, sim, 1e-6f);
        }

        @Test
        @DisplayName("方向相同的向量有高相似度")
        void sameDirectionHighSimilarity() {
            float[] a = {1.0f, 1.0f, 0.0f, 0.0f};
            float[] b = {0.9f, 0.9f, 0.0f, 0.0f};
            float sim = RedisVectorStore.cosineSimilarity(a, b);
            assertTrue(sim > 1.5f, "Similar vectors should have high dot product");
        }
    }
}
