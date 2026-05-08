package com.diffguard.domain.coderag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Redis 向量存储实现。
 * <p>
 * 使用 Redis 的 HSET 存储向量（float[] → byte[]），
 * ZADD 存储向量维度元数据。
 * 通过 cosine similarity 计算，适用于中等规模（万级 chunk）。
 * <p>
 * 注意：生产环境中万级以上建议使用 Milvus，此实现作为
 * Redis 的轻量级替代方案。
 */
public class RedisVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(RedisVectorStore.class);

    private final String redisKey;
    private final int dimension;
    private redis.clients.jedis.JedisPool jedisPool;

    // Fallback: local computation if Redis is unavailable
    private final InMemoryVectorStore fallback = new InMemoryVectorStore();

    /**
     * @param host      Redis host
     * @param port      Redis port
     * @param keyPrefix key prefix for this store
     * @param dimension vector dimension
     */
    public RedisVectorStore(String host, int port, String keyPrefix, int dimension) {
        this.redisKey = keyPrefix + ":vectors";
        this.dimension = dimension;
        try {
            this.jedisPool = new redis.clients.jedis.JedisPool(host, port);
            // Test connection
            try (var jedis = jedisPool.getResource()) {
                jedis.ping();
            }
            log.info("RedisVectorStore connected: {}:{}", host, port);
        } catch (Exception e) {
            log.warn("Redis unavailable, using in-memory fallback: {}", e.getMessage());
            this.jedisPool = null;
        }
    }

    @Override
    public void store(String chunkId, float[] vector) {
        if (jedisPool == null) {
            fallback.store(chunkId, vector);
            return;
        }
        try (var jedis = jedisPool.getResource()) {
            byte[] bytes = floatsToBytes(vector);
            Map<String, String> fields = new HashMap<>();
            fields.put("vector", Base64.getEncoder().encodeToString(bytes));
            fields.put("dim", String.valueOf(vector.length));
            jedis.hset((redisKey + ":" + chunkId).getBytes(), toStringStringMap(fields));
        }
    }

    @Override
    public List<SearchResult> search(float[] queryVector, int topK) {
        if (jedisPool == null) {
            return fallback.search(queryVector, topK);
        }

        // Scan all stored vectors and compute similarity
        List<SearchResult> results = new ArrayList<>();
        try (var jedis = jedisPool.getResource()) {
            List<String> keys = scanKeys(jedis);

            PriorityQueue<SearchResult> heap = new PriorityQueue<>(topK + 1);

            for (String key : keys) {
                var data = jedis.hgetAll(key);
                String vectorB64 = data.get("vector");
                if (vectorB64 == null) continue;

                float[] stored = bytesToFloats(Base64.getDecoder().decode(vectorB64));
                float score = cosineSimilarity(queryVector, stored);

                // Extract chunkId from key
                String chunkId = key.substring((redisKey + ":").length());
                heap.offer(new SearchResult(chunkId, score));
                if (heap.size() > topK) heap.poll();
            }

            results = new ArrayList<>(heap);
            results.sort(Comparator.reverseOrder());
        } catch (Exception e) {
            log.warn("Redis search failed, using fallback: {}", e.getMessage());
            return fallback.search(queryVector, topK);
        }
        return results;
    }

    @Override
    public int size() {
        if (jedisPool == null) return fallback.size();
        try (var jedis = jedisPool.getResource()) {
            return scanKeys(jedis).size();
        }
    }

    @Override
    public void clear() {
        if (jedisPool == null) { fallback.clear(); return; }
        try (var jedis = jedisPool.getResource()) {
            List<String> keys = scanKeys(jedis);
            if (!keys.isEmpty()) jedis.del(keys.toArray(new String[0]));
        }
    }

    /** Use SCAN instead of KEYS to avoid blocking Redis in production. */
    private List<String> scanKeys(redis.clients.jedis.Jedis jedis) {
        List<String> allKeys = new ArrayList<>();
        String cursor = "0";
        String match = redisKey + ":*";
        do {
            var scanResult = jedis.scan(cursor, new redis.clients.jedis.ScanParams().match(match).count(100));
            allKeys.addAll(scanResult.getResult());
            cursor = scanResult.getCursor();
        } while (!"0".equals(cursor));
        return allKeys;
    }

    // --- helpers ---

    static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        float dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    private static byte[] floatsToBytes(float[] floats) {
        byte[] bytes = new byte[floats.length * 4];
        java.nio.ByteBuffer.wrap(bytes).asFloatBuffer().put(floats);
        return bytes;
    }

    private static float[] bytesToFloats(byte[] bytes) {
        float[] floats = new float[bytes.length / 4];
        java.nio.ByteBuffer.wrap(bytes).asFloatBuffer().get(floats);
        return floats;
    }

    private static Map<byte[], byte[]> toStringStringMap(Map<String, String> map) {
        Map<byte[], byte[]> result = new HashMap<>();
        map.forEach((k, v) -> result.put(k.getBytes(), v.getBytes()));
        return result;
    }
}
