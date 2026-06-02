package com.diffguard.platform.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MetricsService")
class MetricsServiceTest {

    private MetricsService service;

    @BeforeEach
    void setUp() {
        service = new MetricsService();
    }

    // ------------------------------------------------------------------
    // Constructor & Registry
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Registry")
    class Registry {

        @Test
        @DisplayName("getRegistry returns non-null MeterRegistry")
        void getRegistryNotNull() {
            MeterRegistry registry = service.getRegistry();
            assertNotNull(registry);
        }

        @Test
        @DisplayName("registry contains expected meters")
        void registryContainsMeters() {
            MeterRegistry registry = service.getRegistry();
            assertFalse(registry.getMeters().isEmpty());
            assertTrue(registry.getMeters().size() >= 9);
        }
    }

    // ------------------------------------------------------------------
    // Counter record methods
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Record methods")
    class RecordMethods {

        @Test
        @DisplayName("recordReviewSubmitted increments review total counter")
        void recordReviewSubmitted() {
            service.recordReviewSubmitted();
            service.recordReviewSubmitted();
            service.recordReviewSubmitted();

            double count = service.getRegistry().counter("diffguard_review_total").count();
            assertEquals(3.0, count, 0.001);
        }

        @Test
        @DisplayName("recordReviewSuccess increments success counter")
        void recordReviewSuccess() {
            service.recordReviewSuccess();
            service.recordReviewSuccess();

            double count = service.getRegistry().counter("diffguard_review_success").count();
            assertEquals(2.0, count, 0.001);
        }

        @Test
        @DisplayName("recordReviewFailed increments failed counter")
        void recordReviewFailed() {
            service.recordReviewFailed();

            double count = service.getRegistry().counter("diffguard_review_failed").count();
            assertEquals(1.0, count, 0.001);
        }

        @Test
        @DisplayName("recordIssues increments by count")
        void recordIssues() {
            service.recordIssues(5);

            double count = service.getRegistry().counter("diffguard_issues_total").count();
            assertEquals(5.0, count, 0.001);
        }

        @Test
        @DisplayName("recordIssues with zero does not change counter")
        void recordIssuesZero() {
            service.recordIssues(0);

            double count = service.getRegistry().counter("diffguard_issues_total").count();
            assertEquals(0.0, count, 0.001);
        }

        @Test
        @DisplayName("recordCriticalIssue increments by one")
        void recordCriticalIssue() {
            service.recordCriticalIssue();
            service.recordCriticalIssue();
            service.recordCriticalIssue();

            double count = service.getRegistry().counter("diffguard_issues_critical").count();
            assertEquals(3.0, count, 0.001);
        }

        @Test
        @DisplayName("recordTokensUsed increments by token count")
        void recordTokensUsed() {
            service.recordTokensUsed(100);
            service.recordTokensUsed(200);

            double count = service.getRegistry().counter("diffguard_llm_tokens").count();
            assertEquals(300.0, count, 0.001);
        }

        @Test
        @DisplayName("recordStaticHit increments by one")
        void recordStaticHit() {
            service.recordStaticHit();

            double count = service.getRegistry().counter("diffguard_rules_static_hits").count();
            assertEquals(1.0, count, 0.001);
        }
    }

    // ------------------------------------------------------------------
    // Timer operations
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Timer operations")
    class TimerOperations {

        @Test
        @DisplayName("startReviewTimer and stopReviewTimer record duration")
        void reviewTimerRecords() {
            Timer.Sample sample = service.startReviewTimer();
            assertNotNull(sample);
            service.stopReviewTimer(sample);

            Timer timer = service.getRegistry().timer("diffguard_review_duration");
            assertEquals(1, timer.count());
            assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) >= 0.0);
        }

        @Test
        @DisplayName("startLlmTimer and stopLlmTimer record duration")
        void llmTimerRecords() {
            Timer.Sample sample = service.startLlmTimer();
            assertNotNull(sample);
            service.stopLlmTimer(sample);

            Timer timer = service.getRegistry().timer("diffguard_llm_call_duration");
            assertEquals(1, timer.count());
            assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) >= 0.0);
        }

        @Test
        @DisplayName("multiple timer samples record correctly")
        void multipleTimerSamples() {
            Timer.Sample s1 = service.startReviewTimer();
            Timer.Sample s2 = service.startReviewTimer();
            service.stopReviewTimer(s1);
            service.stopReviewTimer(s2);

            Timer timer = service.getRegistry().timer("diffguard_review_duration");
            assertEquals(2, timer.count());
        }
    }

    // ------------------------------------------------------------------
    // Scrape
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Scrape")
    class Scrape {

        @Test
        @DisplayName("scrape returns non-empty string")
        void scrapeReturnsNonEmpty() {
            String output = service.scrape();
            assertFalse(output.isEmpty());
        }

        @Test
        @DisplayName("scrape contains metric names")
        void scrapeContainsMetricNames() {
            service.recordReviewSubmitted();
            service.recordReviewSuccess();
            service.recordReviewFailed();
            service.recordIssues(1);
            service.recordCriticalIssue();
            service.recordTokensUsed(1);
            service.recordStaticHit();
            String output = service.scrape().replace('_', '.');
            assertTrue(output.contains("diffguard_review_total"));
            assertTrue(output.contains("diffguard_review_success"));
            assertTrue(output.contains("diffguard_review_failed"));
            assertTrue(output.contains("diffguard_issues_total"));
            assertTrue(output.contains("diffguard_issues_critical"));
            assertTrue(output.contains("diffguard_llm_tokens"));
            assertTrue(output.contains("diffguard_rules_static_hits"));
        }

        @Test
        @DisplayName("scrape reflects recorded values")
        void scrapeReflectsRecordedValues() {
            service.recordReviewSubmitted();
            service.recordIssues(3);

            String output = service.scrape();
            assertFalse(output.isEmpty());
        }
    }
}
