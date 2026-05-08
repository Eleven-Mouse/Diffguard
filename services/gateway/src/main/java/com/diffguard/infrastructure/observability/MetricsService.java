package com.diffguard.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 指标服务：Review 任务计数、延迟、LLM Token 消耗等。
 * 通过 Prometheus 暴露 /metrics 端点。
 */
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private final PrometheusMeterRegistry registry;

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
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        this.reviewTotal = Counter.builder("diffguard_review_total")
                .description("Total review tasks submitted")
                .register(registry);

        this.reviewSuccess = Counter.builder("diffguard_review_success")
                .description("Successfully completed reviews")
                .register(registry);

        this.reviewFailed = Counter.builder("diffguard_review_failed")
                .description("Failed reviews")
                .register(registry);

        this.issuesFound = Counter.builder("diffguard_issues_total")
                .description("Total issues found across all reviews")
                .register(registry);

        this.criticalIssuesFound = Counter.builder("diffguard_issues_critical")
                .description("Critical severity issues found")
                .register(registry);

        this.llmTokensUsed = Counter.builder("diffguard_llm_tokens")
                .description("Total LLM tokens consumed")
                .register(registry);

        this.staticRuleHits = Counter.builder("diffguard_rules_static_hits")
                .description("Issues found by static rules (zero LLM cost)")
                .register(registry);

        this.reviewDuration = Timer.builder("diffguard_review_duration")
                .description("Review execution duration")
                .register(registry);

        this.llmCallDuration = Timer.builder("diffguard_llm_call_duration")
                .description("Single LLM API call duration")
                .register(registry);

        log.info("MetricsService initialized (PrometheusMeterRegistry)");
    }

    public MeterRegistry getRegistry() { return registry; }

    /** 返回 Prometheus 格式指标文本（给 /metrics 端点用） */
    public String scrape() {
        return registry.scrape();
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
