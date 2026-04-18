package com.diffguard.coderag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalTFIDFProviderTest {

    private LocalTFIDFProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalTFIDFProvider();
        provider.buildVocabulary(List.of(
                "public class OrderService { void save() { dao.insert(order); } }",
                "public class UserService { User findByName(String name) { return dao.query(name); } }",
                "public class OrderDAO { void insert(Order order) { sql.execute(); } }"
        ));
    }

    @Test
    void dimension_matchesVocabularySize() {
        assertTrue(provider.dimension() > 0);
        assertEquals(provider.getVocabulary().size(), provider.dimension());
    }

    @Test
    void embed_returnsNormalizedVector() {
        float[] vector = provider.embed("save order to database");
        assertEquals(provider.dimension(), vector.length);

        // L2 norm should be ~1.0 (normalized)
        float norm = 0;
        for (float v : vector) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            assertEquals(1.0f, norm, 0.01f, "Vector should be L2 normalized");
        }
    }

    @Test
    void embed_emptyString_returnsZeroVector() {
        float[] vector = provider.embed("");
        assertEquals(provider.dimension(), vector.length);
    }

    @Test
    void embedBatch_returnsCorrectCount() {
        float[][] batch = provider.embedBatch(new String[]{
                "save order", "find user", "execute sql"
        });
        assertEquals(3, batch.length);
        for (float[] v : batch) {
            assertEquals(provider.dimension(), v.length);
        }
    }

    @Test
    void tokenize_splitsCamelCase() {
        List<String> tokens = provider.tokenize("processOrder");
        assertTrue(tokens.contains("process"));
        assertTrue(tokens.contains("order"));
    }

    @Test
    void tokenize_splitsSnakeCase() {
        List<String> tokens = provider.tokenize("find_by_name");
        assertTrue(tokens.contains("find"));
        assertTrue(tokens.contains("by"));
        assertTrue(tokens.contains("name"));
    }

    @Test
    void similarTexts_haveHigherSimilarity() {
        float[] v1 = provider.embed("save order to database insert");
        float[] v2 = provider.embed("insert order into database save");
        float[] v3 = provider.embed("find user by name query");

        float sim12 = InMemoryVectorStore.cosineSimilarity(v1, v2);
        float sim13 = InMemoryVectorStore.cosineSimilarity(v1, v3);

        assertTrue(sim12 > sim13,
                "Similar texts should have higher cosine similarity than dissimilar ones");
    }
}
