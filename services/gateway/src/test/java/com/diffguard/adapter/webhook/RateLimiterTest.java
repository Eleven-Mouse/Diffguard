package com.diffguard.adapter.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RateLimiter")
class RateLimiterTest {

    @Nested
    @DisplayName("正常请求")
    class NormalRequests {

        @Test
        @DisplayName("未超限的请求被允许")
        void underLimitAllowed() {
            RateLimiter limiter = new RateLimiter(5, Duration.ofMinutes(1));
            for (int i = 0; i < 5; i++) {
                assertTrue(limiter.allowRequest("192.168.1.1"));
            }
        }

        @Test
        @DisplayName("单个请求始终被允许")
        void singleRequestAlwaysAllowed() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1));
            assertTrue(limiter.allowRequest("10.0.0.1"));
        }
    }

    @Nested
    @DisplayName("频率限制触发")
    class RateLimitExceeded {

        @Test
        @DisplayName("超过限制后请求被拒绝")
        void overLimitRejected() {
            RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(1));
            assertTrue(limiter.allowRequest("10.0.0.1"));
            assertTrue(limiter.allowRequest("10.0.0.1"));
            assertTrue(limiter.allowRequest("10.0.0.1"));
            assertFalse(limiter.allowRequest("10.0.0.1"));
        }

        @Test
        @DisplayName("限制为 1 时第二次请求被拒绝")
        void limitOneRejectsSecond() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1));
            assertTrue(limiter.allowRequest("10.0.0.1"));
            assertFalse(limiter.allowRequest("10.0.0.1"));
        }
    }

    @Nested
    @DisplayName("IP 隔离")
    class IpIsolation {

        @Test
        @DisplayName("不同 IP 独立计数")
        void differentIpsIndependent() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1));
            assertTrue(limiter.allowRequest("10.0.0.1"));
            assertTrue(limiter.allowRequest("10.0.0.2"));
            assertFalse(limiter.allowRequest("10.0.0.1"));
            assertFalse(limiter.allowRequest("10.0.0.2"));
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("null IP 被当作 unknown 处理")
        void nullIpTreatedAsUnknown() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1));
            assertTrue(limiter.allowRequest(null));
            assertFalse(limiter.allowRequest(null));
        }

        @Test
        @DisplayName("空白 IP 被当作 unknown 处理")
        void blankIpTreatedAsUnknown() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1));
            assertTrue(limiter.allowRequest("  "));
            assertFalse(limiter.allowRequest("  "));
        }

        @Test
        @DisplayName("getter 返回正确配置")
        void gettersReturnConfig() {
            RateLimiter limiter = new RateLimiter(30, Duration.ofMinutes(1));
            assertEquals(30, limiter.getMaxRequests());
            assertEquals(Duration.ofMinutes(1), limiter.getWindow());
        }
    }
}
