package com.diffguard.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for all DiffGuard exception classes:
 * DiffGuardException, ConfigException, DiffCollectionException,
 * LlmApiException, and WebhookException.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiffGuard Exceptions")
class DiffGuardExceptionTest {

    // ========================================================================
    // DiffGuardException (base class)
    // ========================================================================

    @Nested
    @DisplayName("DiffGuardException")
    class DiffGuardExceptionTests {

        @Test
        @DisplayName("constructor with message preserves message")
        void constructorWithMessage() {
            DiffGuardException ex = new DiffGuardException("test error");
            assertEquals("test error", ex.getMessage());
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("constructor with message and cause preserves both")
        void constructorWithMessageAndCause() {
            Throwable cause = new RuntimeException("root cause");
            DiffGuardException ex = new DiffGuardException("test error", cause);
            assertEquals("test error", ex.getMessage());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("is checked exception (extends Exception)")
        void isCheckedException() {
            assertTrue(Exception.class.isAssignableFrom(DiffGuardException.class));
        }
    }

    // ========================================================================
    // ConfigException
    // ========================================================================

    @Nested
    @DisplayName("ConfigException")
    class ConfigExceptionTests {

        @Test
        @DisplayName("constructor with message")
        void constructorWithMessage() {
            ConfigException ex = new ConfigException("bad config");
            assertEquals("bad config", ex.getMessage());
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("constructor with message and cause")
        void constructorWithMessageAndCause() {
            Throwable cause = new IOException("file not found");
            ConfigException ex = new ConfigException("parse failed", cause);
            assertEquals("parse failed", ex.getMessage());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("toString contains class name and message")
        void toStringContainsClassNameAndMessage() {
            ConfigException ex = new ConfigException("missing field");
            String str = ex.toString();
            assertTrue(str.contains("ConfigException"));
            assertTrue(str.contains("missing field"));
        }

        @Test
        @DisplayName("extends DiffGuardException")
        void extendsDiffGuardException() {
            ConfigException ex = new ConfigException("test");
            assertInstanceOf(DiffGuardException.class, ex);
        }

        @Test
        @DisplayName("can be caught as DiffGuardException")
        void canBeCaughtAsBase() {
            DiffGuardException caught;
            try {
                throw new ConfigException("config error");
            } catch (DiffGuardException e) {
                caught = e;
            }
            assertInstanceOf(ConfigException.class, caught);
        }
    }

    // ========================================================================
    // DiffCollectionException
    // ========================================================================

    @Nested
    @DisplayName("DiffCollectionException")
    class DiffCollectionExceptionTests {

        @Test
        @DisplayName("constructor with message")
        void constructorWithMessage() {
            DiffCollectionException ex = new DiffCollectionException("diff failed");
            assertEquals("diff failed", ex.getMessage());
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("constructor with message and cause")
        void constructorWithMessageAndCause() {
            Throwable cause = new RuntimeException("git error");
            DiffCollectionException ex = new DiffCollectionException("collection failed", cause);
            assertEquals("collection failed", ex.getMessage());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("toString contains class name and message")
        void toStringContainsClassNameAndMessage() {
            DiffCollectionException ex = new DiffCollectionException("no diff");
            String str = ex.toString();
            assertTrue(str.contains("DiffCollectionException"));
            assertTrue(str.contains("no diff"));
        }

        @Test
        @DisplayName("extends DiffGuardException")
        void extendsDiffGuardException() {
            DiffCollectionException ex = new DiffCollectionException("test");
            assertInstanceOf(DiffGuardException.class, ex);
        }
    }

    // ========================================================================
    // LlmApiException
    // ========================================================================

    @Nested
    @DisplayName("LlmApiException")
    class LlmApiExceptionTests {

        @Nested
        @DisplayName("Constructors")
        class ConstructorTests {

            @Test
            @DisplayName("constructor with message sets statusCode to -1")
            void constructorWithMessage() {
                LlmApiException ex = new LlmApiException("API error");
                assertEquals("API error", ex.getMessage());
                assertNull(ex.getCause());
                assertEquals(-1, ex.getStatusCode());
            }

            @Test
            @DisplayName("constructor with message and cause sets statusCode to -1")
            void constructorWithMessageAndCause() {
                Throwable cause = new RuntimeException("timeout");
                LlmApiException ex = new LlmApiException("call failed", cause);
                assertEquals("call failed", ex.getMessage());
                assertSame(cause, ex.getCause());
                assertEquals(-1, ex.getStatusCode());
            }

            @Test
            @DisplayName("constructor with statusCode and message")
            void constructorWithStatusCodeAndMessage() {
                LlmApiException ex = new LlmApiException(429, "rate limited");
                assertEquals(429, ex.getStatusCode());
                assertEquals("rate limited", ex.getMessage());
            }

            @Test
            @DisplayName("extends DiffGuardException")
            void extendsDiffGuardException() {
                LlmApiException ex = new LlmApiException("test");
                assertInstanceOf(DiffGuardException.class, ex);
            }
        }

        @Nested
        @DisplayName("getStatusCode")
        class GetStatusCodeTests {

            @Test
            @DisplayName("returns configured status code")
            void returnsConfiguredStatusCode() {
                assertEquals(500, new LlmApiException(500, "server error").getStatusCode());
                assertEquals(429, new LlmApiException(429, "rate limit").getStatusCode());
                assertEquals(402, new LlmApiException(402, "quota").getStatusCode());
                assertEquals(-1, new LlmApiException("no code").getStatusCode());
            }
        }

        @Nested
        @DisplayName("isRateLimitError")
        class IsRateLimitErrorTests {

            @Test
            @DisplayName("returns true for 429")
            void returnsTrueFor429() {
                assertTrue(new LlmApiException(429, "rate limited").isRateLimitError());
            }

            @Test
            @DisplayName("returns false for non-429 status codes")
            void returnsFalseForNon429() {
                assertFalse(new LlmApiException(200, "ok").isRateLimitError());
                assertFalse(new LlmApiException(500, "server error").isRateLimitError());
                assertFalse(new LlmApiException(401, "unauthorized").isRateLimitError());
                assertFalse(new LlmApiException(-1, "unknown").isRateLimitError());
            }
        }

        @Nested
        @DisplayName("isServerError")
        class IsServerErrorTests {

            @Test
            @DisplayName("returns true for 5xx status codes")
            void returnsTrueFor5xx() {
                assertTrue(new LlmApiException(500, "internal").isServerError());
                assertTrue(new LlmApiException(502, "bad gateway").isServerError());
                assertTrue(new LlmApiException(503, "unavailable").isServerError());
                assertTrue(new LlmApiException(599, "custom 5xx").isServerError());
            }

            @Test
            @DisplayName("returns false for non-5xx status codes")
            void returnsFalseForNon5xx() {
                assertFalse(new LlmApiException(499, "client error").isServerError());
                assertFalse(new LlmApiException(600, "beyond range").isServerError());
                assertFalse(new LlmApiException(200, "ok").isServerError());
                assertFalse(new LlmApiException(429, "rate limit").isServerError());
                assertFalse(new LlmApiException(-1, "unknown").isServerError());
            }
        }

        @Nested
        @DisplayName("isRetryable")
        class IsRetryableTests {

            @Test
            @DisplayName("returns true for 429 rate limit")
            void returnsTrueForRateLimit() {
                assertTrue(new LlmApiException(429, "rate limited").isRetryable());
            }

            @Test
            @DisplayName("returns true for 500 server error")
            void returnsTrueForServerError() {
                assertTrue(new LlmApiException(500, "internal").isRetryable());
            }

            @Test
            @DisplayName("returns true for 503 service unavailable")
            void returnsTrueForServiceUnavailable() {
                assertTrue(new LlmApiException(503, "unavailable").isRetryable());
            }

            @Test
            @DisplayName("returns false for 4xx client errors (not 429)")
            void returnsFalseForClientErrors() {
                assertFalse(new LlmApiException(400, "bad request").isRetryable());
                assertFalse(new LlmApiException(401, "unauthorized").isRetryable());
                assertFalse(new LlmApiException(403, "forbidden").isRetryable());
                assertFalse(new LlmApiException(404, "not found").isRetryable());
            }

            @Test
            @DisplayName("returns false for -1 (unknown)")
            void returnsFalseForUnknown() {
                assertFalse(new LlmApiException(-1, "unknown").isRetryable());
            }
        }

        @Nested
        @DisplayName("isQuotaError")
        class IsQuotaErrorTests {

            @Test
            @DisplayName("returns true for 402 Payment Required")
            void returnsTrueFor402() {
                assertTrue(new LlmApiException(402, "quota exceeded").isQuotaError());
            }

            @Test
            @DisplayName("returns false for non-402 status codes")
            void returnsFalseForNon402() {
                assertFalse(new LlmApiException(429, "rate limit").isQuotaError());
                assertFalse(new LlmApiException(500, "server error").isQuotaError());
                assertFalse(new LlmApiException(200, "ok").isQuotaError());
                assertFalse(new LlmApiException(-1, "unknown").isQuotaError());
            }
        }

        @Nested
        @DisplayName("Boundary tests")
        class BoundaryTests {

            @Test
            @DisplayName("statusCode 499 is NOT server error")
            void statusCode499IsNotServerError() {
                assertFalse(new LlmApiException(499, "client closed").isServerError());
                assertFalse(new LlmApiException(499, "client closed").isRetryable());
            }

            @Test
            @DisplayName("statusCode 500 IS server error (boundary)")
            void statusCode500IsServerError() {
                assertTrue(new LlmApiException(500, "boundary").isServerError());
                assertTrue(new LlmApiException(500, "boundary").isRetryable());
            }

            @Test
            @DisplayName("statusCode 599 IS server error (boundary)")
            void statusCode599IsServerError() {
                assertTrue(new LlmApiException(599, "boundary").isServerError());
                assertTrue(new LlmApiException(599, "boundary").isRetryable());
            }

            @Test
            @DisplayName("statusCode 600 is NOT server error (boundary)")
            void statusCode600IsNotServerError() {
                assertFalse(new LlmApiException(600, "beyond range").isServerError());
                assertFalse(new LlmApiException(600, "beyond range").isRetryable());
            }
        }
    }

    // ========================================================================
    // WebhookException
    // ========================================================================

    @Nested
    @DisplayName("WebhookException")
    class WebhookExceptionTests {

        @Test
        @DisplayName("constructor with message and cause")
        void constructorWithMessageAndCause() {
            Throwable cause = new RuntimeException("connection reset");
            WebhookException ex = new WebhookException("webhook failed", cause);
            assertEquals("webhook failed", ex.getMessage());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("extends DiffGuardException")
        void extendsDiffGuardException() {
            WebhookException ex = new WebhookException("test", new RuntimeException());
            assertInstanceOf(DiffGuardException.class, ex);
        }

        @Test
        @DisplayName("can be caught as DiffGuardException")
        void canBeCaughtAsBase() {
            DiffGuardException caught;
            try {
                throw new WebhookException("webhook error", new RuntimeException());
            } catch (DiffGuardException e) {
                caught = e;
            }
            assertInstanceOf(WebhookException.class, caught);
        }
    }

    // ========================================================================
    // Exception hierarchy integration tests
    // ========================================================================

    @Nested
    @DisplayName("Exception hierarchy")
    class ExceptionHierarchyTests {

        @Test
        @DisplayName("all exceptions are catchable as DiffGuardException")
        void allCatchableAsBase() {
            assertDoesNotThrow(() -> {
                try {
                    throw new ConfigException("config");
                } catch (DiffGuardException e) {
                    // caught
                }
            });

            assertDoesNotThrow(() -> {
                try {
                    throw new DiffCollectionException("diff");
                } catch (DiffGuardException e) {
                    // caught
                }
            });

            assertDoesNotThrow(() -> {
                try {
                    throw new LlmApiException("llm");
                } catch (DiffGuardException e) {
                    // caught
                }
            });

            assertDoesNotThrow(() -> {
                try {
                    throw new WebhookException("webhook", new RuntimeException());
                } catch (DiffGuardException e) {
                    // caught
                }
            });
        }
    }
}
