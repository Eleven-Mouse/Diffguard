package com.diffguard.infrastructure.llm.provider;

import com.diffguard.exception.LlmApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProviderUtils")
class ProviderUtilsTest {

    // ------------------------------------------------------------------
    // translateException
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("translateException")
    class TranslateException {

        @Test
        @DisplayName("LlmApiException 输入 → 返回同一实例")
        void llmExceptionPassthrough() {
            LlmApiException original = new LlmApiException(429, "rate limited");
            LlmApiException result = ProviderUtils.translateException(original, "default");
            assertSame(original, result);
        }

        @Test
        @DisplayName("cause 是 LlmApiException → 返回 cause")
        void causeIsLlmException() {
            LlmApiException cause = new LlmApiException(502, "bad gateway");
            RuntimeException wrapper = new RuntimeException("wrapper", cause);

            LlmApiException result = ProviderUtils.translateException(wrapper, "default");
            assertSame(cause, result);
        }

        @Test
        @DisplayName("普通异常含状态码 → 提取状态码")
        void genericExceptionWithStatusCode() {
            RuntimeException e = new RuntimeException("HTTP 429: Too Many Requests");
            LlmApiException result = ProviderUtils.translateException(e, "default");

            assertEquals(429, result.getStatusCode());
            assertTrue(result.getMessage().contains("429"));
        }

        @Test
        @DisplayName("普通异常无状态码 → 返回 -1")
        void genericExceptionNoStatusCode() {
            RuntimeException e = new RuntimeException("connection timeout");
            LlmApiException result = ProviderUtils.translateException(e, "default");

            assertEquals(-1, result.getStatusCode());
        }

        @Test
        @DisplayName("异常无消息 → 使用默认消息")
        void nullMessageUsesDefault() {
            RuntimeException e = new RuntimeException((String) null);
            LlmApiException result = ProviderUtils.translateException(e, "fallback message");

            assertEquals("fallback message", result.getMessage());
        }

        @Test
        @DisplayName("异常链保留")
        void exceptionChainPreserved() {
            RuntimeException e = new RuntimeException("HTTP 500: Server Error");
            LlmApiException result = ProviderUtils.translateException(e, "default");

            assertSame(e, result.getCause());
        }
    }

    // ------------------------------------------------------------------
    // extractStatusCode
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("extractStatusCode")
    class ExtractStatusCode {

        @Test
        @DisplayName("消息含 429 → 429")
        void code429() {
            assertEquals(429, ProviderUtils.extractStatusCode(new RuntimeException("HTTP 429: Too Many Requests")));
        }

        @Test
        @DisplayName("消息含 500 → 500")
        void code500() {
            assertEquals(500, ProviderUtils.extractStatusCode(new RuntimeException("Server error 500")));
        }

        @Test
        @DisplayName("消息含 502 → 502")
        void code502() {
            assertEquals(502, ProviderUtils.extractStatusCode(new RuntimeException("Bad Gateway 502")));
        }

        @Test
        @DisplayName("消息含 503 → 503")
        void code503() {
            assertEquals(503, ProviderUtils.extractStatusCode(new RuntimeException("Service Unavailable 503")));
        }

        @Test
        @DisplayName("消息含 529 → 529")
        void code529() {
            assertEquals(529, ProviderUtils.extractStatusCode(new RuntimeException("Overloaded 529")));
        }

        @Test
        @DisplayName("消息含 400 → 400")
        void code400() {
            assertEquals(400, ProviderUtils.extractStatusCode(new RuntimeException("Bad Request 400")));
        }

        @Test
        @DisplayName("null 消息 → -1")
        void nullMessage() {
            assertEquals(-1, ProviderUtils.extractStatusCode(new RuntimeException((String) null)));
        }

        @Test
        @DisplayName("无匹配状态码 → -1")
        void noMatch() {
            assertEquals(-1, ProviderUtils.extractStatusCode(new RuntimeException("connection timeout")));
        }
    }
}
