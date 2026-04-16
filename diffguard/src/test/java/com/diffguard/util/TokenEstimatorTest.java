package com.diffguard.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenEstimatorTest {

    @Test
    @DisplayName("count 返回非负值")
    void countNonNegative() {
        assertTrue(TokenEstimator.count("hello world") > 0);
    }

    @Test
    @DisplayName("空字符串返回 0 或 1")
    void emptyString() {
        int tokens = TokenEstimator.count("");
        assertTrue(tokens >= 0);
    }

    @Test
    @DisplayName("estimate 对 openai 等于 count")
    void estimateOpenAi() {
        String text = "public class Main { public static void main(String[] args) { } }";
        assertEquals(TokenEstimator.count(text), TokenEstimator.estimate(text, "openai"));
    }

    @Test
    @DisplayName("estimate 对 claude 大于 count（修正系数）")
    void estimateClaude() {
        String text = "public class Main { public static void main(String[] args) { } }";
        int base = TokenEstimator.count(text);
        int claude = TokenEstimator.estimate(text, "claude");
        assertTrue(claude > base,
                "Claude 估算值 (" + claude + ") 应大于基础值 (" + base + ")");
    }

    @Test
    @DisplayName("estimate 对 claude 约为基础值 × 1.15")
    void estimateClaudeCorrectionFactor() {
        String text = "This is a test string for token estimation accuracy verification.";
        int base = TokenEstimator.count(text);
        int claude = TokenEstimator.estimate(text, "claude");
        int expected = (int) Math.ceil(base * 1.15);
        assertEquals(expected, claude);
    }

    @Test
    @DisplayName("estimate 大小写不敏感")
    void estimateCaseInsensitive() {
        String text = "test string";
        assertEquals(TokenEstimator.estimate(text, "Claude"), TokenEstimator.estimate(text, "claude"));
        assertEquals(TokenEstimator.estimate(text, "OpenAI"), TokenEstimator.estimate(text, "openai"));
    }

    @Test
    @DisplayName("中文文本 token 估算")
    void chineseText() {
        String text = "这是一个中文测试文本，用于验证 token 估算功能是否正常工作。";
        assertTrue(TokenEstimator.count(text) > 0);
        assertTrue(TokenEstimator.estimate(text, "claude") > TokenEstimator.count(text));
    }
}
