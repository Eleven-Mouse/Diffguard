package com.diffguard.review.coderag;

import com.diffguard.platform.common.JacksonMapper;
import com.diffguard.platform.config.ReviewConfig;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * ChromaDB HTTP 向量存储实现。
 * 若 Chroma 不可达，将自动回退到进程内临时向量缓存，避免中断主流程。
 */
public class ChromaVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(ChromaVectorStore.class);

    private final HttpClient client;
    private final String baseUrl;
    private final String tenant;
    private final String database;
    private final String collectionName;
    private final String apiToken;
    private final List<String> fallbackChunkIds = new ArrayList<>();
    private final List<float[]> fallbackVectors = new ArrayList<>();

    private volatile String collectionId;
    private volatile boolean available = true;

    public ChromaVectorStore(ReviewConfig.CodeRagStoreConfig cfg) {
        this.baseUrl = cfg.resolveChromaUrl();
        this.tenant = cfg.resolveTenant();
        this.database = cfg.resolveDatabase();
        this.collectionName = cfg.resolveCollection();
        this.apiToken = cfg.resolveApiToken();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, cfg.getTimeoutSeconds())))
                .build();
    }

    @Override
    public synchronized void store(String chunkId, float[] vector) {
        if (!ensureAvailable()) {
            fallbackStore(chunkId, vector);
            return;
        }
        try {
            String cid = ensureCollectionId();
            ObjectNode root = JacksonMapper.MAPPER.createObjectNode();
            ArrayNode ids = root.putArray("ids");
            ids.add(chunkId);
            ArrayNode embeddings = root.putArray("embeddings");
            ArrayNode emb = JacksonMapper.MAPPER.createArrayNode();
            for (float v : vector) emb.add(v);
            embeddings.add(emb);

            HttpRequest req = requestBuilder(collectionBasePath(cid) + "/add")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("Chroma add failed: HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            degrade("store", e);
            fallbackStore(chunkId, vector);
        }
    }

    @Override
    public synchronized List<SearchResult> search(float[] queryVector, int topK) {
        if (!ensureAvailable()) {
            return fallbackSearch(queryVector, topK);
        }
        try {
            String cid = ensureCollectionId();
            ObjectNode root = JacksonMapper.MAPPER.createObjectNode();
            ArrayNode queryEmbeddings = root.putArray("query_embeddings");
            ArrayNode emb = JacksonMapper.MAPPER.createArrayNode();
            for (float v : queryVector) emb.add(v);
            queryEmbeddings.add(emb);
            root.put("n_results", Math.max(1, topK));

            HttpRequest req = requestBuilder(collectionBasePath(cid) + "/query")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("Chroma query failed: HTTP " + resp.statusCode());
            }

            JsonNode body = JacksonMapper.MAPPER.readTree(resp.body());
            ArrayNode idsOuter = array(body.path("ids"));
            ArrayNode distancesOuter = array(body.path("distances"));
            if (idsOuter == null || idsOuter.isEmpty()) {
                return List.of();
            }

            ArrayNode ids = array(idsOuter.get(0));
            ArrayNode distances = distancesOuter != null && !distancesOuter.isEmpty()
                    ? array(distancesOuter.get(0)) : null;

            List<SearchResult> out = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i).asText("");
                double distance = distances != null && i < distances.size()
                        ? distances.get(i).asDouble(1.0) : 1.0;
                float score = (float) (1.0 - distance);
                out.add(new SearchResult(id, score));
            }
            out.sort(Comparator.reverseOrder());
            return out;
        } catch (Exception e) {
            degrade("search", e);
            return fallbackSearch(queryVector, topK);
        }
    }

    @Override
    public synchronized int size() {
        if (!ensureAvailable()) {
            return fallbackSize();
        }
        try {
            String cid = ensureCollectionId();
            HttpRequest req = requestBuilder(collectionBasePath(cid) + "/count")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("Chroma count failed: HTTP " + resp.statusCode());
            }
            JsonNode body = JacksonMapper.MAPPER.readTree(resp.body());
            return body.path("count").asInt(0);
        } catch (Exception e) {
            degrade("size", e);
            return fallbackSize();
        }
    }

    @Override
    public synchronized void clear() {
        fallbackClear();
        if (!ensureAvailable()) return;
        try {
            String cid = ensureCollectionId();
            HttpRequest req = requestBuilder(collectionBasePath(cid) + "/delete")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            degrade("clear", e);
        }
    }

    private boolean ensureAvailable() {
        return available;
    }

    private String ensureCollectionId() throws Exception {
        if (collectionId != null && !collectionId.isBlank()) {
            return collectionId;
        }
        synchronized (this) {
            if (collectionId != null && !collectionId.isBlank()) {
                return collectionId;
            }
            String found = findCollectionIdByName();
            if (found != null && !found.isBlank()) {
                collectionId = found;
                return collectionId;
            }
            collectionId = createCollection();
            return collectionId;
        }
    }

    private String findCollectionIdByName() throws Exception {
        String path = dbBasePath() + "/collections/" + collectionName;
        HttpRequest req = requestBuilder(path).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Chroma get collection failed: HTTP " + resp.statusCode());
        }
        JsonNode body = JacksonMapper.MAPPER.readTree(resp.body());
        String id = body.path("id").asText("");
        return id.isBlank() ? null : id;
    }

    private String createCollection() throws Exception {
        ObjectNode body = JacksonMapper.MAPPER.createObjectNode();
        body.put("name", collectionName);
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("hnsw:space", "cosine");

        HttpRequest req = requestBuilder(dbBasePath() + "/collections")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Chroma create collection failed: HTTP " + resp.statusCode());
        }
        JsonNode json = JacksonMapper.MAPPER.readTree(resp.body());
        String id = json.path("id").asText("");
        if (id.isBlank()) {
            throw new IllegalStateException("Chroma create collection returned empty id");
        }
        return id;
    }

    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (apiToken != null && !apiToken.isBlank()) {
            builder.header("Authorization", "Bearer " + apiToken);
        }
        return builder;
    }

    private String dbBasePath() {
        return "/api/v2/tenants/" + tenant + "/databases/" + database;
    }

    private String collectionBasePath(String cid) {
        return dbBasePath() + "/collections/" + cid;
    }

    private static ArrayNode array(JsonNode node) {
        return (node != null && node.isArray()) ? (ArrayNode) node : null;
    }

    private void degrade(String op, Exception e) {
        available = false;
        log.warn("Chroma unavailable in op={}, fallback to in-memory: {}", op, e.getMessage());
    }

    private void fallbackStore(String chunkId, float[] vector) {
        fallbackChunkIds.add(chunkId);
        fallbackVectors.add(vector.clone());
    }

    private List<SearchResult> fallbackSearch(float[] queryVector, int topK) {
        if (queryVector.length == 0 || fallbackVectors.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, topK);
        PriorityQueue<SearchResult> heap = new PriorityQueue<>(limit + 1);
        for (int i = 0; i < fallbackVectors.size(); i++) {
            float score = cosineSimilarity(queryVector, fallbackVectors.get(i));
            heap.offer(new SearchResult(fallbackChunkIds.get(i), score));
            if (heap.size() > limit) {
                heap.poll();
            }
        }
        List<SearchResult> results = new ArrayList<>(heap);
        results.sort(Comparator.reverseOrder());
        return results;
    }

    private int fallbackSize() {
        return fallbackChunkIds.size();
    }

    private void fallbackClear() {
        fallbackChunkIds.clear();
        fallbackVectors.clear();
    }

    static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        float dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
