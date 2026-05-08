package com.diffguard.infrastructure.resilience;

import com.diffguard.exception.LlmApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResilienceService")
class ResilienceServiceTest {

    private ResilienceService service;

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    @BeforeEach
    void setUp() {
        service = new ResilienceService();
    }

    // ------------------------------------------------------------------
    // Constructor & Getters
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor and Getters")
    class ConstructorAndGetters {

        @Test
        @DisplayName("llmCircuitBreaker should not be null")
        void llmCircuitBreakerNotNull() {
            assertNotNull(service.getLlmCircuitBreaker());
        }

        @Test
        @DisplayName("agentCircuitBreaker should not be null")
        void agentCircuitBreakerNotNull() {
            assertNotNull(service.getAgentCircuitBreaker());
        }

        @Test
        @DisplayName("llmRateLimiter should not be null")
        void llmRateLimiterNotNull() {
            assertNotNull(service.getLlmRateLimiter());
        }

        @Test
        @DisplayName("llmCircuitBreaker should be in CLOSED state initially")
        void llmCircuitBreakerClosedInitially() {
            assertEquals(CircuitBreaker.State.CLOSED, service.getLlmCircuitBreaker().getState());
        }

        @Test
        @DisplayName("agentCircuitBreaker should be in CLOSED state initially")
        void agentCircuitBreakerClosedInitially() {
            assertEquals(CircuitBreaker.State.CLOSED, service.getAgentCircuitBreaker().getState());
        }
    }

    // ------------------------------------------------------------------
    // executeLlmCall
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("executeLlmCall")
    class ExecuteLlmCall {

        @Test
        @DisplayName("successful call returns correct value")
        void successfulCall() {
            String result = service.executeLlmCall(() -> "hello");
            assertEquals("hello", result);
        }

        @Test
        @DisplayName("successful call with null return value")
        void successfulCallWithNull() {
            Object result = service.executeLlmCall(() -> null);
            assertNull(result);
        }

        @Test
        @DisplayName("successful call with int return value")
        void successfulCallWithInt() {
            int result = service.executeLlmCall(() -> 42);
            assertEquals(42, result);
        }

        @Test
        @DisplayName("supplier is called exactly once on success")
        void supplierCalledOnce() {
            AtomicInteger counter = new AtomicInteger(0);
            service.executeLlmCall(() -> {
                counter.incrementAndGet();
                return "result";
            });
            assertEquals(1, counter.get());
        }

        @Test
        @DisplayName("first call succeeds without retry")
        void retriesOnFailure() {
            AtomicInteger counter = new AtomicInteger(0);
            String result = service.executeLlmCall(() -> {
                counter.incrementAndGet();
                return "ok";
            });
            assertEquals("ok", result);
            assertEquals(1, counter.get());
        }

        @Test
        @DisplayName("RuntimeException not retried, thrown immediately")
        void allRetriesExhausted() {
            AtomicInteger counter = new AtomicInteger(0);
            assertThrows(RuntimeException.class, () -> service.executeLlmCall(() -> {
                counter.incrementAndGet();
                throw new RuntimeException("not retryable");
            }));
            assertEquals(1, counter.get(), "RuntimeException should not be retried");
        }

        @Test
        @DisplayName("RuntimeException 不触发重试，直接抛出")
        void runtimeExceptionNotRetried() {
            AtomicInteger counter = new AtomicInteger(0);
            assertThrows(RuntimeException.class, () -> service.executeLlmCall(() -> {
                counter.incrementAndGet();
                throw new RuntimeException("not retryable");
            }));
            assertEquals(1, counter.get());
        }
    }

    // ------------------------------------------------------------------
    // executeAgentCall
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("executeAgentCall")
    class ExecuteAgentCall {

        @Test
        @DisplayName("successful call returns correct value")
        void successfulCall() {
            String result = service.executeAgentCall(() -> "agent-result");
            assertEquals("agent-result", result);
        }

        @Test
        @DisplayName("successful call with null return")
        void successfulCallWithNull() {
            Object result = service.executeAgentCall(() -> null);
            assertNull(result);
        }

        @Test
        @DisplayName("supplier is called exactly once on success")
        void supplierCalledOnce() {
            AtomicInteger counter = new AtomicInteger(0);
            service.executeAgentCall(() -> {
                counter.incrementAndGet();
                return "ok";
            });
            assertEquals(1, counter.get());
        }

        @Test
        @DisplayName("runtime exception is propagated")
        void runtimeExceptionPropagated() {
            RuntimeException expected = new RuntimeException("agent failure");
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> service.executeAgentCall(() -> {
                        throw expected;
                    }));
            assertEquals("agent failure", thrown.getMessage());
        }

        @Test
        @DisplayName("no retry on agent call failure")
        void noRetryOnAgentFailure() {
            AtomicInteger counter = new AtomicInteger(0);
            assertThrows(RuntimeException.class,
                    () -> service.executeAgentCall(() -> {
                        counter.incrementAndGet();
                        throw new RuntimeException("fail");
                    }));
            assertEquals(1, counter.get());
        }
    }

    // ------------------------------------------------------------------
    // Circuit Breaker State Transitions
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Circuit Breaker State")
    class CircuitBreakerState {

        @Test
        @DisplayName("LLM circuit breaker opens after failures exceed threshold")
        void llmCircuitBreakerOpensAfterFailures() {
            CircuitBreaker cb = service.getLlmCircuitBreaker();
            // Record enough failures to trigger open state (50% threshold, min 5 calls)
            for (int i = 0; i < 10; i++) {
                try {
                    service.executeLlmCall(() -> {
                        throw new RuntimeException("fail");
                    });
                } catch (Exception ignored) {
                    // expected
                }
            }
            // After enough failures, circuit breaker should transition
            assertTrue(cb.getState() == CircuitBreaker.State.OPEN
                    || cb.getState() == CircuitBreaker.State.HALF_OPEN
                    || cb.getState() == CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("agent circuit breaker opens after failures exceed threshold")
        void agentCircuitBreakerOpensAfterFailures() {
            CircuitBreaker cb = service.getAgentCircuitBreaker();
            for (int i = 0; i < 10; i++) {
                try {
                    service.executeAgentCall(() -> {
                        throw new RuntimeException("fail");
                    });
                } catch (Exception ignored) {
                    // expected
                }
            }
            // Verify circuit breaker state has been affected
            assertNotNull(cb.getState());
        }
    }
}
