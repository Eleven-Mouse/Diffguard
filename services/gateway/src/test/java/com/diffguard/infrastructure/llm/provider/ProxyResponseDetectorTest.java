package com.diffguard.infrastructure.llm.provider;

import com.diffguard.exception.LlmApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProxyResponseDetector")
class ProxyResponseDetectorTest {

    // ------------------------------------------------------------------
    // 模式 1：业务错误包装 {"success": false, "code": 500}
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("模式 1：业务错误包装")
    class Pattern1BusinessError {

        @Test
        @DisplayName("success=false + code=500 → 抛出 LlmApiException")
        void standardProxyError() {
            String body = "{\"success\": false, \"code\": 500, \"msg\": \"Internal Server Error\"}";

            LlmApiException ex = assertThrows(LlmApiException.class,
                    () -> ProxyResponseDetector.validate(body));
            assertEquals(500, ex.getStatusCode());
            assertTrue(ex.getMessage().contains("Internal Server Error"));
        }

        @Test
        @DisplayName("success=false + code=429 → 状态码为 429")
        void rateLimitProxyError() {
            String body = "{\"success\": false, \"code\": 429, \"msg\": \"Too Many Requests\"}";

            LlmApiException ex = assertThrows(LlmApiException.class,
                    () -> ProxyResponseDetector.validate(body));
            assertEquals(500, ex.getStatusCode());
        }

        @Test
        @DisplayName("缺少 msg 字段 → 使用 message 字段")
        void fallbackToMessageField() {
            String body = "{\"success\": false, \"code\": 500, \"message\": \"fallback message\"}";

            LlmApiException ex = assertThrows(LlmApiException.class,
                    () -> ProxyResponseDetector.validate(body));
            assertTrue(ex.getMessage().contains("fallback message"));
        }

        @Test
        @DisplayName("缺少 msg 和 message → 使用默认错误消息")
        void noErrorMessage() {
            String body = "{\"success\": false, \"code\": 500}";

            LlmApiException ex = assertThrows(LlmApiException.class,
                    () -> ProxyResponseDetector.validate(body));
            assertNotNull(ex.getMessage());
        }

        @Test
        @DisplayName("success=true → 不抛出")
        void successTruePasses() {
            String body = "{\"success\": true, \"code\": 200, \"msg\": \"ok\"}";
            assertDoesNotThrow(() -> ProxyResponseDetector.validate(body));
        }
    }

    // ------------------------------------------------------------------
    // 模式 2：error 对象且无正常响应字段
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("模式 2：error 对象")
    class Pattern2ErrorObject {

        @Test
        @DisplayName("含 error 且无 choices/content → 抛出")
        void errorWithoutNormalFields() {
            String body = "{\"error\": {\"message\": \"Rate limit exceeded\", \"type\": \"rate_limit_error\"}}";

            LlmApiException ex = assertThrows(LlmApiException.class,
                    () -> ProxyResponseDetector.validate(body));
            assertEquals(500, ex.getStatusCode());
            assertTrue(ex.getMessage().contains("Rate limit exceeded"));
        }

        @Test
        @DisplayName("error 是字符串 → 直接使用")
        void errorIsString() {
            String body = "{\"error\": \"something went wrong\"}";

            LlmApiException ex = assertThrows(LlmApiException.class,
                    () -> ProxyResponseDetector.validate(body));
            assertTrue(ex.getMessage().contains("something went wrong"));
        }

        @Test
        @DisplayName("含 error + choices → 不抛出（正常 OpenAI 响应）")
        void errorWithChoicesIsNormal() {
            String body = "{\"error\": {\"message\": \"timeout\"}, \"choices\": [{\"message\": {\"content\": \"ok\"}}]}";
            assertDoesNotThrow(() -> ProxyResponseDetector.validate(body));
        }

        @Test
        @DisplayName("含 error + content → 不抛出（正常 Claude 响应）")
        void errorWithContentIsNormal() {
            String body = "{\"error\": {\"message\": \"timeout\"}, \"content\": [{\"type\": \"text\", \"text\": \"ok\"}]}";
            assertDoesNotThrow(() -> ProxyResponseDetector.validate(body));
        }
    }

    // ------------------------------------------------------------------
    // 安全跳过
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("安全跳过")
    class SafeSkip {

        @Test
        @DisplayName("null → 不抛出")
        void nullInput() {
            assertDoesNotThrow(() -> ProxyResponseDetector.validate(null));
        }

        @Test
        @DisplayName("空字符串 → 不抛出")
        void emptyInput() {
            assertDoesNotThrow(() -> ProxyResponseDetector.validate(""));
        }

        @Test
        @DisplayName("纯空白 → 不抛出")
        void blankInput() {
            assertDoesNotThrow(() -> ProxyResponseDetector.validate("   \n\t  "));
        }

        @Test
        @DisplayName("非 JSON 文本 → 不抛出")
        void nonJsonText() {
            assertDoesNotThrow(() -> ProxyResponseDetector.validate("This is plain text review output"));
        }

        @Test
        @DisplayName("以 { 开头但非合法 JSON → 不抛出")
        void startsWithBraceButInvalidJson() {
            assertDoesNotThrow(() -> ProxyResponseDetector.validate("{这不是有效的JSON"));
        }

        @Test
        @DisplayName("普通 JSON 对象 → 不抛出")
        void normalJsonObject() {
            assertDoesNotThrow(() -> ProxyResponseDetector.validate("{\"id\": \"chatcmpl-123\", \"choices\": []}"));
        }

        @Test
        @DisplayName("成功 API 响应含 choices → 不抛出")
        void successfulApiResponse() {
            String body = "{\"id\": \"chatcmpl-123\", \"object\": \"chat.completion\", \"choices\": [{\"message\": {\"role\": \"assistant\", \"content\": \"ok\"}}]}";
            assertDoesNotThrow(() -> ProxyResponseDetector.validate(body));
        }
    }

    // ------------------------------------------------------------------
    // 异常内容
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("异常内容验证")
    class ExceptionContent {

        @Test
        @DisplayName("异常状态码为 500")
        void statusCode500() {
            String body = "{\"success\": false, \"code\": 429, \"msg\": \"rate limited\"}";
            LlmApiException ex = assertThrows(LlmApiException.class,
                    () -> ProxyResponseDetector.validate(body));
            assertEquals(500, ex.getStatusCode());
        }

        @Test
        @DisplayName("超长错误消息被截断")
        void longMessageTruncated() {
            String longMsg = "x".repeat(500);
            String body = "{\"success\": false, \"code\": 500, \"msg\": \"" + longMsg + "\"}";

            LlmApiException ex = assertThrows(LlmApiException.class,
                    () -> ProxyResponseDetector.validate(body));
            assertTrue(ex.getMessage().length() < 300);
        }
    }
}
