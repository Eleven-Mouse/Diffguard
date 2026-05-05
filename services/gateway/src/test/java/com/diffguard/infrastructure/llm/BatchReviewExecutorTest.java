package com.diffguard.infrastructure.llm;

import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.domain.review.model.Severity;
import com.diffguard.exception.LlmApiException;
import com.diffguard.infrastructure.output.TerminalUI;
import com.diffguard.infrastructure.prompt.PromptBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BatchReviewExecutor")
class BatchReviewExecutorTest {

    private BatchReviewExecutor executor;

    @BeforeAll
    static void setSilent() {
        TerminalUI.setSilent(true);
    }

    @BeforeEach
    void setUp() {
        executor = new BatchReviewExecutor(2);
    }

    private PromptBuilder.PromptContent createPrompt(String content) {
        return new PromptBuilder.PromptContent("system", content);
    }

    // ------------------------------------------------------------------
    // executeBatch - success cases
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("executeBatch - success")
    class ExecuteBatchSuccess {

        @Test
        @DisplayName("single prompt returns merged result")
        void singlePromptSuccess() throws LlmApiException {
            List<PromptBuilder.PromptContent> prompts = List.of(createPrompt("diff1"));
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.WARNING);
            issue.setFile("Test.java");
            issue.setLine(10);
            issue.setType("BUG");
            issue.setMessage("Potential NPE");

            LlmResponse response = LlmResponse.fromContent(
                    "{\"has_critical\":false,\"issues\":[{\"severity\":\"WARNING\",\"file\":\"Test.java\",\"line\":10,\"type\":\"BUG\",\"message\":\"Potential NPE\"}]}");

            ReviewResult result = executor.executeBatch(prompts, p -> response);

            assertEquals(1, result.getIssues().size());
            assertEquals(1, result.getTotalFilesReviewed());
        }

        @Test
        @DisplayName("multiple prompts merge results")
        void multiplePromptsMerge() throws LlmApiException {
            List<PromptBuilder.PromptContent> prompts = List.of(
                    createPrompt("diff1"), createPrompt("diff2"), createPrompt("diff3"));

            ReviewResult result = executor.executeBatch(prompts, p -> {
                LlmResponse response = LlmResponse.fromContent(
                        "{\"has_critical\":false,\"issues\":[{\"severity\":\"INFO\",\"file\":\""
                                + p.getUserPrompt() + ".java\",\"line\":1,\"type\":\"TYPE\",\"message\":\"msg\"}]}");
                return response;
            });

            assertEquals(3, result.getIssues().size());
            assertEquals(3, result.getTotalFilesReviewed());
        }

        @Test
        @DisplayName("empty prompts list returns empty result")
        void emptyPrompts() throws LlmApiException {
            List<PromptBuilder.PromptContent> prompts = List.of();
            ReviewResult result = executor.executeBatch(prompts, p -> null);
            assertEquals(0, result.getIssues().size());
        }

        @Test
        @DisplayName("raw text response merges correctly")
        void rawTextResponseMerges() throws LlmApiException {
            List<PromptBuilder.PromptContent> prompts = List.of(createPrompt("diff1"));
            LlmResponse response = new LlmResponse(List.of(), "Raw text output", null);

            ReviewResult result = executor.executeBatch(prompts, p -> response);

            assertEquals("Raw text output", result.getRawReport());
            assertEquals(1, result.getTotalFilesReviewed());
        }

        @Test
        @DisplayName("multiple raw text responses are concatenated")
        void multipleRawTextConcatenated() throws LlmApiException {
            List<PromptBuilder.PromptContent> prompts = List.of(
                    createPrompt("d1"), createPrompt("d2"));

            ReviewResult result = executor.executeBatch(prompts, p -> {
                if (p.getUserPrompt().contains("d1")) {
                    return new LlmResponse(List.of(), "Part 1", null);
                }
                return new LlmResponse(List.of(), "Part 2", null);
            });

            assertTrue(result.getRawReport().contains("Part 1"));
            assertTrue(result.getRawReport().contains("Part 2"));
            assertTrue(result.isRawReport());
        }
    }

    // ------------------------------------------------------------------
    // executeBatch - failure cases
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("executeBatch - failures")
    class ExecuteBatchFailures {

        @Test
        @DisplayName("all batches failed throws LlmApiException")
        void allBatchesFailed() {
            List<PromptBuilder.PromptContent> prompts = List.of(createPrompt("diff1"));

            LlmApiException thrown = assertThrows(LlmApiException.class,
                    () -> executor.executeBatch(prompts, p -> {
                        throw new RuntimeException(new LlmApiException("API error"));
                    }));
            assertTrue(thrown.getMessage().contains("失败"));
        }

        @Test
        @DisplayName("partial failure returns partial result")
        void partialFailure() throws LlmApiException {
            List<PromptBuilder.PromptContent> prompts = List.of(
                    createPrompt("d1"), createPrompt("d2"));

            ReviewResult result = executor.executeBatch(prompts, p -> {
                if (p.getUserPrompt().contains("d1")) {
                    return LlmResponse.fromContent(
                            "{\"has_critical\":false,\"issues\":[]}");
                }
                throw new RuntimeException(new LlmApiException("API error for d2"));
            });

            assertEquals(1, result.getTotalFilesReviewed());
        }
    }

    // ------------------------------------------------------------------
    // mergeResponse
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("mergeResponse")
    class MergeResponse {

        @Test
        @DisplayName("merges structured issues into result")
        void mergesStructuredIssues() {
            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.CRITICAL);
            issue.setFile("A.java");
            issue.setLine(1);
            issue.setType("SEC");
            issue.setMessage("SQL injection");

            LlmResponse response = new LlmResponse(List.of(issue), null, false);

            executor.mergeResponse(response, result);

            assertEquals(1, result.getIssues().size());
            assertEquals(1, result.getTotalFilesReviewed());
        }

        @Test
        @DisplayName("sets hasCriticalFlag when response has critical")
        void setsCriticalFlag() {
            ReviewResult result = new ReviewResult();
            LlmResponse response = new LlmResponse(List.of(), null, true);

            executor.mergeResponse(response, result);

            assertTrue(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("sets hasCriticalFlag when issue severity blocks commit")
        void setsCriticalFlagFromSeverity() {
            ReviewResult result = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.CRITICAL);
            issue.setFile("A.java");
            issue.setLine(1);
            issue.setType("SEC");
            issue.setMessage("Critical");

            LlmResponse response = new LlmResponse(List.of(issue), null, null);

            executor.mergeResponse(response, result);

            assertTrue(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("merges raw text response")
        void mergesRawText() {
            ReviewResult result = new ReviewResult();
            LlmResponse response = new LlmResponse(List.of(), "raw text", null);

            executor.mergeResponse(response, result);

            assertEquals("raw text", result.getRawReport());
        }

        @Test
        @DisplayName("concatenates multiple raw text responses")
        void concatenatesRawText() {
            ReviewResult result = new ReviewResult();
            LlmResponse r1 = new LlmResponse(List.of(), "part1", null);
            LlmResponse r2 = new LlmResponse(List.of(), "part2", null);

            executor.mergeResponse(r1, result);
            executor.mergeResponse(r2, result);

            assertTrue(result.getRawReport().contains("part1"));
            assertTrue(result.getRawReport().contains("part2"));
        }

        @Test
        @DisplayName("increments totalFilesReviewed")
        void incrementsFilesReviewed() {
            ReviewResult result = new ReviewResult();
            LlmResponse response = new LlmResponse(List.of(), null, false);

            executor.mergeResponse(response, result);
            executor.mergeResponse(response, result);

            assertEquals(2, result.getTotalFilesReviewed());
        }
    }

    // ------------------------------------------------------------------
    // close
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("close")
    class Close {

        @Test
        @DisplayName("close shuts down executor gracefully")
        void closeShutdown() {
            executor.close();
            // Should not throw on second close
            assertDoesNotThrow(() -> executor.close());
        }
    }
}
