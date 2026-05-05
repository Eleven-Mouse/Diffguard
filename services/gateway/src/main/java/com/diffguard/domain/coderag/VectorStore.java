package com.diffguard.domain.coderag;

import java.util.List;

/**
 * 向量存储接口，支持向量的存储和相似度检索。
 */
public interface VectorStore {

    /**
     * 存储一个向量，关联到指定的 chunk ID。
     */
    void store(String chunkId, float[] vector);

    /**
     * 批量存储。
     */
    default void storeBatch(List<String> chunkIds, List<float[]> vectors) {
        for (int i = 0; i < chunkIds.size(); i++) {
            store(chunkIds.get(i), vectors.get(i));
        }
    }

    /**
     * 查询与给定向量最相似的 topK 个结果。
     *
     * @param queryVector 查询向量
     * @param topK        返回数量
     * @return 按相似度降序排列的结果列表
     */
    List<SearchResult> search(float[] queryVector, int topK);

    /**
     * 返回已存储的向量数量。
     */
    int size();

    /**
     * 清空所有存储的向量。
     */
    void clear();

    /**
     * 搜索结果。
     */
    record SearchResult(String chunkId, float score) implements Comparable<SearchResult> {
        @Override
        public int compareTo(SearchResult other) {
            return Float.compare(this.score, other.score);
        }
    }
}
