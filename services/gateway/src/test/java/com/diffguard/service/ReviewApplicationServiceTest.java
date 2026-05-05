package com.diffguard.service;

import com.diffguard.domain.review.ReviewEngine;
import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.domain.review.model.Severity;
import com.diffguard.exception.ConfigException;
import com.diffguard.exception.DiffCollectionException;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.exception.LlmApiException;
import com.diffguard.infrastructure.config.ReviewConfig;
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
 * Uses MockedStatic for ConfigLoader, DiffCollector, ASTEnricher,
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
            try (MockedStatic<com.diffguard.infrastructure.config.ConfigLoader> configLoaderMock =
                         mockStatic(com.diffguard.infrastructure.config.ConfigLoader.class)) {

                configLoaderMock.when(() ->
                        com.diffguard.infrastructure.config.ConfigLoader.load(any(Path.class)))
                        .thenThrow(new ConfigException("bad config"));

                ReviewConfig result = service.loadConfig(tempDir, null);
                assertNull(result);
            }
        }

        @Test
        @DisplayName("returns null when ConfigLoader.loadFromFile throws ConfigException")
        void returnsNullOnLoadFromFileException() {
            Path configPath = tempDir.resolve("config.yml");
            try (MockedStatic<com.diffguard.infrastructure.config.ConfigLoader> configLoaderMock =
                         mockStatic(com.diffguard.infrastructure.config.ConfigLoader.class)) {

                configLoaderMock.when(() ->
                        com.diffguard.infrastructure.config.ConfigLoader.loadFromFile(any(Path.class)))
                        .thenThrow(new ConfigException("file not found"));

                ReviewConfig result = service.loadConfig(tempDir, configPath);
                assertNull(result);
            }
        }

        @Test
        @DisplayName("returns config from ConfigLoader.load when configPath is null")
        void returnsConfigFromLoad() {
            ReviewConfig expected = new ReviewConfig();
            try (MockedStatic<com.diffguard.infrastructure.config.ConfigLoader> configLoaderMock =
                         mockStatic(com.diffguard.infrastructure.config.ConfigLoader.class)) {

                configLoaderMock.when(() ->
                        com.diffguard.infrastructure.config.ConfigLoader.load(tempDir))
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
            try (MockedStatic<com.diffguard.infrastructure.config.ConfigLoader> configLoaderMock =
                         mockStatic(com.diffguard.infrastructure.config.ConfigLoader.class)) {

                configLoaderMock.when(() ->
                        com.diffguard.infrastructure.config.ConfigLoader.loadFromFile(configPath))
                        .thenReturn(expected);

                ReviewConfig result = service.loadConfig(tempDir, configPath);
                assertSame(expected, result);
                configLoaderMock.verify(() ->
                        com.diffguard.infrastructure.config.ConfigLoader.loadFromFile(configPath));
                configLoaderMock.verify(() ->
                        com.diffguard.infrastructure.config.ConfigLoader.load(any()), never());
            }
        }

        @Test
        @DisplayName("returns null when IllegalArgumentException is thrown")
        void returnsNullOnIllegalArgument() {
            try (MockedStatic<com.diffguard.infrastructure.config.ConfigLoader> configLoaderMock =
                         mockStatic(com.diffguard.infrastructure.config.ConfigLoader.class)) {

                configLoaderMock.when(() ->
                        com.diffguard.infrastructure.config.ConfigLoader.load(any(Path.class)))
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
            try (MockedStatic<com.diffguard.infrastructure.git.DiffCollector> diffCollectorMock =
                         mockStatic(com.diffguard.infrastructure.git.DiffCollector.class)) {

                diffCollectorMock.when(() ->
                        com.diffguard.infrastructure.git.DiffCollector.collectStagedDiff(any(), any()))
                        .thenThrow(new DiffCollectionException("git error"));

                List<DiffFileEntry> result = service.collectAndEnrich(
                        tempDir, config, true, null, null);
                assertNull(result);
            }
        }

        @Test
        @DisplayName("returns empty list when no diff entries")
        void returnsEmptyListWhenNoDiff() {
            ReviewConfig config = new ReviewConfig();
            try (MockedStatic<com.diffguard.infrastructure.git.DiffCollector> diffCollectorMock =
                         mockStatic(com.diffguard.infrastructure.git.DiffCollector.class)) {

                diffCollectorMock.when(() ->
                        com.diffguard.infrastructure.git.DiffCollector.collectStagedDiff(any(), any()))
                        .thenReturn(Collections.emptyList());

                List<DiffFileEntry> result = service.collectAndEnrich(
                        tempDir, config, true, null, null);
                assertNotNull(result);
                assertTrue(result.isEmpty());
            }
        }

        @Test
        @DisplayName("returns enriched entries for staged diff")
        void returnsEnrichedEntriesForStaged() {
            ReviewConfig config = new ReviewConfig();
            DiffFileEntry rawEntry = new DiffFileEntry("Test.java", "diff content", 10);
            DiffFileEntry enrichedEntry = new DiffFileEntry("Test.java", "enriched content", 15);
            List<DiffFileEntry> rawEntries = List.of(rawEntry);
            List<DiffFileEntry> enrichedEntries = List.of(enrichedEntry);

            try (MockedStatic<com.diffguard.infrastructure.git.DiffCollector> diffCollectorMock =
                         mockStatic(com.diffguard.infrastructure.git.DiffCollector.class);
                 MockedStatic<com.diffguard.domain.ast.ASTEnricher> enricherMock =
                         mockStatic(com.diffguard.domain.ast.ASTEnricher.class)) {

                diffCollectorMock.when(() ->
                        com.diffguard.infrastructure.git.DiffCollector.collectStagedDiff(any(), any()))
                        .thenReturn(rawEntries);

                com.diffguard.domain.ast.ASTEnricher mockEnricher =
                        mock(com.diffguard.domain.ast.ASTEnricher.class);
                enricherMock.when(() -> new com.diffguard.domain.ast.ASTEnricher(any(), any()))
                        .thenReturn(mockEnricher);
                when(mockEnricher.enrich(rawEntries)).thenReturn(enrichedEntries);

                List<DiffFileEntry> result = service.collectAndEnrich(
                        tempDir, config, true, null, null);
                assertEquals(1, result.size());
                assertEquals("enriched content", result.get(0).getContent());
            }
        }

        @Test
        @DisplayName("uses collectDiffBetweenRefs when from/to refs provided")
        void usesCollectDiffBetweenRefs() {
            ReviewConfig config = new ReviewConfig();
            try (MockedStatic<com.diffguard.infrastructure.git.DiffCollector> diffCollectorMock =
                         mockStatic(com.diffguard.infrastructure.git.DiffCollector.class)) {

                diffCollectorMock.when(() ->
                        com.diffguard.infrastructure.git.DiffCollector.collectDiffBetweenRefs(
                                any(), eq("main"), eq("feature"), any()))
                        .thenReturn(Collections.emptyList());

                List<DiffFileEntry> result = service.collectAndEnrich(
                        tempDir, config, false, "main", "feature");
                assertNotNull(result);
                assertTrue(result.isEmpty());

                diffCollectorMock.verify(() ->
                        com.diffguard.infrastructure.git.DiffCollector.collectDiffBetweenRefs(
                                any(), eq("main"), eq("feature"), any()));
            }
        }

        @Test
        @DisplayName("returns null when neither staged nor from/to refs provided")
        void returnsNullWhenNoModeSpecified() {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> result = service.collectAndEnrich(
                    tempDir, config, false, null, null);
            assertNull(result);
        }

        @Test
        @DisplayName("returns null when only fromRef is provided without toRef")
        void returnsNullWhenOnlyFromRefProvided() {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> result = service.collectAndEnrich(
                    tempDir, config, false, "main", null);
            assertNull(result);
        }

        @Test
        @DisplayName("returns null when only toRef is provided without fromRef")
        void returnsNullWhenOnlyToRefProvided() {
            ReviewConfig config = new ReviewConfig();
            List<DiffFileEntry> result = service.collectAndEnrich(
                    tempDir, config, false, null, "feature");
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
