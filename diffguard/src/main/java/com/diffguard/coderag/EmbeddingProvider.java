package com.diffguard.coderag;

/**
 * 向量化接口，将文本转换为浮点向量。
 * <p>
 * 实现可以是本地 TF-IDF、OpenAI Embeddings API、或其他 embedding 模型。
 */
public interface EmbeddingProvider {

    /**
     * 获取向量维度。
     */
    int dimension();

    /**
     * 将文本转换为向量。
     *
     * @param text 输入文本
     * @return 归一化的浮点向量
     */
    float[] embed(String text);

    /**
     * 批量向量化（默认逐条调用，子类可覆盖以提高效率）。
     */
    default float[][] embedBatch(String[] texts) {
        float[][] result = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            result[i] = embed(texts[i]);
        }
        return result;
    }
}
