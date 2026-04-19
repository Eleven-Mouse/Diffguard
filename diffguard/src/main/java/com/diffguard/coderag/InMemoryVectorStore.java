package com.diffguard.coderag;

import java.util.*;

/**
 * 内存向量存储，使用余弦相似度进行检索。
 * <p>
 * 适用于中小规模代码库（数千个 chunk），无需外部依赖。
 * 向量存储为 float[]，chunk ID 映射到向量索引。
 */
public class InMemoryVectorStore implements VectorStore {

    private final List<String> chunkIds = new ArrayList<>();
    private final List<float[]> vectors = new ArrayList<>();

    @Override
    public synchronized void store(String chunkId, float[] vector) {
        chunkIds.add(chunkId);
        vectors.add(vector.clone());
    }

    @Override
    public synchronized List<SearchResult> search(float[] queryVector, int topK) {
        if (vectors.isEmpty() || queryVector.length == 0) {
            return List.of();
        }

        PriorityQueue<SearchResult> heap = new PriorityQueue<>(topK + 1);

        for (int i = 0; i < vectors.size(); i++) {
            float score = cosineSimilarity(queryVector, vectors.get(i));
            heap.offer(new SearchResult(chunkIds.get(i), score));
            if (heap.size() > topK) {
                heap.poll();
            }
        }

        List<SearchResult> results = new ArrayList<>(heap);
        results.sort(Comparator.reverseOrder());
        return results;
    }

    @Override
    public synchronized int size() {
        return chunkIds.size();
    }

    @Override
    public synchronized void clear() {
        chunkIds.clear();
        vectors.clear();
    }

    /**
     * 计算余弦相似度（假设向量已 L2 归一化，即点积 = 余弦相似度）。
     */
    static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        float dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
