package com.diffguard.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 指标服务：Review 任务计数、延迟、LLM Token 消耗等。
 * 通过 Prometheus 暴露 /metrics 端点。
 */
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private final SimpleMeterRegistry registry;

    private final Counter reviewTotal;
    private final Counter reviewSuccess;
    private final Counter reviewFailed;
    private final Counter issuesFound;
    private final Counter criticalIssuesFound;
    private final Counter llmTokensUsed;
    private final Counter staticRuleHits;
    private final Timer reviewDuration;
    private final Timer llmCallDuration;

    public MetricsService() {
        this.registry = new SimpleMeterRegistry();

        this.reviewTotal = Counter.builder("diffguard.review.total")
                .description("Total review tasks submitted")
                .register(registry);

        this.reviewSuccess = Counter.builder("diffguard.review.success")
                .description("Successfully completed reviews")
                .register(registry);

        this.reviewFailed = Counter.builder("diffguard.review.failed")
                .description("Failed reviews")
                .register(registry);

        this.issuesFound = Counter.builder("diffguard.issues.total")
                .description("Total issues found across all reviews")
                .register(registry);

        this.criticalIssuesFound = Counter.builder("diffguard.issues.critical")
                .description("Critical severity issues found")
                .register(registry);

        this.llmTokensUsed = Counter.builder("diffguard.llm.tokens")
                .description("Total LLM tokens consumed")
                .register(registry);

        this.staticRuleHits = Counter.builder("diffguard.rules.static.hits")
                .description("Issues found by static rules (zero LLM cost)")
                .register(registry);

        this.reviewDuration = Timer.builder("diffguard.review.duration")
                .description("Review execution duration")
                .register(registry);

        this.llmCallDuration = Timer.builder("diffguard.llm.call.duration")
                .description("Single LLM API call duration")
                .register(registry);

        log.info("MetricsService initialized (SimpleMeterRegistry)");
    }

    public MeterRegistry getRegistry() { return registry; }

    /** 返回指标摘要文本（给 /metrics 端点用） */
    public String scrape() {
        StringBuilder sb = new StringBuilder();
        registry.getMeters().forEach(m -> sb.append(m.getId().getName()).append(" ").append(m.measure()).append("\n"));
        return sb.toString();
    }

    public void recordReviewSubmitted() { reviewTotal.increment(); }
    public void recordReviewSuccess() { reviewSuccess.increment(); }
    public void recordReviewFailed() { reviewFailed.increment(); }
    public void recordIssues(int count) { issuesFound.increment(count); }
    public void recordCriticalIssue() { criticalIssuesFound.increment(); }
    public void recordTokensUsed(int tokens) { llmTokensUsed.increment(tokens); }
    public void recordStaticHit() { staticRuleHits.increment(); }

    public Timer.Sample startReviewTimer() { return Timer.start(registry); }
    public void stopReviewTimer(Timer.Sample sample) { sample.stop(reviewDuration); }

    public Timer.Sample startLlmTimer() { return Timer.start(registry); }
    public void stopLlmTimer(Timer.Sample sample) { sample.stop(llmCallDuration); }
}
