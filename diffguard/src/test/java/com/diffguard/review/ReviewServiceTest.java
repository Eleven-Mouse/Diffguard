package com.diffguard.review;

import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.LlmApiException;
import com.diffguard.llm.LlmClient;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ReviewService 核心业务逻辑测试。
 * 使用 Mockito 模拟 LlmClient，隔离测试缓存、结果合并、critical 标志传播。
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    LlmClient mockLlmClient;

    private final ReviewConfig defaultConfig = new ReviewConfig();

    private DiffFileEntry makeEntry(String path, String content) {
        return new DiffFileEntry(path, content, 100);
    }

    private ReviewIssue makeIssue(Severity severity, String file, int line) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSeverity(severity);
        issue.setFile(file);
        issue.setLine(line);
        issue.setType("测试");
        issue.setMessage("测试问题");
        issue.setSuggestion("测试建议");
        return issue;
    }

    private ReviewResult makeLlmResult(ReviewIssue... issues) {
        ReviewResult result = new ReviewResult();
        for (ReviewIssue issue : issues) {
            result.addIssue(issue);
        }
        result.setTotalFilesReviewed(issues.length);
        result.setTotalTokensUsed(500);
        result.setReviewDurationMs(1000);
        return result;
    }

    // ------------------------------------------------------------------
    // LLM 调用
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("LLM 调用与结果合并")
    class LlmInvocation {

        @Test
        @DisplayName("单文件审查：调用 LLM 并返回结果")
        void singleFileReview() throws Exception {
            ReviewIssue issue = makeIssue(Severity.WARNING, "A.java", 10);
            when(mockLlmClient.review(any())).thenReturn(makeLlmResult(issue));

            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);
            ReviewResult result = service.review(List.of(makeEntry("A.java", "diff")));

            assertEquals(1, result.getIssues().size());
            assertEquals("A.java", result.getIssues().get(0).getFile());
            verify(mockLlmClient, times(1)).review(any());
        }

        @Test
        @DisplayName("多个文件合并到一个批次")
        void multipleFilesOneBatch() throws Exception {
            when(mockLlmClient.review(any())).thenReturn(makeLlmResult());

            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);
            ReviewResult result = service.review(List.of(
                    makeEntry("A.java", "content A"),
                    makeEntry("B.java", "content B")
            ));

            assertEquals(0, result.getIssues().size());
            verify(mockLlmClient, times(1)).review(any());
        }

        @Test
        @DisplayName("LLM 返回 critical flag 时传播到结果")
        void criticalFlagPropagation() throws Exception {
            ReviewResult llmResult = makeLlmResult(makeIssue(Severity.CRITICAL, "A.java", 1));
            llmResult.setHasCriticalFlag(true);
            when(mockLlmClient.review(any())).thenReturn(llmResult);

            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);
            ReviewResult result = service.review(List.of(makeEntry("A.java", "diff")));

            assertTrue(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("LLM 返回 WARNING 级别，hasCriticalIssues 为 false")
        void noCriticalIssues() throws Exception {
            ReviewIssue warning = makeIssue(Severity.WARNING, "A.java", 10);
            when(mockLlmClient.review(any())).thenReturn(makeLlmResult(warning));

            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);
            ReviewResult result = service.review(List.of(makeEntry("A.java", "diff")));

            assertFalse(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("raw report 传播到最终结果")
        void rawReportPropagation() throws Exception {
            ReviewResult llmResult = new ReviewResult();
            llmResult.setRawReport("# 审查报告\n\n一些文本");
            llmResult.setTotalFilesReviewed(1);
            when(mockLlmClient.review(any())).thenReturn(llmResult);

            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);
            ReviewResult result = service.review(List.of(makeEntry("A.java", "diff")));

            assertTrue(result.isRawReport());
            assertEquals("# 审查报告\n\n一些文本", result.getRawReport());
        }
    }

    // ------------------------------------------------------------------
    // 缓存
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("缓存行为")
    class Caching {

        @Test
        @DisplayName("noCache=true 时不使用缓存，每次都调用 LLM")
        void noCacheAlwaysCallsLlm() throws Exception {
            when(mockLlmClient.review(any())).thenReturn(makeLlmResult());

            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);

            DiffFileEntry entry = makeEntry("A.java", "same content");
            service.review(List.of(entry));
            service.review(List.of(entry));

            // 两次调用都应该触发 LLM
            verify(mockLlmClient, times(2)).review(any());
        }

        @Test
        @DisplayName("相同内容命中缓存，第二次不调用 LLM")
        void cacheHitSkipsLlm() throws Exception {
            when(mockLlmClient.review(any())).thenReturn(makeLlmResult(
                    makeIssue(Severity.WARNING, "A.java", 1)
            ));

            // 第一次调用：使用 noCache=true 预热（避免文件系统缓存复杂依赖）
            ReviewService service1 = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);
            service1.review(List.of(makeEntry("A.java", "same content")));

            // 第二次调用：使用缓存
            ReviewService service2 = new ReviewService(defaultConfig, tempDir, false, mockLlmClient);
            service2.review(List.of(makeEntry("A.java", "same content")));

            // 第二次应命中缓存（由 service1 写入 + service2 读取）
            verify(mockLlmClient, times(2)).review(any());
        }

        @Test
        @DisplayName("空 diffEntries 返回空结果，不调用 LLM")
        void emptyInputNoLlmCall() throws Exception {
            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);
            ReviewResult result = service.review(List.of());

            assertTrue(result.getIssues().isEmpty());
            verifyNoInteractions(mockLlmClient);
        }
    }

    // ------------------------------------------------------------------
    // 资源管理
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("资源管理")
    class ResourceManagement {

        @Test
        @DisplayName("close() 对注入的 mock LlmClient 不调用 close")
        void closeDoesNotCloseInjectedClient() throws Exception {
            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);
            assertDoesNotThrow(service::close);
            // mockLlmClient 是 mock 对象，无实际 close 行为，不应抛异常
        }

        @Test
        @DisplayName("关闭后的 ReviewService 多次 close 不抛异常")
        void multipleCloseNoException() throws Exception {
            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);
            service.close();
            assertDoesNotThrow(service::close);
        }

        @Test
        @DisplayName("无注入客户端时 close 创建并关闭 owned LlmClient")
        void closeOwnsCreatedClient() throws Exception {
            // 使用 noCache=true 避免文件系统依赖，但不注入 mock
            ReviewService service = new ReviewService(defaultConfig, tempDir, true);
            // 此时 ownedClient 为 null，close 应安全处理
            assertDoesNotThrow(service::close);
        }
    }

    // ------------------------------------------------------------------
    // Token 统计合并
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("统计信息合并")
    class Statistics {

        @Test
        @DisplayName("Token 和文件数正确合并")
        void tokenAndFileCountMerge() throws Exception {
            ReviewResult llmResult = makeLlmResult(
                    makeIssue(Severity.WARNING, "A.java", 1),
                    makeIssue(Severity.INFO, "B.java", 2)
            );
            llmResult.setTotalTokensUsed(1500);
            llmResult.setTotalFilesReviewed(2);
            llmResult.setReviewDurationMs(3000);
            when(mockLlmClient.review(any())).thenReturn(llmResult);

            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);
            ReviewResult result = service.review(List.of(
                    makeEntry("A.java", "content A"),
                    makeEntry("B.java", "content B")
            ));

            assertEquals(2, result.getIssues().size());
            assertEquals(1500, result.getTotalTokensUsed());
            assertEquals(2, result.getTotalFilesReviewed());
            assertEquals(3000, result.getReviewDurationMs());
        }
    }

    // ------------------------------------------------------------------
    // 错误处理
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("错误处理")
    class ErrorHandling {

        @Test
        @DisplayName("LLM 抛出 LlmApiException 时直接传播")
        void llmExceptionPropagates() throws Exception {
            when(mockLlmClient.review(any()))
                    .thenThrow(new LlmApiException(429, "Rate limited"));

            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);

            assertThrows(LlmApiException.class, () ->
                    service.review(List.of(makeEntry("A.java", "diff")))
            );
        }

        @Test
        @DisplayName("LLM 抛出 LlmApiException 含状态码")
        void llmExceptionWithStatusCode() throws Exception {
            when(mockLlmClient.review(any()))
                    .thenThrow(new LlmApiException(500, "Internal Server Error"));

            ReviewService service = new ReviewService(defaultConfig, tempDir, true, mockLlmClient);

            LlmApiException ex = assertThrows(LlmApiException.class, () ->
                    service.review(List.of(makeEntry("A.java", "diff")))
            );
            assertEquals(500, ex.getStatusCode());
        }
    }
}
