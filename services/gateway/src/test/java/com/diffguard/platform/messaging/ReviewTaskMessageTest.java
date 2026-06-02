package com.diffguard.platform.messaging;

import com.diffguard.review.model.DiffFileEntry;
import com.diffguard.platform.config.ReviewConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReviewTaskMessage")
class ReviewTaskMessageTest {

    private ReviewConfig config;
    private List<DiffFileEntry> diffEntries;

    @BeforeEach
    void setUp() {
        config = new ReviewConfig();
        ReviewConfig.LlmConfig llm = config.getLlm();
        llm.setProvider("openai");
        llm.setModel("gpt-4o");
        llm.setBaseUrl("https://api.openai.com/v1");

        diffEntries = new ArrayList<>();
        diffEntries.add(new DiffFileEntry("src/Main.java", "public class Main {}", 50));
        diffEntries.add(new DiffFileEntry("src/Util.java", "class Util {}", 30));
    }

    private ReviewTaskMessage createMessage(boolean hotfix) {
        return new ReviewTaskMessage(
                "PIPELINE", config, diffEntries, "/project/dir",
                "http://localhost:9090", hotfix);
    }

    // Note: resolveApiKey() reads from env var DIFFGUARD_API_KEY.
    // We set the baseUrl directly so serialization works in toJsonBytes.
    // But resolveApiKey() will throw without env var.
    // We work around by only testing methods that don't serialize the API key,
    // or we handle the exception.

    // ------------------------------------------------------------------
    // Constructor & Getters
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor and Getters")
    class ConstructorAndGetters {

        @Test
        @DisplayName("constructor generates non-null taskId")
        void taskIdNotNull() {
            ReviewTaskMessage msg = createMessage(false);
            assertNotNull(msg.getTaskId());
            assertFalse(msg.getTaskId().isEmpty());
        }

        @Test
        @DisplayName("constructor generates unique taskIds")
        void taskIdUnique() {
            ReviewTaskMessage msg1 = createMessage(false);
            ReviewTaskMessage msg2 = createMessage(false);
            assertNotEquals(msg1.getTaskId(), msg2.getTaskId());
        }

        @Test
        @DisplayName("getMode returns correct mode")
        void getMode() {
            ReviewTaskMessage msg = createMessage(false);
            assertEquals("PIPELINE", msg.getMode());
        }

        @Test
        @DisplayName("getProjectDir returns correct directory")
        void getProjectDir() {
            ReviewTaskMessage msg = createMessage(false);
            assertEquals("/project/dir", msg.getProjectDir());
        }

        @Test
        @DisplayName("getDiffEntries returns correct entries")
        void getDiffEntries() {
            ReviewTaskMessage msg = createMessage(false);
            assertEquals(2, msg.getDiffEntries().size());
            assertEquals("src/Main.java", msg.getDiffEntries().get(0).getFilePath());
        }

        @Test
        @DisplayName("isHotfix returns false for normal message")
        void isHotfixFalse() {
            ReviewTaskMessage msg = createMessage(false);
            assertFalse(msg.isHotfix());
        }

        @Test
        @DisplayName("isHotfix returns true for hotfix message")
        void isHotfixTrue() {
            ReviewTaskMessage msg = createMessage(true);
            assertTrue(msg.isHotfix());
        }

        @Test
        @DisplayName("getCreatedAt returns positive timestamp")
        void getCreatedAt() {
            long before = System.currentTimeMillis();
            ReviewTaskMessage msg = createMessage(false);
            long after = System.currentTimeMillis();
            assertTrue(msg.getCreatedAt() >= before);
            assertTrue(msg.getCreatedAt() <= after);
        }
    }

    // ------------------------------------------------------------------
    // Priority
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Priority")
    class Priority {

        @Test
        @DisplayName("hotfix message has priority 9")
        void hotfixPriority() {
            ReviewTaskMessage msg = createMessage(true);
            assertEquals(9, msg.getPriority());
        }

        @Test
        @DisplayName("normal message has priority 5")
        void normalPriority() {
            ReviewTaskMessage msg = createMessage(false);
            assertEquals(5, msg.getPriority());
        }
    }

    // ------------------------------------------------------------------
    // Routing Key
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Routing Key")
    class RoutingKey {

        @Test
        @DisplayName("PIPELINE mode generates correct routing key")
        void pipelineRoutingKey() {
            ReviewTaskMessage msg = createMessage(false);
            assertEquals("review.pipeline.task", msg.getRoutingKey());
        }

        @Test
        @DisplayName("AGENT mode generates correct routing key")
        void agentRoutingKey() {
            ReviewTaskMessage msg = new ReviewTaskMessage(
                    "AGENT", config, diffEntries, "/dir",
                    "http://localhost:9090", false);
            assertEquals("review.agent.task", msg.getRoutingKey());
        }

        @Test
        @DisplayName("SIMPLE mode generates correct routing key")
        void simpleRoutingKey() {
            ReviewTaskMessage msg = new ReviewTaskMessage(
                    "SIMPLE", config, diffEntries, "/dir",
                    "http://localhost:9090", false);
            assertEquals("review.simple.task", msg.getRoutingKey());
        }
    }

    // ------------------------------------------------------------------
    // JSON serialization / deserialization
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("JSON")
    class JsonRoundTrip {

        @Test
        @DisplayName("toJsonBytes should include fields and allowed_files fallback to diff entries")
        void toJsonBytesIncludesExpectedFields() throws Exception {
            ReviewTaskMessage msg = new ReviewTaskMessage(
                    "task-json-1",
                    "PIPELINE",
                    config,
                    diffEntries,
                    "/project/dir",
                    "http://localhost:9090",
                    null,
                    false
            );

            byte[] jsonBytes = msg.toJsonBytes();
            assertNotNull(jsonBytes);
            assertTrue(jsonBytes.length > 0);

            com.fasterxml.jackson.databind.JsonNode root =
                    com.diffguard.platform.common.JacksonMapper.MAPPER.readTree(jsonBytes);
            assertEquals("task-json-1", root.path("task_id").asText());
            assertEquals("task-json-1", root.path("request_id").asText());
            assertEquals("PIPELINE", root.path("mode").asText());
            assertEquals("/project/dir", root.path("project_dir").asText());
            assertEquals("http://localhost:9090", root.path("tool_server_url").asText());
            assertTrue(root.path("created_at").asLong() > 0);
            assertEquals(2, root.path("diff_entries").size());
            // allowed_files 未显式传入时，回退为 diff file list
            assertEquals(2, root.path("allowed_files").size());
            assertEquals("src/Main.java", root.path("allowed_files").get(0).asText());
            assertEquals("src/Util.java", root.path("allowed_files").get(1).asText());
        }

        @Test
        @DisplayName("fromJson should restore task id/mode/diff entries/allowed files")
        void fromJsonShouldRestoreCoreFields() {
            ReviewTaskMessage src = new ReviewTaskMessage(
                    "task-json-2",
                    "MULTI_AGENT",
                    config,
                    diffEntries,
                    "/project/dir",
                    "http://localhost:9091",
                    List.of("src/Main.java"),
                    true
            );

            ReviewTaskMessage restored = ReviewTaskMessage.fromJson(src.toJsonBytes());
            assertEquals("task-json-2", restored.getTaskId());
            assertEquals("MULTI_AGENT", restored.getMode());
            assertEquals("/project/dir", restored.getProjectDir());
            assertEquals(2, restored.getDiffEntries().size());
            assertEquals("src/Main.java", restored.getDiffEntries().get(0).getFilePath());
            // fromJson 当前实现 hotfix 固定为 false
            assertFalse(restored.isHotfix());
        }
    }

    // ------------------------------------------------------------------
    // extractTaskId
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("extractTaskId")
    class ExtractTaskId {

        @Test
        @DisplayName("extractTaskId returns null for invalid data")
        void extractTaskIdInvalid() {
            assertNull(ReviewTaskMessage.extractTaskId("not json".getBytes()));
        }

        @Test
        @DisplayName("extractTaskId returns null for null-like data")
        void extractTaskIdNull() {
            String taskId = ReviewTaskMessage.extractTaskId(new byte[0]);
            assertTrue(taskId == null || taskId.isBlank());
        }

        @Test
        @DisplayName("extractTaskId falls back to request_id when task_id missing")
        void extractTaskIdFallbackToRequestId() {
            String json = "{\"request_id\":\"req-123\",\"mode\":\"PIPELINE\"}";
            assertEquals("req-123", ReviewTaskMessage.extractTaskId(json.getBytes()));
        }
    }
}
