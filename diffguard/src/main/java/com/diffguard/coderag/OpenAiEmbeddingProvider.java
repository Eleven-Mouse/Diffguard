package com.diffguard.coderag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 基于 OpenAI Embedding API 的向量化实现。
 * <p>
 * 使用 LangChain4j 的 {@link OpenAiEmbeddingModel} 调用远程 Embedding API，
 * 支持 text-embedding-3-small / text-embedding-3-large 等模型。
 * <p>
 * 相比 TF-IDF，基于神经网络的 Embedding 能捕获语义相似性
 * （如 "authentication" 与 "login" 的相关性），显著提升代码检索质量。
 */
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingProvider.class);

    /** OpenAI embedding API 单次请求最大输入数。 */
    private static final int API_BATCH_LIMIT = 2048;

    private final OpenAiEmbeddingModel model;
    private final int dimensions;

    public OpenAiEmbeddingProvider(String apiKey, String baseUrl,
                                   String modelName, int dimensions,
                                   Duration timeout) {
        this.dimensions = dimensions;

        var builder = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .maxRetries(2);

        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }

        this.model = builder.build();
        log.info("OpenAI Embedding provider 初始化: model={}, dimensions={}, baseUrl={}",
                modelName, dimensions, baseUrl);
    }

    @Override
    public int dimension() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        try {
            Response<Embedding> response = model.embed(text);
            float[] vector = response.content().vector();
            if (vector.length != dimensions) {
                log.warn("Embedding 维度不匹配: 期望={}, 实际={}", dimensions, vector.length);
            }
            return vector;
        } catch (Exception e) {
            log.error("OpenAI embedding 调用失败: {}", e.getMessage());
            throw new EmbeddingUnavailableException("OpenAI embedding 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public float[][] embedBatch(String[] texts) {
        if (texts.length <= API_BATCH_LIMIT) {
            return doEmbedBatch(texts);
        }

        // 拆分为多个子批次，避免超出 API 限制
        float[][] result = new float[texts.length][];
        int offset = 0;
        while (offset < texts.length) {
            int end = Math.min(offset + API_BATCH_LIMIT, texts.length);
            String[] batch = Arrays.copyOfRange(texts, offset, end);
            float[][] batchResult = doEmbedBatch(batch);
            System.arraycopy(batchResult, 0, result, offset, batchResult.length);
            offset = end;
            log.debug("Embedding batch 进度: {}/{}", offset, texts.length);
        }
        return result;
    }

    private float[][] doEmbedBatch(String[] texts) {
        try {
            List<TextSegment> segments = Arrays.stream(texts)
                    .map(TextSegment::from)
                    .toList();
            Response<List<Embedding>> response = model.embedAll(segments);
            List<Embedding> embeddings = response.content();

            float[][] result = new float[embeddings.size()][];
            for (int i = 0; i < embeddings.size(); i++) {
                result[i] = embeddings.get(i).vector();
            }
            return result;
        } catch (Exception e) {
            log.error("OpenAI batch embedding 调用失败 ({} 条): {}", texts.length, e.getMessage());
            throw new EmbeddingUnavailableException("OpenAI batch embedding 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建 EmbeddingProvider，API Key 缺失或初始化失败时自动降级为 TF-IDF。
     *
     * @return OpenAiEmbeddingProvider 或 LocalTFIDFProvider
     */
    public static EmbeddingProvider createWithFallback(String apiKey, String baseUrl,
                                                       String modelName, int dimensions,
                                                       Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("未提供 Embedding API Key，使用 TF-IDF 作为 fallback");
            return new LocalTFIDFProvider();
        }
        try {
            return new OpenAiEmbeddingProvider(apiKey, baseUrl, modelName, dimensions, timeout);
        } catch (Exception e) {
            log.warn("OpenAI Embedding 初始化失败，降级为 TF-IDF: {}", e.getMessage());
            return new LocalTFIDFProvider();
        }
    }

    /**
     * Embedding 服务不可用异常，用于区分 Embedding API 错误与其他运行时异常。
     */
    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
