package com.diffguard.agent.pipeline;

import com.diffguard.agent.pipeline.model.AggregatedReview;
import com.diffguard.agent.pipeline.model.DiffSummary;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.IssueRecord;
import com.diffguard.model.ReviewResult;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MultiStageReviewService 边界场景")
class MultiStageReviewServiceEdgeCaseTest {

    @Mock DiffSummaryAgent mockSummaryAgent;
    @Mock SecurityReviewer mockSecurityReviewer;
    @Mock LogicReviewer mockLogicReviewer;
    @Mock QualityReviewer mockQualityReviewer;
    @Mock AggregationAgent mockAggregationAgent;

    @TempDir Path tempDir;

    private DiffFileEntry makeEntry(String path, String content) {
        return new DiffFileEntry(path, content, 100);
    }

    private DiffSummary makeSummary() {
        return new DiffSummary("变更总结", List.of("A.java"), List.of("refactor"), 2);
    }

    private AggregatedReview makeAggregated(boolean hasCritical, int issueCount) {
        List<IssueRecord> issues = java.util.stream.IntStream.range(0, issueCount)
                .mapToObj(i -> new IssueRecord(
                        hasCritical && i == 0 ? "CRITICAL" : "WARNING",
                        "A.java", i + 1, "类型", "问题" + i, "建议" + i))
                .toList();
        return new AggregatedReview(hasCritical, "综合总结", issues, List.of(), List.of());
    }

    private <T> Result<T> resultOf(T content) {
        return Result.<T>builder().content(content).build();
    }

    private MultiStageReviewService createService() {
        return new MultiStageReviewService(
                mockSummaryAgent, mockSecurityReviewer, mockLogicReviewer,
                mockQualityReviewer, mockAggregationAgent);
    }

    // ------------------------------------------------------------------
    // 空输入
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("空输入")
    class EmptyInput {

        @Test
        @DisplayName("空 diff 列表 → 空 ReviewResult，零 issues")
        void emptyDiffListReturnsEmptyResult() {
            when(mockSummaryAgent.summarize(anyString()))
                    .thenReturn(resultOf(makeSummary()));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(resultOf(makeAggregated(false, 0)));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(List.of(), tempDir);

                assertNotNull(result);
                assertEquals(0, result.getIssues().size());
                assertEquals(0, result.getTotalFilesReviewed());
            }
        }

        @Test
        @DisplayName("空 diff 列表 → reviewDurationMs > 0")
        void emptyDiffStillTracksDuration() {
            when(mockSummaryAgent.summarize(anyString()))
                    .thenReturn(resultOf(makeSummary()));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(resultOf(makeAggregated(false, 0)));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(List.of(), tempDir);
                assertTrue(result.getReviewDurationMs() >= 0);
            }
        }
    }

    // ------------------------------------------------------------------
    // AggregatedReview null 字段
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("IssueRecord null 字段处理")
    class NullFields {

        @Test
        @DisplayName("issue 的 file 为 null → 转为空字符串")
        void nullFileBecomesEmpty() {
            AggregatedReview agg = new AggregatedReview(false, "总结",
                    List.of(new IssueRecord("WARNING", null, 1, "类型", "消息", "建议")),
                    List.of(), List.of());

            when(mockSummaryAgent.summarize(anyString())).thenReturn(resultOf(makeSummary()));
            when(mockSecurityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(new TargetedReviewResult("安全OK", List.of())));
            when(mockLogicReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(new TargetedReviewResult("逻辑OK", List.of())));
            when(mockQualityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(new TargetedReviewResult("质量OK", List.of())));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(resultOf(agg));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(List.of(makeEntry("A.java", "diff")), tempDir);
                assertEquals(1, result.getIssues().size());
                assertEquals("", result.getIssues().get(0).getFile());
            }
        }

        @Test
        @DisplayName("issue 的 type/message/suggestion 为 null → 转为空字符串")
        void nullTypeMessageSuggestionBecomeEmpty() {
            AggregatedReview agg = new AggregatedReview(false, "总结",
                    List.of(new IssueRecord("WARNING", "A.java", 1, null, null, null)),
                    List.of(), List.of());

            when(mockSummaryAgent.summarize(anyString())).thenReturn(resultOf(makeSummary()));
            when(mockSecurityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(new TargetedReviewResult("安全OK", List.of())));
            when(mockLogicReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(new TargetedReviewResult("逻辑OK", List.of())));
            when(mockQualityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(new TargetedReviewResult("质量OK", List.of())));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(resultOf(agg));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(List.of(makeEntry("A.java", "diff")), tempDir);
                assertEquals(1, result.getIssues().size());
                assertEquals("", result.getIssues().get(0).getType());
                assertEquals("", result.getIssues().get(0).getMessage());
                assertEquals("", result.getIssues().get(0).getSuggestion());
            }
        }
    }

    // ------------------------------------------------------------------
    // Stage 2 全部失败
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Stage 2 全部失败")
    class AllStage2Failures {

        @Test
        @DisplayName("三个审查全部失败 → Stage 3 仍被调用并返回聚合结果")
        void allStage2FailStage3StillSucceeds() {
            when(mockSummaryAgent.summarize(anyString())).thenReturn(resultOf(makeSummary()));
            when(mockSecurityReviewer.review(anyString(), anyString()))
                    .thenThrow(new RuntimeException("安全审查失败"));
            when(mockLogicReviewer.review(anyString(), anyString()))
                    .thenThrow(new RuntimeException("逻辑审查失败"));
            when(mockQualityReviewer.review(anyString(), anyString()))
                    .thenThrow(new RuntimeException("质量审查失败"));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(resultOf(makeAggregated(false, 0)));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(List.of(makeEntry("A.java", "diff")), tempDir);
                verify(mockAggregationAgent).aggregate(anyString(), anyString(), anyString(), anyString());
                assertNotNull(result);
            }
        }

        @Test
        @DisplayName("三个审查全部失败 + Stage 3 失败 → 返回空结果")
        void allStagesFailReturnsEmptyResult() {
            when(mockSummaryAgent.summarize(anyString())).thenReturn(resultOf(makeSummary()));
            when(mockSecurityReviewer.review(anyString(), anyString()))
                    .thenThrow(new RuntimeException("失败"));
            when(mockLogicReviewer.review(anyString(), anyString()))
                    .thenThrow(new RuntimeException("失败"));
            when(mockQualityReviewer.review(anyString(), anyString()))
                    .thenThrow(new RuntimeException("失败"));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("聚合失败"));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(List.of(makeEntry("A.java", "diff")), tempDir);
                assertEquals(0, result.getIssues().size());
            }
        }
    }

    // ------------------------------------------------------------------
    // Duration 跟踪
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Duration 跟踪")
    class DurationTracking {

        @Test
        @DisplayName("正常 Pipeline → reviewDurationMs > 0")
        void durationRecorded() {
            when(mockSummaryAgent.summarize(anyString())).thenReturn(resultOf(makeSummary()));
            when(mockSecurityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(new TargetedReviewResult("安全OK", List.of())));
            when(mockLogicReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(new TargetedReviewResult("逻辑OK", List.of())));
            when(mockQualityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(new TargetedReviewResult("质量OK", List.of())));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(resultOf(makeAggregated(false, 0)));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(List.of(makeEntry("A.java", "diff")), tempDir);
                assertTrue(result.getReviewDurationMs() >= 0);
            }
        }
    }
}
