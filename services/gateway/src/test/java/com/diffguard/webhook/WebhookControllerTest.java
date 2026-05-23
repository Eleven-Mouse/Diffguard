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
        @DisplayName("签名验证先于频率限制执行")
        void signatureVerifiedBeforeRateLimit() {
            RateLimiter strictLimiter = new RateLimiter(1, Duration.ofMinutes(1));
            WebhookController limitedController = new WebhookController(
                    mockVerifier, mockOrchestrator, strictLimiter);

            when(mockVerifier.verify(anyString(), anyString())).thenReturn(true);
            when(mockContext.ip()).thenReturn("10.0.0.1");
            when(mockContext.body()).thenReturn("{}");
            when(mockContext.header("X-Hub-Signature-256")).thenReturn("sha256=abc");
            when(mockContext.header("X-GitHub-Event")).thenReturn("ping");

            // 第一次请求通过签名验证和限流
            limitedController.handleWebhook(mockContext);
            verify(mockContext, never()).status(429);
            verify(mockVerifier, times(1)).verify(anyString(), anyString());

            // 第二次请求：签名验证先通过，然后被限流
            limitedController.handleWebhook(mockContext);
            verify(mockContext).status(429);
            verify(mockContext).result("Too many requests");
            // 签名验证在限流之前，所以两次请求都会验证签名
            verify(mockVerifier, times(2)).verify(anyString(), anyString());
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
        @DisplayName("使用 ctx.ip() 获取客户端 IP")
        void usesContextIp() {
            when(mockContext.ip()).thenReturn("192.168.1.1");
            assertEquals("192.168.1.1", WebhookController.resolveClientIp(mockContext));
        }
    }
}
