package com.diffguard.infrastructure.llm;

import com.diffguard.exception.LlmApiException;
import com.diffguard.infrastructure.llm.provider.ProviderUtils;
import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.infrastructure.output.ProgressDisplay;
import com.diffguard.infrastructure.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 批量审查并行执行器。
 * <p>
 * 管理并发线程池和信号量，对多个 Prompt 并行调用 LLM，
 * 合并结果到统一的 ReviewResult。
 */
public class BatchReviewExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BatchReviewExecutor.class);

    private static final long BATCH_TIMEOUT_SECONDS = 600;

    private final ExecutorService executor;
    private final Semaphore concurrencyLimiter;

    public BatchReviewExecutor(int maxConcurrency) {
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
        this.concurrencyLimiter = new Semaphore(maxConcurrency);
    }

    /**
     * 并行执行多个 Prompt 的审查。
     *
     * @param prompts           待审查的 Prompt 列表
     * @param singlePromptRunner 单个 Prompt 的执行回调（包含重试逻辑）
     * @return 合并后的审查结果
     * @throws LlmApiException 所有批次均失败时抛出
     */
    public ReviewResult executeBatch(List<PromptBuilder.PromptContent> prompts,
                                      Function<PromptBuilder.PromptContent, LlmResponse> singlePromptRunner) throws LlmApiException {
        ReviewResult result = new ReviewResult();

        if (prompts.isEmpty()) {
            return result;
        }

        List<Future<LlmResponse>> futures = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    concurrencyLimiter.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LlmApiException("获取并发许可被中断", e);
                }
                try {
                    ProgressDisplay.printBatchProgress(idx + 1, prompts.size());
                    return singlePromptRunner.apply(prompts.get(idx));
                } finally {
                    concurrencyLimiter.release();
                }
            }));
        }

        int failedBatches = 0;
        LlmApiException firstFatalError = null;
        long batchStartTime = System.currentTimeMillis();
        for (int i = 0; i < futures.size(); i++) {
            try {
                if (i > 0) {
                    long elapsed = (System.currentTimeMillis() - batchStartTime) / 1000;
                    log.info("批次进度：{}/{}/{}，已耗时 {}s", i + 1, futures.size(), failedBatches, elapsed);
                }
                LlmResponse response = futures.get(i).get(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                mergeResponse(response, result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelRemainingFutures(futures);
                throw new LlmApiException("并行审查被中断", e);
            } catch (ExecutionException e) {
                failedBatches++;
                Throwable cause = e.getCause();
                LlmApiException extracted = extractLlmApiException(cause);
                String errorMsg = extracted != null ? extracted.getMessage()
                        : (cause != null ? cause.getMessage() : "未知错误");
                log.warn("批次 {}/{} 审查失败：{}", i + 1, futures.size(), errorMsg);

                // 熔断：quota/billing 错误不可恢复，取消剩余批次
                if (ProviderUtils.isQuotaError(e)) {
                    firstFatalError = extracted != null ? extracted
                            : new LlmApiException("API quota 错误：" + errorMsg);
                    log.error("检测到 quota/billing 错误，取消剩余 {} 个批次", futures.size() - i - 1);
                    cancelRemainingFutures(futures);
                    failedBatches = futures.size();
                    break;
                }
                if (firstFatalError == null && extracted != null) {
                    firstFatalError = extracted;
                }
            } catch (TimeoutException e) {
                failedBatches++;
                log.warn("批次 {}/{} 审查超时（{}秒），跳过该批次", i + 1, futures.size(), BATCH_TIMEOUT_SECONDS);
            }
        }

        if (failedBatches == futures.size()) {
            if (firstFatalError != null) {
                throw new LlmApiException("所有批次审查均失败（" + firstFatalError.getMessage() + "）", firstFatalError);
            }
            throw new LlmApiException("所有批次审查均失败");
        }
        if (failedBatches > 0) {
            log.warn("{}/{} 个批次审查失败，返回部分结果", failedBatches, futures.size());
        }

        return result;
    }

    void mergeResponse(LlmResponse response, ReviewResult result) {
        if (response.isRawText()) {
            String existing = result.getRawReport();
            result.setRawReport(existing == null ? response.getRawText()
                    : existing + "\n\n" + response.getRawText());
        } else {
            for (ReviewIssue issue : response.getIssues()) {
                result.addIssue(issue);
            }
            if (Boolean.TRUE.equals(response.getHasCritical())
                    || response.getIssues().stream().anyMatch(i -> i.getSeverity().shouldBlockCommit())) {
                result.setHasCriticalFlag(true);
            }
        }
        result.setTotalFilesReviewed(result.getTotalFilesReviewed() + 1);
    }

    private static LlmApiException extractLlmApiException(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof LlmApiException lae) return lae;
            current = current.getCause();
        }
        return null;
    }

    private void cancelRemainingFutures(List<Future<LlmResponse>> futures) {
        for (Future<LlmResponse> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
