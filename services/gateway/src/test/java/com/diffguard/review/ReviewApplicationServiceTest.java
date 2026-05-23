package com.diffguard.review;

import com.diffguard.review.ReviewEngine;
import com.diffguard.review.model.DiffFileEntry;
import com.diffguard.review.model.ReviewIssue;
import com.diffguard.review.model.ReviewResult;
import com.diffguard.review.model.Severity;
import com.diffguard.exception.ConfigException;
import com.diffguard.exception.DiffCollectionException;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.exception.LlmApiException;
import com.diffguard.platform.config.ReviewConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ReviewApplicationService} covering config loading,
 * diff collection and enrichment, and review orchestration.
 * <p>
 * Uses MockedStatic for ConfigLoader, GitHubPrDiffCollector, ASTEnricher,
 * ReviewEngineFactory since they are static/utility classes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewApplicationService")
class ReviewApplicationServiceTest {

    @TempDir
    Path tempDir;

    private final ReviewApplicationService service = new ReviewApplicationService();

    // ========================================================================
    // loadConfig
    // ========================================================================

    @Nested
    @DisplayName("loadConfig")
    class LoadConfig {

        @Test
        @DisplayName("returns null when ConfigLoader.load throws ConfigException")
        void returnsNullOnConfigException() {
            try (MockedStatic<com.diffguard.platform.config.ConfigLoader> configLoaderMock =
                         mockStatic(com.diffguard.platform.config.ConfigLoader.class)) {

                configLoaderMock.when(() ->
                        com.diffguard.platform.config.ConfigLoader.load(any(Path.class)))
                        .thenThrow(new ConfigException("bad config"));

                ReviewConfig result = service.loadConfig(tempDir, null);
                assertNull(result);
            }
        }

        @Test
        @DisplayName("returns null when ConfigLoader.loadFromFile throws ConfigException")
        void returnsNullOnLoadFromFileException() {
            Path configPath = tempDir.resolve("config.yml");
            try (MockedStatic<com.diffguard.platform.config.ConfigLoader> configLoaderMock =
                         mockStatic(com.diffguard.platform.config.ConfigLoader.class)) {

                configLoaderMock.when(() ->
                        com.diffguard.platform.config.ConfigLoader.loadFromFile(any(Path.class)))
                        .thenThrow(new ConfigException("file not found"));

                ReviewConfig result = service.loadConfig(tempDir, configPath);
                assertNull(result);
            }
        }

        @Test
        @DisplayName("returns config from ConfigLoader.load when configPath is null")
        void returnsConfigFromLoad() {
            ReviewConfig expected = new ReviewConfig();
            try (MockedStatic<com.diffguard.platform.config.ConfigLoader> configLoaderMock =
                         mockStatic(com.diffguard.platform.config.ConfigLoader.class)) {

                configLoaderMock.when(() ->
                        com.diffguard.platform.config.ConfigLoader.load(tempDir))
                        .thenReturn(expected);

                ReviewConfig result = service.loadConfig(tempDir, null);
                assertSame(expected, result);
            }
        }

        @Test
        @DisplayName("returns config from ConfigLoader.loadFromFile when configPath is provided")
        void returnsConfigFromLoadFromFile() {
            Path configPath = tempDir.resolve("custom-config.yml");
            ReviewConfig expected = new ReviewConfig();
            try (MockedStatic<com.diffguard.platform.config.ConfigLoader> configLoaderMock =
                         mockStatic(com.diffguard.platform.config.ConfigLoader.class)) {

                configLoaderMock.when(() ->
                        com.diffguard.platform.config.ConfigLoader.loadFromFile(configPath))
                        .thenReturn(expected);

                ReviewConfig result = service.loadConfig(tempDir, configPath);
                assertSame(expected, result);
                configLoaderMock.verify(() ->
                        com.diffguard.platform.config.ConfigLoader.loadFromFile(configPath));
                configLoaderMock.verify(() ->
                        com.diffguard.platform.config.ConfigLoader.load(any()), never());
            }
        }

        @Test
        @DisplayName("returns null when IllegalArgumentException is thrown")
        void returnsNullOnIllegalArgument() {
            try (MockedStatic<com.diffguard.platform.config.ConfigLoader> configLoaderMock =
                         mockStatic(com.diffguard.platform.config.ConfigLoader.class)) {

                configLoaderMock.when(() ->
                        com.diffguard.platform.config.ConfigLoader.load(any(Path.class)))
                        .thenThrow(new IllegalArgumentException("invalid args"));

                ReviewConfig result = service.loadConfig(tempDir, null);
                assertNull(result);
            }
        }
    }

    // ========================================================================
    // collectAndEnrich
    // ========================================================================

    @Nested
    @DisplayName("collectAndEnrich")
    class CollectAndEnrich {

        @Test
        @DisplayName("returns null when diff collection fails")
        void returnsNullOnCollectionFailure() {
            ReviewConfig config = new ReviewConfig();
            try (MockedStatic<com.diffguard.platform.git.GitHubPrDiffCollector> prCollectorMock =
                         mockStatic(com.diffguard.platform.git.GitHubPrDiffCollector.class)) {

                prCollectorMock.when(() ->
                        com.diffguard.platform.git.GitHubPrDiffCollector.collectPrDiff(any(), eq("foo/bar#1"), any()))
                        .thenThrow(new DiffCollectionException("git error"));

                List<DiffFileEntry> result = service.collectAndEnrich(
                        tempDir, config, "foo/bar#1");
                assertNull(result);
            }
        }

        @Test
        @DisplayName("returns empty list when no diff entries")
        void returnsEmptyListWhenNoDiff() {
            ReviewConfig config = new ReviewConfig();
            try (MockedStatic<com.diffguard.platform.git.GitHubPrDiffCollector> prCollectorMock =
                         mockStatic(com.diffguard.platform.git.GitHubPrDiffCollector.class)) {

                prCollectorMock.when(() ->
                        com.diffguard.platform.git.GitHubPrDiffCollector.collectPrDiff(any(), eq("foo/bar#1"), any()))
                        .thenReturn(Collections.emptyList());

                List<DiffFileEntry> result = service.collectAndEnrich(
                        tempDir, config, "foo/bar#1");
                assertNotNull(result);
                assertTrue(result.isEmpty());
            }
        }

        @Test
        @DisplayName("returns enriched entries for PR diff")
        void returnsEnrichedEntriesForPr() {
            ReviewConfig config = new ReviewConfig();
            DiffFileEntry rawEntry = new DiffFileEntry("Test.java", "diff content", 10);
            DiffFileEntry enrichedEntry = new DiffFileEntry("Test.java", "enriched content", 15);
            List<DiffFileEntry> rawEntries = List.of(rawEntry);
            List<DiffFileEntry> enrichedEntries = List.of(enrichedEntry);

            try (MockedStatic<com.diffguard.platform.git.GitHubPrDiffCollector> prCollectorMock =
                         mockStatic(com.diffguard.platform.git.GitHubPrDiffCollector.class);
                 org.mockito.MockedConstruction<com.diffguard.review.ast.ASTEnricher> ignored =
                         mockConstruction(com.diffguard.review.ast.ASTEnricher.class,
                                 (mock, context) -> when(mock.enrich(rawEntries)).thenReturn(enrichedEntries))) {

                prCollectorMock.when(() ->
                        com.diffguard.platform.git.GitHubPrDiffCollector.collectPrDiff(any(), eq("foo/bar#1"), any()))
                        .thenReturn(rawEntries);

                List<DiffFileEntry> result = service.collectAndEnrich(
                        tempDir, config, "foo/bar#1");
                assertEquals(1, result.size());
                assertEquals("enriched content", result.get(0).getContent());
            }
        }

        @Test
        @DisplayName("uses GitHubPrDiffCollector for PR mode")
        void usesGitHubPrDiffCollector() {
            ReviewConfig config = new ReviewConfig();
            try (MockedStatic<com.diffguard.platform.git.GitHubPrDiffCollector> prCollectorMock =
                         mockStatic(com.diffguard.platform.git.GitHubPrDiffCollector.class)) {

                prCollectorMock.when(() ->
                        com.diffguard.platform.git.GitHubPrDiffCollector.collectPrDiff(
                                any(), eq("foo/bar#1"), any()))
                        .thenReturn(Collections.emptyList());

                List<DiffFileEntry> result = service.collectAndEnrich(
                        tempDir, config, "foo/bar#1");
                assertNotNull(result);
                assertTrue(result.isEmpty());

                prCollectorMock.verify(() ->
                        com.diffguard.platform.git.GitHubPrDiffCollector.collectPrDiff(
                                any(), eq("foo/bar#1"), any()));
            }
        }

        @Test
        @DisplayName("returns null when PR spec is null")
        void returnsNullWhenPrSpecIsNull() {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> result = service.collectAndEnrich(
                    tempDir, config, null);
            assertNull(result);
        }

        @Test
        @DisplayName("returns null when PR spec is blank")
        void returnsNullWhenPrSpecIsBlank() {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> result = service.collectAndEnrich(
                    tempDir, config, "   ");
            assertNull(result);
        }
    }

    // ========================================================================
    // review
    // ========================================================================

    @Nested
    @DisplayName("review")
    class Review {

        @Test
        @DisplayName("returns null on LlmApiException")
        void returnsNullOnLlmApiException() throws Exception {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> entries = List.of(
                    new DiffFileEntry("Test.java", "content", 10));

            ReviewEngine mockEngine = mock(ReviewEngine.class);
            when(mockEngine.review(anyList(), any(Path.class)))
                    .thenThrow(new LlmApiException(500, "server error"));

            try (MockedStatic<ReviewEngineFactory> factoryMock =
                         mockStatic(ReviewEngineFactory.class)) {

                factoryMock.when(() -> ReviewEngineFactory.resolveEngineType(any(), anyBoolean(), anyBoolean()))
                        .thenReturn(ReviewEngineFactory.EngineType.SIMPLE);
                factoryMock.when(() -> ReviewEngineFactory.create(
                                any(), any(), any(), any(), anyBoolean()))
                        .thenReturn(mockEngine);

                ReviewResult result = service.review(tempDir, config, entries, false, false, false);
                assertNull(result);
            }
        }

        @Test
        @DisplayName("returns null on DiffGuardException")
        void returnsNullOnDiffGuardException() throws Exception {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> entries = List.of(
                    new DiffFileEntry("Test.java", "content", 10));

            ReviewEngine mockEngine = mock(ReviewEngine.class);
            when(mockEngine.review(anyList(), any(Path.class)))
                    .thenThrow(new DiffGuardException("general error"));

            try (MockedStatic<ReviewEngineFactory> factoryMock =
                         mockStatic(ReviewEngineFactory.class)) {

                factoryMock.when(() -> ReviewEngineFactory.resolveEngineType(any(), anyBoolean(), anyBoolean()))
                        .thenReturn(ReviewEngineFactory.EngineType.SIMPLE);
                factoryMock.when(() -> ReviewEngineFactory.create(
                                any(), any(), any(), any(), anyBoolean()))
                        .thenReturn(mockEngine);

                ReviewResult result = service.review(tempDir, config, entries, false, false, false);
                assertNull(result);
            }
        }

        @Test
        @DisplayName("returns ReviewResult on successful SIMPLE review")
        void returnsResultOnSimpleReview() throws Exception {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> entries = List.of(
                    new DiffFileEntry("Test.java", "content", 10));

            ReviewResult expectedResult = new ReviewResult();
            ReviewIssue issue = new ReviewIssue();
            issue.setSeverity(Severity.WARNING);
            issue.setMessage("test warning");
            expectedResult.addIssue(issue);

            ReviewEngine mockEngine = mock(ReviewEngine.class);
            when(mockEngine.review(anyList(), any(Path.class)))
                    .thenReturn(expectedResult);

            try (MockedStatic<ReviewEngineFactory> factoryMock =
                         mockStatic(ReviewEngineFactory.class)) {

                factoryMock.when(() -> ReviewEngineFactory.resolveEngineType(any(), anyBoolean(), anyBoolean()))
                        .thenReturn(ReviewEngineFactory.EngineType.SIMPLE);
                factoryMock.when(() -> ReviewEngineFactory.create(
                                any(), any(), any(), any(), anyBoolean()))
                        .thenReturn(mockEngine);

                ReviewResult result = service.review(tempDir, config, entries, false, false, false);
                assertNotNull(result);
                assertEquals(1, result.getIssues().size());
            }
        }

        @Test
        @DisplayName("returns ReviewResult on successful PIPELINE review")
        void returnsResultOnPipelineReview() throws Exception {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> entries = List.of(
                    new DiffFileEntry("Test.java", "content", 10));

            ReviewResult expectedResult = new ReviewResult();
            ReviewEngine mockEngine = mock(ReviewEngine.class);
            when(mockEngine.review(anyList(), any(Path.class)))
                    .thenReturn(expectedResult);

            try (MockedStatic<ReviewEngineFactory> factoryMock =
                         mockStatic(ReviewEngineFactory.class)) {

                factoryMock.when(() -> ReviewEngineFactory.resolveEngineType(any(), anyBoolean(), anyBoolean()))
                        .thenReturn(ReviewEngineFactory.EngineType.PIPELINE);
                factoryMock.when(() -> ReviewEngineFactory.create(
                                any(), any(), any(), any(), anyBoolean()))
                        .thenReturn(mockEngine);

                ReviewResult result = service.review(tempDir, config, entries, true, true, false);
                assertNotNull(result);
            }
        }

        @Test
        @DisplayName("returns ReviewResult on successful MULTI_AGENT review")
        void returnsResultOnMultiAgentReview() throws Exception {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> entries = List.of(
                    new DiffFileEntry("Test.java", "content", 10));

            ReviewResult expectedResult = new ReviewResult();
            ReviewEngine mockEngine = mock(ReviewEngine.class);
            when(mockEngine.review(anyList(), any(Path.class)))
                    .thenReturn(expectedResult);

            try (MockedStatic<ReviewEngineFactory> factoryMock =
                         mockStatic(ReviewEngineFactory.class)) {

                factoryMock.when(() -> ReviewEngineFactory.resolveEngineType(any(), anyBoolean(), anyBoolean()))
                        .thenReturn(ReviewEngineFactory.EngineType.MULTI_AGENT);
                factoryMock.when(() -> ReviewEngineFactory.create(
                                any(), any(), any(), any(), anyBoolean()))
                        .thenReturn(mockEngine);

                ReviewResult result = service.review(tempDir, config, entries, true, false, true);
                assertNotNull(result);
            }
        }

        @Test
        @DisplayName("closes engine after review (even on success)")
        void closesEngineAfterSuccess() throws Exception {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> entries = List.of(
                    new DiffFileEntry("Test.java", "content", 10));

            ReviewEngine mockEngine = mock(ReviewEngine.class);
            when(mockEngine.review(anyList(), any(Path.class)))
                    .thenReturn(new ReviewResult());

            try (MockedStatic<ReviewEngineFactory> factoryMock =
                         mockStatic(ReviewEngineFactory.class)) {

                factoryMock.when(() -> ReviewEngineFactory.resolveEngineType(any(), anyBoolean(), anyBoolean()))
                        .thenReturn(ReviewEngineFactory.EngineType.SIMPLE);
                factoryMock.when(() -> ReviewEngineFactory.create(
                                any(), any(), any(), any(), anyBoolean()))
                        .thenReturn(mockEngine);

                service.review(tempDir, config, entries, false, false, false);
                verify(mockEngine).close();
            }
        }

        @Test
        @DisplayName("closes engine after review (even on error)")
        void closesEngineAfterError() throws Exception {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> entries = List.of(
                    new DiffFileEntry("Test.java", "content", 10));

            ReviewEngine mockEngine = mock(ReviewEngine.class);
            when(mockEngine.review(anyList(), any(Path.class)))
                    .thenThrow(new LlmApiException(429, "rate limit"));

            try (MockedStatic<ReviewEngineFactory> factoryMock =
                         mockStatic(ReviewEngineFactory.class)) {

                factoryMock.when(() -> ReviewEngineFactory.resolveEngineType(any(), anyBoolean(), anyBoolean()))
                        .thenReturn(ReviewEngineFactory.EngineType.SIMPLE);
                factoryMock.when(() -> ReviewEngineFactory.create(
                                any(), any(), any(), any(), anyBoolean()))
                        .thenReturn(mockEngine);

                service.review(tempDir, config, entries, false, false, false);
                verify(mockEngine).close();
            }
        }
    }
}
