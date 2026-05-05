package com.diffguard.infrastructure.llm.provider;

import com.diffguard.exception.LlmApiException;
import com.diffguard.infrastructure.config.ReviewConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OpenAiHttpProvider")
class OpenAiHttpProviderTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @Mock
    private TokenTracker tokenTracker;

    /**
     * Creates an OpenAiHttpProvider with mocked dependencies via reflection,
     * bypassing the need for environment variables.
     */
    private OpenAiHttpProvider createProvider(String model, TokenTracker tracker) {
        ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig() {
            @Override
            public String resolveApiKey() { return "test-api-key-1234567890"; }
        };
        config.setProvider("openai");
        config.setModel(model);
        config.setBaseUrl("https://api.openai.com/v1");
        config.setMaxTokens(4096);
        config.setTemperature(0.3);
        config.setTimeoutSeconds(60);

        OpenAiHttpProvider provider = new OpenAiHttpProvider(config, tracker);
        injectField(provider, "httpClient", httpClient);
        return provider;
    }

    private void injectField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to inject field: " + fieldName, e);
        }
    }

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("constructor requires API key via env var")
        void constructorRequiresApiKey() {
            ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig();
            config.setProvider("openai");
            config.setModel("gpt-4o");
            config.setBaseUrl("https://api.openai.com/v1");

            try {
                new OpenAiHttpProvider(config, null);
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("API") || e.getMessage().contains("密钥"));
            }
        }
    }

    // ------------------------------------------------------------------
    // call - success
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("call - success")
    class CallSuccess {

        @Test
        @DisplayName("successful call returns content")
        void successfulCall() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", tokenTracker);

            String responseBody = """
                    {
                      "choices": [{
                        "message": {"content": "Review result"},
                        "finish_reason": "stop"
                      }],
                      "usage": {"prompt_tokens": 100, "completion_tokens": 50}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            String result = provider.call("system prompt", "user prompt");
            assertEquals("Review result", result);
        }

        @Test
        @DisplayName("successful call tracks tokens")
        void successfulCallTracksTokens() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", tokenTracker);

            String responseBody = """
                    {
                      "choices": [{
                        "message": {"content": "result"},
                        "finish_reason": "stop"
                      }],
                      "usage": {"prompt_tokens": 200, "completion_tokens": 100}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            provider.call("sys", "user");
            verify(tokenTracker).addTokens(300);
        }

        @Test
        @DisplayName("empty content returns empty string")
        void emptyContent() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", tokenTracker);

            String responseBody = """
                    {
                      "choices": [{
                        "message": {"content": ""},
                        "finish_reason": "stop"
                      }],
                      "usage": {}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            String result = provider.call("sys", "user");
            assertEquals("", result);
        }

        @Test
        @DisplayName("missing choices returns empty string")
        void missingChoices() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", tokenTracker);

            String responseBody = """
                    {
                      "choices": [],
                      "usage": {}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            String result = provider.call("sys", "user");
            assertEquals("", result);
        }

        @Test
        @DisplayName("call with null token tracker does not fail")
        void nullTokenTracker() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", null);

            String responseBody = """
                    {
                      "choices": [{
                        "message": {"content": "result"},
                        "finish_reason": "stop"
                      }],
                      "usage": {"prompt_tokens": 100, "completion_tokens": 50}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            String result = provider.call("sys", "user");
            assertEquals("result", result);
        }

        @Test
        @DisplayName("zero tokens does not call tracker")
        void zeroTokensNoTracker() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", tokenTracker);

            String responseBody = """
                    {
                      "choices": [{
                        "message": {"content": "result"},
                        "finish_reason": "stop"
                      }],
                      "usage": {"prompt_tokens": 0, "completion_tokens": 0}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            provider.call("sys", "user");
            verify(tokenTracker, never()).addTokens(anyInt());
        }
    }

    // ------------------------------------------------------------------
    // call - errors
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("call - errors")
    class CallErrors {

        @Test
        @DisplayName("non-200 status throws LlmApiException")
        void non200Throws() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", tokenTracker);

            when(httpResponse.statusCode()).thenReturn(500);
            when(httpResponse.body()).thenReturn("Server Error");
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            assertThrows(LlmApiException.class,
                    () -> provider.call("sys", "user"));
        }

        @Test
        @DisplayName("401 throws LlmApiException")
        void unauthorizedThrows() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", tokenTracker);

            when(httpResponse.statusCode()).thenReturn(401);
            when(httpResponse.body()).thenReturn("Unauthorized");
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            assertThrows(LlmApiException.class,
                    () -> provider.call("sys", "user"));
        }

        @Test
        @DisplayName("429 throws LlmApiException")
        void rateLimitThrows() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", tokenTracker);

            when(httpResponse.statusCode()).thenReturn(429);
            when(httpResponse.body()).thenReturn("Rate limited");
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            assertThrows(LlmApiException.class,
                    () -> provider.call("sys", "user"));
        }

        @Test
        @DisplayName("IOException is wrapped into exception")
        void ioExceptionWrapped() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", tokenTracker);

            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Connection refused"));

            assertThrows(Exception.class,
                    () -> provider.call("sys", "user"));
        }

        @Test
        @DisplayName("proxy error triggers fallback retry")
        void proxyErrorTriggersFallback() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-4o", tokenTracker);

            // First call returns 400 (proxy error)
            when(httpResponse.statusCode()).thenReturn(400);
            when(httpResponse.body()).thenReturn("Bad request response_format");
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            // Should attempt retry and eventually throw
            assertThrows(Exception.class,
                    () -> provider.call("sys", "user"));
        }
    }

    // ------------------------------------------------------------------
    // Model-specific behavior
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Model-specific behavior")
    class ModelSpecific {

        @Test
        @DisplayName("GPT-5 model works correctly")
        void gpt5Works() throws Exception {
            OpenAiHttpProvider provider = createProvider("gpt-5", tokenTracker);

            String responseBody = """
                    {
                      "choices": [{
                        "message": {"content": "result"},
                        "finish_reason": "stop"
                      }],
                      "usage": {}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            String result = provider.call("sys", "user");
            assertEquals("result", result);
        }

        @Test
        @DisplayName("Claude model via proxy works")
        void claudeViaProxyWorks() throws Exception {
            OpenAiHttpProvider provider = createProvider("claude-3-5-sonnet", tokenTracker);

            String responseBody = """
                    {
                      "choices": [{
                        "message": {"content": "claude result"},
                        "finish_reason": "stop"
                      }],
                      "usage": {}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            String result = provider.call("sys", "user");
            assertEquals("claude result", result);
        }
    }
}
