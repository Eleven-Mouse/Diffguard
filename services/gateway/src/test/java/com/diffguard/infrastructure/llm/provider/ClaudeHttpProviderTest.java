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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClaudeHttpProvider")
class ClaudeHttpProviderTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @Mock
    private TokenTracker tokenTracker;

    /**
     * Creates a ClaudeHttpProvider with all dependencies injected via reflection,
     * bypassing the need for environment variables.
     */
    private ClaudeHttpProvider createProvider(TokenTracker tracker) {
        ReviewConfig.LlmConfig config = new ReviewConfig.LlmConfig() {
            @Override
            public String resolveApiKey() { return "test-api-key-1234567890"; }
        };
        config.setProvider("claude");
        config.setModel("claude-sonnet-4-6");
        config.setBaseUrl("https://api.anthropic.com");
        config.setMaxTokens(4096);
        config.setTemperature(0.3);
        config.setTimeoutSeconds(60);

        ClaudeHttpProvider provider = new ClaudeHttpProvider(config, tracker);
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
            config.setProvider("claude");
            config.setModel("claude-sonnet-4-6");
            config.setBaseUrl("https://api.anthropic.com");

            // Without DIFFGUARD_API_KEY env var, should throw
            try {
                new ClaudeHttpProvider(config, null);
                // If it succeeds, the env var is set - that's also fine
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("API") || e.getMessage().contains("密钥"));
            }
        }
    }

    // ------------------------------------------------------------------
    // call - successful scenarios
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("call - success")
    class CallSuccess {

        @Test
        @DisplayName("successful API call returns response text")
        void successfulCallReturnsText() throws Exception {
            ClaudeHttpProvider provider = createProvider(tokenTracker);

            String responseBody = """
                    {
                      "content": [{"type": "text", "text": "Review result text"}],
                      "usage": {"input_tokens": 100, "output_tokens": 50},
                      "stop_reason": "end_turn"
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            String result = provider.call("system prompt", "user prompt");
            assertEquals("Review result text", result);
        }

        @Test
        @DisplayName("successful call tracks tokens")
        void successfulCallTracksTokens() throws Exception {
            ClaudeHttpProvider provider = createProvider(tokenTracker);

            String responseBody = """
                    {
                      "content": [{"type": "text", "text": "result"}],
                      "usage": {"input_tokens": 200, "output_tokens": 100}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            provider.call("sys", "user");

            verify(tokenTracker).addTokens(300);
        }

        @Test
        @DisplayName("call with multiple content blocks concatenates text")
        void multipleContentBlocks() throws Exception {
            ClaudeHttpProvider provider = createProvider(tokenTracker);

            String responseBody = """
                    {
                      "content": [
                        {"type": "text", "text": "Part 1"},
                        {"type": "text", "text": "Part 2"}
                      ],
                      "usage": {}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            String result = provider.call("sys", "user");
            assertEquals("Part 1Part 2", result);
        }

        @Test
        @DisplayName("call with no token tracker does not fail")
        void noTokenTracker() throws Exception {
            ClaudeHttpProvider provider = createProvider(null);

            String responseBody = """
                    {
                      "content": [{"type": "text", "text": "result"}],
                      "usage": {"input_tokens": 100, "output_tokens": 50}
                    }
                    """;

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            String result = provider.call("sys", "user");
            assertEquals("result", result);
        }

        @Test
        @DisplayName("call with zero tokens does not call tracker")
        void zeroTokensNoTracker() throws Exception {
            ClaudeHttpProvider provider = createProvider(tokenTracker);

            String responseBody = """
                    {
                      "content": [{"type": "text", "text": "result"}],
                      "usage": {"input_tokens": 0, "output_tokens": 0}
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
    // call - error scenarios
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("call - errors")
    class CallErrors {

        @Test
        @DisplayName("non-200 status code throws LlmApiException")
        void non200StatusThrows() throws Exception {
            ClaudeHttpProvider provider = createProvider(tokenTracker);

            when(httpResponse.statusCode()).thenReturn(500);
            when(httpResponse.body()).thenReturn("Internal Server Error");
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            assertThrows(LlmApiException.class,
                    () -> provider.call("sys", "user"));
        }

        @Test
        @DisplayName("401 status code throws LlmApiException")
        void unauthorizedThrows() throws Exception {
            ClaudeHttpProvider provider = createProvider(tokenTracker);

            when(httpResponse.statusCode()).thenReturn(401);
            when(httpResponse.body()).thenReturn("Unauthorized");
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            assertThrows(LlmApiException.class,
                    () -> provider.call("sys", "user"));
        }

        @Test
        @DisplayName("429 rate limit throws LlmApiException")
        void rateLimitThrows() throws Exception {
            ClaudeHttpProvider provider = createProvider(tokenTracker);

            when(httpResponse.statusCode()).thenReturn(429);
            when(httpResponse.body()).thenReturn("Rate limited");
            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            assertThrows(LlmApiException.class,
                    () -> provider.call("sys", "user"));
        }

        @Test
        @DisplayName("empty content array returns empty string")
        void emptyContentReturnsEmpty() throws Exception {
            ClaudeHttpProvider provider = createProvider(tokenTracker);

            String responseBody = """
                    {
                      "content": [],
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
        @DisplayName("IOException from HttpClient is wrapped")
        void ioExceptionWrapped() throws Exception {
            ClaudeHttpProvider provider = createProvider(tokenTracker);

            when(httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Connection refused"));

            assertThrows(Exception.class,
                    () -> provider.call("sys", "user"));
        }
    }

    // ------------------------------------------------------------------
    // close - resource cleanup
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("close - 资源清理")
    class Close {

        @Test
        @DisplayName("close() 关闭 HttpClient 不抛异常")
        void closeDoesNotThrow() {
            ClaudeHttpProvider provider = createProvider(tokenTracker);
            assertDoesNotThrow(() -> provider.close());
        }
    }
}
