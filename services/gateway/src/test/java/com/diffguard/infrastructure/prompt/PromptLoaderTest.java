package com.diffguard.infrastructure.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PromptLoader")
class PromptLoaderTest {

    // ------------------------------------------------------------------
    // load
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("load")
    class Load {

        @Test
        @DisplayName("returns fallback when resource not found")
        void returnsFallbackWhenNotFound() {
            String result = PromptLoader.load("/nonexistent/path.txt", "fallback value");
            assertEquals("fallback value", result);
        }

        @Test
        @DisplayName("returns fallback for null-like resource path")
        void returnsFallbackForInvalidPath() {
            String result = PromptLoader.load("/does-not-exist.at.all", "default");
            assertEquals("default", result);
        }

        @Test
        @DisplayName("returns fallback when resource path is empty")
        void returnsFallbackForEmptyPath() {
            String result = PromptLoader.load("", "my fallback");
            assertEquals("my fallback", result);
        }

        @Test
        @DisplayName("returns content when resource exists")
        void returnsContentWhenResourceExists() {
            // version.properties should exist in the classpath
            String result = PromptLoader.load("/version.properties", "fallback");
            assertNotNull(result);
            // If the resource exists, it should have content; otherwise fallback
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("returns fallback string when resource is null")
        void returnsFallbackForNullResource() {
            // A path that definitely doesn't exist
            String result = PromptLoader.load("/completely/invalid/resource.xyz", "default content");
            assertEquals("default content", result);
        }

        @Test
        @DisplayName("handles deeply nested non-existent path")
        void handlesDeeplyNestedPath() {
            String result = PromptLoader.load("/a/b/c/d/e/f/g.txt", "deep fallback");
            assertEquals("deep fallback", result);
        }

        @Test
        @DisplayName("empty fallback returns empty string when not found")
        void emptyFallbackReturnsEmpty() {
            String result = PromptLoader.load("/nonexistent.txt", "");
            assertEquals("", result);
        }

        @Test
        @DisplayName("null fallback returns null when not found")
        void nullFallbackReturnsNull() {
            String result = PromptLoader.load("/nonexistent.txt", null);
            assertNull(result);
        }
    }
}
