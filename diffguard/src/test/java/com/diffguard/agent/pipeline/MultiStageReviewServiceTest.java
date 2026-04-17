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
@DisplayName("MultiStageReviewService")
class MultiStageReviewServiceTest {

    @Mock DiffSummaryAgent mockSummaryAgent;
    @Mock SecurityReviewer mockSecurityReviewer;
    @Mock LogicReviewer mockLogicReviewer;
    @Mock QualityReviewer mockQualityReviewer;
    @Mock AggregationAgent mockAggregationAgent;

    @TempDir
    Path tempDir;

    private DiffFileEntry makeEntry(String path, String content) {
        return new DiffFileEntry(path, content, 100);
    }

    private DiffSummary makeSummary() {
        return new DiffSummary("变更总结", List.of("A.java"), List.of("refactor"), 2);
    }

    private TargetedReviewResult makeTargetedResult(String summary, int issueCount) {
        List<IssueRecord> issues = java.util.stream.IntStream.range(0, issueCount)
                .mapToObj(i -> new IssueRecord("WARNING", "A.java", i + 1, "问题", "描述" + i, "建议" + i))
                .toList();
        return new TargetedReviewResult(summary, issues);
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

    @Nested
    @DisplayName("完整 Pipeline 流程")
    class FullPipeline {

        @Test
        @DisplayName("三阶段正常完成：总结 → 并行审查 → 聚合")
        void fullPipelineSuccess() {
            when(mockSummaryAgent.summarize(anyString()))
                    .thenReturn(resultOf(makeSummary()));
            when(mockSecurityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("安全OK", 0)));
            when(mockLogicReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("逻辑OK", 1)));
            when(mockQualityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("质量OK", 0)));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(resultOf(makeAggregated(false, 1)));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(
                        List.of(makeEntry("A.java", "diff content")), tempDir);

                assertEquals(1, result.getIssues().size());
                assertEquals(1, result.getTotalFilesReviewed());
                assertFalse(result.hasCriticalIssues());
            }
        }

        @Test
        @DisplayName("CRITICAL 问题正确传播")
        void criticalIssuePropagation() {
            when(mockSummaryAgent.summarize(anyString()))
                    .thenReturn(resultOf(makeSummary()));
            when(mockSecurityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("安全问题", 1)));
            when(mockLogicReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("逻辑OK", 0)));
            when(mockQualityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("质量OK", 0)));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(resultOf(makeAggregated(true, 1)));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(
                        List.of(makeEntry("A.java", "diff content")), tempDir);

                assertTrue(result.hasCriticalIssues());
            }
        }
    }

    @Nested
    @DisplayName("阶段失败处理")
    class StageFailures {

        @Test
        @DisplayName("Stage 1 失败时使用简化摘要继续")
        void stage1FailureFallback() {
            when(mockSummaryAgent.summarize(anyString()))
                    .thenThrow(new RuntimeException("LLM 超时"));
            when(mockSecurityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("安全OK", 0)));
            when(mockLogicReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("逻辑OK", 0)));
            when(mockQualityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("质量OK", 0)));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(resultOf(makeAggregated(false, 0)));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(
                        List.of(makeEntry("A.java", "diff")), tempDir);

                assertNotNull(result);
                verify(mockAggregationAgent).aggregate(anyString(), anyString(), anyString(), anyString());
            }
        }

        @Test
        @DisplayName("单个 Stage 2 审查失败不影响其他")
        void stage2PartialFailure() {
            when(mockSummaryAgent.summarize(anyString()))
                    .thenReturn(resultOf(makeSummary()));
            when(mockSecurityReviewer.review(anyString(), anyString()))
                    .thenThrow(new RuntimeException("安全审查失败"));
            when(mockLogicReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("逻辑OK", 1)));
            when(mockQualityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("质量OK", 0)));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(resultOf(makeAggregated(false, 1)));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(
                        List.of(makeEntry("A.java", "diff")), tempDir);

                assertEquals(1, result.getIssues().size());
            }
        }

        @Test
        @DisplayName("Stage 3 聚合失败返回空结果")
        void stage3FailureFallback() {
            when(mockSummaryAgent.summarize(anyString()))
                    .thenReturn(resultOf(makeSummary()));
            when(mockSecurityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("安全OK", 0)));
            when(mockLogicReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("逻辑OK", 0)));
            when(mockQualityReviewer.review(anyString(), anyString()))
                    .thenReturn(resultOf(makeTargetedResult("质量OK", 0)));
            when(mockAggregationAgent.aggregate(anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("聚合失败"));

            try (MultiStageReviewService service = createService()) {
                ReviewResult result = service.review(
                        List.of(makeEntry("A.java", "diff")), tempDir);

                assertNotNull(result);
                assertEquals(0, result.getIssues().size());
            }
        }
    }

    @Nested
    @DisplayName("资源管理")
    class ResourceManagement {

        @Test
        @DisplayName("close() 正常关闭不抛异常")
        void closeNoException() {
            MultiStageReviewService service = createService();
            assertDoesNotThrow(service::close);
        }
    }
}
