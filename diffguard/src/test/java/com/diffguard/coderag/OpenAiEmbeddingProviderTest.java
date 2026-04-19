package com.diffguard.coderag;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiEmbeddingProviderTest {

    @Test
    void createWithFallback_nullApiKey_returnsTfidfProvider() {
        EmbeddingProvider provider = OpenAiEmbeddingProvider.createWithFallback(
                null, "https://api.openai.com/v1",
                "text-embedding-3-small", 1536, Duration.ofSeconds(30));

        assertInstanceOf(LocalTFIDFProvider.class, provider);
    }

    @Test
    void createWithFallback_blankApiKey_returnsTfidfProvider() {
        EmbeddingProvider provider = OpenAiEmbeddingProvider.createWithFallback(
                "", "https://api.openai.com/v1",
                "text-embedding-3-small", 1536, Duration.ofSeconds(30));

        assertInstanceOf(LocalTFIDFProvider.class, provider);
    }

    @Test
    void createWithFallback_whitespaceApiKey_returnsTfidfProvider() {
        EmbeddingProvider provider = OpenAiEmbeddingProvider.createWithFallback(
                "   ", "https://api.openai.com/v1",
                "text-embedding-3-small", 1536, Duration.ofSeconds(30));

        assertInstanceOf(LocalTFIDFProvider.class, provider);
    }

    @Test
    void embeddingUnavailableException_isRuntimeException() {
        RuntimeException cause = new RuntimeException("connection refused");
        var ex = new OpenAiEmbeddingProvider.EmbeddingUnavailableException("test", cause);

        assertInstanceOf(RuntimeException.class, ex);
        assertEquals("test", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
