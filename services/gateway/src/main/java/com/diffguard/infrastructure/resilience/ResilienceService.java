package com.diffguard.infrastructure.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 熔断 + 限流 + 重试 统一服务。
 * 包装 LLM 调用和 Python Agent 调用，提供弹性能力。
 */
public class ResilienceService {

    private static final Logger log = LoggerFactory.getLogger(ResilienceService.class);

    private final CircuitBreaker llmCircuitBreaker;
    private final CircuitBreaker agentCircuitBreaker;
    private final RateLimiter llmRateLimiter;
    private final Retry llmRetry;

    public ResilienceService() {
        // LLM 熔断: 50% 失败率开启, 30s 半开等待
        CircuitBreakerConfig llmCbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .build();

        // Agent 熔断: 60% 失败率开启
        CircuitBreakerConfig agentCbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(60)
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .permittedNumberOfCallsInHalfOpenState(2)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .build();

        this.llmCircuitBreaker = CircuitBreaker.of("llm", llmCbConfig);
        this.agentCircuitBreaker = CircuitBreaker.of("agent", agentCbConfig);

        // LLM 限流: 每秒 10 次 (令牌桶)
        RateLimiterConfig rlConfig = RateLimiterConfig.custom()
                .limitForPeriod(10)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(30))
                .build();
        this.llmRateLimiter = RateLimiter.of("llm", rlConfig);

        // LLM 重试: 3 次, 指数退避
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .retryOnException(e -> true)
                .build();
        this.llmRetry = Retry.of("llm", retryConfig);

        // 监听熔断状态变化
        llmCircuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("LLM circuit breaker: {} → {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
        agentCircuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Agent circuit breaker: {} → {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    /**
     * 包装 LLM 调用：熔断 + 限流 + 重试。
     */
    public <T> T executeLlmCall(Supplier<T> supplier) {
        Supplier<T> decorated = RateLimiter.decorateSupplier(llmRateLimiter, supplier);
        decorated = CircuitBreaker.decorateSupplier(llmCircuitBreaker, decorated);
        decorated = Retry.decorateSupplier(llmRetry, decorated);
        return decorated.get();
    }

    /**
     * 包装 Python Agent 调用：熔断。
     */
    public <T> T executeAgentCall(Supplier<T> supplier) {
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(agentCircuitBreaker, supplier);
        return decorated.get();
    }

    public CircuitBreaker getLlmCircuitBreaker() { return llmCircuitBreaker; }
    public CircuitBreaker getAgentCircuitBreaker() { return agentCircuitBreaker; }
    public RateLimiter getLlmRateLimiter() { return llmRateLimiter; }
}
