package com.diffguard.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReviewResultTest {

    private ReviewIssue makeIssue(Severity severity, String file, int line) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSeverity(severity);
        issue.setFile(file);
        issue.setLine(line);
        issue.setType("测试");
        issue.setMessage("测试问题");
        issue.setSuggestion("测试建议");
        return issue;
    }

    @Nested
    @DisplayName("hasCriticalIssues - 结构化 Issues")
    class StructuredIssues {

        @Test
        @DisplayName("空 issues 列表 → false")
        void emptyIssues() {
            ReviewResult result = new ReviewResult();
            assertFalse(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("只有 WARNING 级别 → false")
        void onlyWarnings() {
            ReviewResult result = new ReviewResult();
            result.addIssue(makeIssue(Severity.WARNING, "A.java", 10));
            result.addIssue(makeIssue(Severity.INFO, "B.java", 20));
            assertFalse(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("存在 CRITICAL 级别 → true")
        void hasCritical() {
            ReviewResult result = new ReviewResult();
            result.addIssue(makeIssue(Severity.WARNING, "A.java", 10));
            result.addIssue(makeIssue(Severity.CRITICAL, "B.java", 20));
            assertTrue(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("只有 INFO 级别 → false")
        void onlyInfo() {
            ReviewResult result = new ReviewResult();
            result.addIssue(makeIssue(Severity.INFO, "A.java", 10));
            assertFalse(result.hasCriticalIssues());
        }
    }

    @Nested
    @DisplayName("hasCriticalIssues - hasCriticalFlag")
    class HasCriticalFlag {

        @Test
        @DisplayName("flag=true 无 issues → true")
        void flagTrueNoIssues() {
            ReviewResult result = new ReviewResult();
            result.setHasCriticalFlag(true);
            assertTrue(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("flag=true 且有 WARNING issues → true（flag 优先）")
        void flagTrueWithWarningIssues() {
            ReviewResult result = new ReviewResult();
            result.setHasCriticalFlag(true);
            result.addIssue(makeIssue(Severity.WARNING, "A.java", 10));
            assertTrue(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("flag=false 且有 CRITICAL issues → true（severity 兜底）")
        void flagFalseButCriticalIssue() {
            ReviewResult result = new ReviewResult();
            // flag 未设置（null），但有 CRITICAL issue
            result.addIssue(makeIssue(Severity.CRITICAL, "A.java", 10));
            assertTrue(result.hasCriticalIssues());
        }
    }

    @Nested
    @DisplayName("hasCriticalIssues - Raw Report Fallback")
    class RawReport {

        @Test
        @DisplayName("包含 '未发现严重问题' → false")
        void noCriticalIssues() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("# 代码审查报告\n\n## 严重问题\n未发现严重问题\n\n## 建议\n无");
            assertFalse(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("包含编号的严重问题条目 → true")
        void withCriticalIssues() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("# 代码审查报告\n\n## 严重问题\n1. [src/Main.java:42] SQL注入风险\n\n## 建议\n修复SQL拼接");
            assertTrue(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("空报告 → false")
        void emptyReport() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("");
            assertFalse(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("无严重问题章节 → false")
        void noCriticalSection() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("# 审查报告\n\n## 亮点\n代码结构清晰\n\n## 建议\n无");
            assertFalse(result.hasCriticalIssues());
        }

        @Test
        @DisplayName("结构化 issues 存在时优先于 raw report")
        void structuredTakesPriority() {
            ReviewResult result = new ReviewResult();
            result.addIssue(makeIssue(Severity.CRITICAL, "A.java", 1));
            result.setRawReport("# 审查报告\n\n## 严重问题\n未发现严重问题");
            // 结构化 issues 有 CRITICAL，raw report 说没有 → 应该返回 true
            assertTrue(result.hasCriticalIssues());
        }
    }

    @Nested
    @DisplayName("getSummary")
    class Summary {

        @Test
        @DisplayName("结构化结果的摘要")
        void structuredSummary() {
            ReviewResult result = new ReviewResult();
            result.addIssue(makeIssue(Severity.CRITICAL, "A.java", 1));
            result.addIssue(makeIssue(Severity.WARNING, "B.java", 2));
            result.addIssue(makeIssue(Severity.INFO, "C.java", 3));
            assertEquals("发现 3 个问题（1 个严重，1 个警告，1 个提示）", result.getSummary());
        }

        @Test
        @DisplayName("raw report 的摘要")
        void rawReportSummary() {
            ReviewResult result = new ReviewResult();
            result.setRawReport("一些文本");
            assertEquals("AI 代码审查", result.getSummary());
        }
    }

    @Nested
    @DisplayName("getIssuesBySeverity")
    class BySeverity {

        @Test
        @DisplayName("按严重级别过滤")
        void filterBySeverity() {
            ReviewResult result = new ReviewResult();
            result.addIssue(makeIssue(Severity.CRITICAL, "A.java", 1));
            result.addIssue(makeIssue(Severity.WARNING, "B.java", 2));
            result.addIssue(makeIssue(Severity.CRITICAL, "C.java", 3));

            assertEquals(2, result.getIssuesBySeverity(Severity.CRITICAL).size());
            assertEquals(1, result.getIssuesBySeverity(Severity.WARNING).size());
            assertEquals(0, result.getIssuesBySeverity(Severity.INFO).size());
        }
    }
}
