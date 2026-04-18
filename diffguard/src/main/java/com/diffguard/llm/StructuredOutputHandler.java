package com.diffguard.llm;


import com.diffguard.model.IssueRecord;
import com.diffguard.model.ReviewOutput;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.Severity;
import com.diffguard.prompt.PromptBuilder;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AiServices 结构化输出处理器。
 * <p>
 * 封装 LangChain4j AiServices 的结构化输出调用逻辑：
 * 优先使用带 Tool Use 的服务，失败时降级到无工具服务。
 */
public class StructuredOutputHandler {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputHandler.class);

    private static final long SERVER_ERROR_BASE_DELAY_MS = 5_000;

    private final StructuredReviewService structuredService;
    private final StructuredReviewService structuredServiceNoTools;
    private final AtomicInteger totalTokensUsed;

    public StructuredOutputHandler(StructuredReviewService structuredService,
                                   StructuredReviewService structuredServiceNoTools,
                                   AtomicInteger totalTokensUsed) {
        this.structuredService = structuredService;
        this.structuredServiceNoTools = structuredServiceNoTools;
        this.totalTokensUsed = totalTokensUsed;
    }

    /**
     * 尝试通过 AiServices 获取结构化输出。
     * <p>
     * 调用链：带工具服务 → 降级无工具服务 → 返回 null（由调用方 fallback）
     *
     * @return 结构化 LlmResponse，或 null 表示需要 fallback 到手动解析
     */
    public LlmResponse tryStructuredOutput(PromptBuilder.PromptContent prompt) {
        String language = prompt.getLanguage();
        String rules = prompt.getRules();
        String filePath = prompt.getFilePath();
        String diffContent = prompt.getDiffContent();

        // Phase 1: 带 Tool Use 的结构化输出（可能多轮 HTTP 往返）
        try {
            LlmResponse result = doStructuredCall(structuredService, language, rules, filePath, diffContent);
            if (result != null) {
                return result;
            }
            log.warn("AiServices Tool Use 返回 null content，尝试无工具模式");
        } catch (Exception e) {
            if (isRetriableServerError(e) && structuredServiceNoTools != null && structuredServiceNoTools != structuredService) {
                log.warn("Tool Use 路径 {}，降级为无工具结构化输出重试", e.getMessage());
                sleepQuietly(SERVER_ERROR_BASE_DELAY_MS);

                // Phase 2: 降级为无工具结构化输出（单轮 HTTP）
                try {
                    LlmResponse result = doStructuredCall(structuredServiceNoTools, language, rules, filePath, diffContent);
                    if (result != null) {
                        return result;
                    }
                    log.warn("无工具结构化输出返回 null content，回退到手动解析");
                } catch (Exception fallbackEx) {
                    log.warn("无工具结构化输出也失败：{}，回退到手动解析", fallbackEx.getMessage());
                }
                return null;
            }
            log.debug("AiServices 结构化输出失败（不可重试）：{}，回退到手动解析", e.getMessage());
        }
        return null;
    }

    StructuredReviewService getStructuredServiceNoTools() {
        return structuredServiceNoTools;
    }

    private LlmResponse doStructuredCall(StructuredReviewService service,
                                          String language, String rules,
                                          String filePath, String diffContent) {
        log.debug("AiServices 调用: diffContent长度={}, filePath={}", diffContent.length(), filePath);
        Result<ReviewOutput> result = service.review(language, rules, filePath, diffContent);

        if (result.tokenUsage() != null) {
            long tokens = result.tokenUsage().inputTokenCount() + result.tokenUsage().outputTokenCount();
            if (tokens > 0) {
                totalTokensUsed.addAndGet((int) tokens);
            }
        }

        ReviewOutput output = result.content();
        if (output == null) {
            String rawText = result.finalResponse() != null && result.finalResponse().aiMessage() != null
                    ? result.finalResponse().aiMessage().text() : null;
            long tokens = result.tokenUsage() != null
                    ? result.tokenUsage().inputTokenCount() + result.tokenUsage().outputTokenCount() : -1;
            String model = result.finalResponse() != null ? result.finalResponse().modelName() : "unknown";
            log.warn("AiServices 返回 null content (model={}, tokens={}, rawText={})",
                    model, tokens, rawText != null ? rawText.length() + "chars" : "null");
            if (rawText != null) {
                return LlmResponse.fromContent(rawText);
            }
            return null;
        }

        List<ReviewIssue> issues = new ArrayList<>();
        if (output.issues() != null) {
            for (IssueRecord ir : output.issues()) {
                ReviewIssue issue = new ReviewIssue();
                issue.setSeverity(Severity.fromString(ir.severity()));
                issue.setFile(ir.file() != null ? ir.file() : "");
                issue.setLine(ir.line());
                issue.setType(ir.type() != null ? ir.type() : "");
                issue.setMessage(ir.message() != null ? ir.message() : "");
                issue.setSuggestion(ir.suggestion() != null ? ir.suggestion() : "");
                issues.add(issue);
            }
        }

        return new LlmResponse(issues, null, Boolean.TRUE.equals(output.has_critical()));
    }

    static boolean isRetriableServerError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.matches(".*\\b(502|503|504|429)\\b.*")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
