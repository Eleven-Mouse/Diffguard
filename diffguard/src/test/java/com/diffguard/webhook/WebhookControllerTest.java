package com.diffguard.webhook;

import com.diffguard.review.ReviewOrchestrator;
import io.javalin.http.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookController")
class WebhookControllerTest {

    @Mock SignatureVerifier mockVerifier;
    @Mock ReviewOrchestrator mockOrchestrator;
    @Mock Context mockContext;

    WebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new WebhookController(mockVerifier, mockOrchestrator);
        // Javalin Context.status() 返回 Context 自身用于链式调用
        lenient().when(mockContext.status(anyInt())).thenReturn(mockContext);
    }

    @Nested
    @DisplayName("签名验证")
    class SignatureValidation {

        @Test
        @DisplayName("无效签名返回 401")
        void invalidSignatureReturns401() {
            when(mockVerifier.verify(anyString(), anyString())).thenReturn(false);
            when(mockContext.body()).thenReturn("{}");
            when(mockContext.header("X-Hub-Signature-256")).thenReturn("sha256=abc123");
            when(mockContext.header("X-GitHub-Event")).thenReturn("push");

            controller.handleWebhook(mockContext);

            verify(mockContext).status(401);
            verify(mockContext).result("Invalid signature");
        }

        @Test
        @DisplayName("无签名头返回 401")
        void noSignatureReturns401() {
            when(mockVerifier.verify(anyString(), isNull())).thenReturn(false);
            when(mockContext.body()).thenReturn("{}");
            when(mockContext.header("X-Hub-Signature-256")).thenReturn(null);
            when(mockContext.header("X-GitHub-Event")).thenReturn("push");

            controller.handleWebhook(mockContext);

            verify(mockContext).status(401);
        }
    }

    @Nested
    @DisplayName("事件过滤")
    class EventFiltering {

        @Test
        @DisplayName("ping 事件返回 pong")
        void pingReturnsPong() {
            when(mockVerifier.verify(anyString(), anyString())).thenReturn(true);
            when(mockContext.body()).thenReturn("{}");
            when(mockContext.header("X-Hub-Signature-256")).thenReturn("sha256=abc");
            when(mockContext.header("X-GitHub-Event")).thenReturn("ping");

            controller.handleWebhook(mockContext);

            verify(mockContext).status(200);
            verify(mockContext).result("pong");
        }

        @Test
        @DisplayName("非 pull_request 事件被忽略")
        void nonPullRequestIgnored() {
            when(mockVerifier.verify(anyString(), anyString())).thenReturn(true);
            when(mockContext.body()).thenReturn("{}");
            when(mockContext.header("X-Hub-Signature-256")).thenReturn("sha256=abc");
            when(mockContext.header("X-GitHub-Event")).thenReturn("push");

            controller.handleWebhook(mockContext);

            verify(mockContext).status(200);
            verify(mockContext).result(startsWith("Ignored"));
        }
    }

    @Nested
    @DisplayName("PR 处理")
    class PullRequestProcessing {

        @Test
        @DisplayName("无效 payload 返回 400")
        void invalidPayloadReturns400() {
            when(mockVerifier.verify(anyString(), anyString())).thenReturn(true);
            when(mockContext.body()).thenReturn("{invalid json");
            when(mockContext.header("X-Hub-Signature-256")).thenReturn("sha256=abc");
            when(mockContext.header("X-GitHub-Event")).thenReturn("pull_request");

            controller.handleWebhook(mockContext);

            verify(mockContext).status(400);
        }

        @Test
        @DisplayName("无关 action（如 labeled）被忽略")
        void irrelevantActionIgnored() {
            when(mockVerifier.verify(anyString(), anyString())).thenReturn(true);
            when(mockContext.body()).thenReturn("""
                    {"action":"labeled","pull_request":{"number":1},"repository":{"full_name":"test/repo"}}
                    """);
            when(mockContext.header("X-Hub-Signature-256")).thenReturn("sha256=abc");
            when(mockContext.header("X-GitHub-Event")).thenReturn("pull_request");

            controller.handleWebhook(mockContext);

            verify(mockContext).status(200);
            verify(mockContext).result(startsWith("Ignored"));
        }

        @Test
        @DisplayName("有效的 opened PR 被接受并异步处理")
        void validOpenedPrAccepted() {
            when(mockVerifier.verify(anyString(), anyString())).thenReturn(true);
            when(mockContext.body()).thenReturn("""
                    {"action":"opened","pull_request":{"number":42},"repository":{"full_name":"test/repo"},"base":{"ref":"main"},"head":{"ref":"feature","sha":"abc123"}}
                    """);
            when(mockContext.header("X-Hub-Signature-256")).thenReturn("sha256=abc");
            when(mockContext.header("X-GitHub-Event")).thenReturn("pull_request");

            controller.handleWebhook(mockContext);

            verify(mockContext).status(200);
            verify(mockContext).result("Accepted");
            verify(mockOrchestrator).processAsync(any());
        }
    }

    @Nested
    @DisplayName("资源管理")
    class ResourceManagement {

        @Test
        @DisplayName("close() 委托给 orchestrator")
        void closeDelegates() {
            controller.close();
            verify(mockOrchestrator).close();
        }
    }

    @Nested
    @DisplayName("频率限制")
    class RateLimiting {

        @Test
        @DisplayName("超限请求返回 429，不执行签名验证")
        void rateLimitReturns429() {
            RateLimiter strictLimiter = new RateLimiter(1, Duration.ofMinutes(1));
            WebhookController limitedController = new WebhookController(
                    mockVerifier, mockOrchestrator, strictLimiter);

            when(mockContext.ip()).thenReturn("10.0.0.1");
            when(mockContext.body()).thenReturn("{}");
            when(mockContext.header("X-Hub-Signature-256")).thenReturn("sha256=abc");
            when(mockContext.header("X-GitHub-Event")).thenReturn("pull_request");

            // 第一次请求通过限流，到达签名验证
            limitedController.handleWebhook(mockContext);
            verify(mockContext, never()).status(429);
            verify(mockVerifier, times(1)).verify(anyString(), anyString());

            // 第二次请求被限流，签名验证不再被调用
            limitedController.handleWebhook(mockContext);
            verify(mockContext).status(429);
            verify(mockContext).result("Too many requests");
            // 总共仍只调用了一次签名验证（第二次被限流拦截）
            verify(mockVerifier, times(1)).verify(anyString(), anyString());
        }

        @Test
        @DisplayName("不同 IP 独立计算频率")
        void differentIpsIndependent() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1));
            WebhookController limitedController = new WebhookController(
                    mockVerifier, mockOrchestrator, limiter);

            when(mockVerifier.verify(anyString(), anyString())).thenReturn(true);
            when(mockContext.body()).thenReturn("{}");
            when(mockContext.header("X-Hub-Signature-256")).thenReturn("sha256=abc");
            when(mockContext.header("X-GitHub-Event")).thenReturn("ping");

            // 第一个 IP
            when(mockContext.ip()).thenReturn("10.0.0.1");
            limitedController.handleWebhook(mockContext);
            verify(mockContext).status(200);

            // 第二个 IP — 独立计数，应通过
            when(mockContext.ip()).thenReturn("10.0.0.2");
            limitedController.handleWebhook(mockContext);
            verify(mockContext, times(2)).status(200);
        }
    }

    @Nested
    @DisplayName("resolveClientIp")
    class ResolveClientIp {

        @Test
        @DisplayName("无代理头时回退到 ctx.ip()")
        void noProxyHeader() {
            when(mockContext.header("X-Forwarded-For")).thenReturn(null);
            when(mockContext.ip()).thenReturn("192.168.1.1");
            assertEquals("192.168.1.1", WebhookController.resolveClientIp(mockContext));
        }

        @Test
        @DisplayName("X-Forwarded-For 取第一个 IP")
        void xForwardedForFirstIp() {
            when(mockContext.header("X-Forwarded-For")).thenReturn("10.0.0.1, 172.16.0.1");
            assertEquals("10.0.0.1", WebhookController.resolveClientIp(mockContext));
        }

        @Test
        @DisplayName("空白 X-Forwarded-For 回退到 ctx.ip()")
        void blankForwardedFor() {
            when(mockContext.header("X-Forwarded-For")).thenReturn("  ");
            when(mockContext.ip()).thenReturn("192.168.1.1");
            assertEquals("192.168.1.1", WebhookController.resolveClientIp(mockContext));
        }
    }
}
