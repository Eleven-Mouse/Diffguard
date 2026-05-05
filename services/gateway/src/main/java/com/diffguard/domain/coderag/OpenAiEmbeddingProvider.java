package com.diffguard.domain.coderag;

import com.diffguard.infrastructure.common.JacksonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

/**
 * 基于 OpenAI Embedding API 的向量化实现。
 * <p>
 * 直接 HTTP 调用 OpenAI Embedding API，不依赖 LangChain4j。
 * 支持 text-embedding-3-small / text-embedding-3-large 等模型。
 */
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingProvider.class);

    private static final int API_BATCH_LIMIT = 2048;

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final int dimensions;
    private final HttpClient httpClient;
    private final Duration timeout;

    public OpenAiEmbeddingProvider(String apiKey, String baseUrl,
                                   String modelName, int dimensions,
                                   Duration timeout) {
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "https://api.openai.com/v1";
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
            float[][] results = embedBatch(new String[]{text});
            return results[0];
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
            ObjectNode body = JacksonMapper.MAPPER.createObjectNode();
            body.put("model", modelName);
            ArrayNode inputArray = body.putArray("input");
            for (String text : texts) {
                inputArray.add(text);
            }

            String jsonBody = JacksonMapper.MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Embedding API error " + response.statusCode() + ": "
                        + (response.body().length() > 200 ? response.body().substring(0, 200) : response.body()));
            }

            JsonNode root = JacksonMapper.MAPPER.readTree(response.body());
            JsonNode dataArray = root.path("data");

            float[][] result = new float[texts.length][];
            for (int i = 0; i < dataArray.size(); i++) {
                JsonNode embeddingNode = dataArray.get(i).path("embedding");
                result[i] = new float[embeddingNode.size()];
                for (int j = 0; j < embeddingNode.size(); j++) {
                    result[i][j] = (float) embeddingNode.get(j).asDouble();
                }
            }
            return result;
        } catch (Exception e) {
            log.error("OpenAI batch embedding 调用失败 ({} 条): {}", texts.length, e.getMessage());
            throw new EmbeddingUnavailableException("OpenAI batch embedding 失败: " + e.getMessage(), e);
        }
    }

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

    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
