package com.diffguard.domain.rules;

import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuleEngine")
class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    private DiffFileEntry entry(String path, String content) {
        return new DiffFileEntry(path, content, content.length());
    }

    @Nested
    @DisplayName("SQL 注入检测 (SqlInjectionRule)")
    class SqlInjectionRuleTest {

        @Test
        @DisplayName("检测字符串拼接 SELECT 语句")
        void detectsSqlConcatenation() {
            String content = "String query = \" + userId + \" WHERE id = 1";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertFalse(issues.isEmpty(), "Should detect SQL injection");
            assertTrue(issues.stream().anyMatch(i -> "sql_injection".equals(i.getType())));
        }

        @Test
        @DisplayName("检测 INSERT 语句拼接")
        void detectsInsertConcatenation() {
            String content = "String sql = \"INSERT INTO users VALUES(\" + val + \")\";";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertTrue(issues.stream().anyMatch(i -> "sql_injection".equals(i.getType())));
        }

        @Test
        @DisplayName("安全代码不触发 SQL 注入规则")
        void safeCodeNoAlert() {
            String content = "String query = \"SELECT * FROM users WHERE id = ?\";";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertTrue(issues.stream().noneMatch(i -> "sql_injection".equals(i.getType())));
        }

        @Test
        @DisplayName("非 Java/Python 文件跳过检查")
        void skipsNonJavaPythonFiles() {
            String content = "String query = \"SELECT * FROM users WHERE id = \" + userId;";
            List<ReviewIssue> issues = engine.scan(List.of(entry("config.xml", content)));

            assertTrue(issues.stream().noneMatch(i -> "sql_injection".equals(i.getType())));
        }

        @Test
        @DisplayName("Python 文件也检测 SQL 注入")
        void detectsSqlInjectionInPython() {
            String content = "query = \" + user_id + \" WHERE id = 1";
            List<ReviewIssue> issues = engine.scan(List.of(entry("service.py", content)));

            assertTrue(issues.stream().anyMatch(i -> "sql_injection".equals(i.getType())));
        }

        @Test
        @DisplayName("检测 diff 新增行中的 SQL 注入")
        void detectsSqlInDiffAddedLine() {
            String content = "+String query = \"SELECT * FROM \" + table + \" WHERE id = 1\";";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertTrue(issues.stream().anyMatch(i -> "sql_injection".equals(i.getType())));
        }

        @Test
        @DisplayName("跳过以 - 开头的 diff 标记行")
        void skipsDiffMarkerMinus() {
            String content = "-String query = \"SELECT * FROM users WHERE id = \" + userId;";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertTrue(issues.stream().noneMatch(i -> "sql_injection".equals(i.getType())));
        }
    }

    @Nested
    @DisplayName("硬编码密钥检测 (HardcodedSecretRule)")
    class HardcodedSecretRuleTest {

        @Test
        @DisplayName("检测硬编码密码")
        void detectsHardcodedPassword() {
            String content = "password = \"mylongpassword123\";\napi_key = \"sk-12345678abcdefgh\";";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Config.java", content)));

            assertTrue(issues.stream().anyMatch(i -> "hardcoded_secret".equals(i.getType())));
        }

        @Test
        @DisplayName("检测 AWS Access Key")
        void detectsAwsKey() {
            String content = "String awsKey = \"AKIAIOSFODNN7EXAMPLE\";";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Config.java", content)));

            assertTrue(issues.stream().anyMatch(i -> "hardcoded_secret".equals(i.getType())));
        }

        @Test
        @DisplayName("检测 GitHub Token")
        void detectsGitHubToken() {
            String content = "String token = \"ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij1234\";";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Config.java", content)));

            assertTrue(issues.stream().anyMatch(i -> "hardcoded_secret".equals(i.getType())));
        }

        @Test
        @DisplayName("检测 api_key 赋值")
        void detectsApiKeyAssignment() {
            String content = "api_key = \"sk-12345678abcdefgh\"";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Config.java", content)));

            assertTrue(issues.stream().anyMatch(i -> "hardcoded_secret".equals(i.getType())));
        }

        @Test
        @DisplayName("短于 8 字符的值不触发检测")
        void shortValueNotDetected() {
            String content = "password = \"short\"";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Config.java", content)));

            assertTrue(issues.stream().noneMatch(i -> "hardcoded_secret".equals(i.getType())));
        }

        @Test
        @DisplayName("环境变量引用不触发检测")
        void envVariableNotDetected() {
            String content = "String password = System.getenv(\"DB_PASSWORD\");";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Config.java", content)));

            assertTrue(issues.stream().noneMatch(i -> "hardcoded_secret".equals(i.getType())));
        }
    }

    @Nested
    @DisplayName("危险函数检测 (DangerousFunctionRule)")
    class DangerousFunctionRuleTest {

        @Test
        @DisplayName("检测 Runtime.exec() 调用")
        void detectsRuntimeExec() {
            String content = "Runtime.getRuntime().exec(cmd);";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertTrue(issues.stream().anyMatch(i -> "command_injection".equals(i.getType())));
        }

        @Test
        @DisplayName("检测 ObjectInputStream 不安全反序列化")
        void detectsObjectInputStream() {
            String content = "ObjectInputStream ois = new ObjectInputStream(inputStream);";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertTrue(issues.stream().anyMatch(i -> "insecure_deserialization".equals(i.getType())));
        }

        @Test
        @DisplayName("ValidatingObjectInputStream 不触发告警")
        void validatingStreamIsSafe() {
            String content = "ValidatingObjectInputStream ois = new ValidatingObjectInputStream(inputStream);";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertTrue(issues.stream().noneMatch(i -> "insecure_deserialization".equals(i.getType())));
        }

        @Test
        @DisplayName("非 Java/Python 文件跳过检查")
        void skipsNonJavaPython() {
            String content = "Runtime.getRuntime().exec(cmd);";
            List<ReviewIssue> issues = engine.scan(List.of(entry("script.sh", content)));

            assertTrue(issues.stream().noneMatch(i -> "command_injection".equals(i.getType())));
        }
    }

    @Nested
    @DisplayName("代码复杂度检测 (ComplexityRule)")
    class ComplexityRuleTest {

        @Test
        @DisplayName("超过 24 空格缩进触发告警")
        void detectsHighNesting() {
            String content = "class A {\n" +
                    "    void m() {\n" +
                    "        if (true) {\n" +
                    "            if (true) {\n" +
                    "                if (true) {\n" +
                    "                    if (true) {\n" +
                    "                        if (true) {\n" +
                    "                            if (true) {\n" +
                    "                                deeplyNested();\n" +
                    "                            }\n" +
                    "                        }\n" +
                    "                    }\n" +
                    "                }\n" +
                    "            }\n" +
                    "        }\n" +
                    "    }\n" +
                    "}";
            List<ReviewIssue> issues = engine.scan(List.of(entry("DeepNesting.java", content)));

            assertTrue(issues.stream().anyMatch(i -> "high_nesting_depth".equals(i.getType())));
        }

        @Test
        @DisplayName("正常缩进不触发告警")
        void normalNestingNoAlert() {
            String content = "class A {\n    void m() {\n        doStuff();\n    }\n}";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Normal.java", content)));

            assertTrue(issues.stream().noneMatch(i -> "high_nesting_depth".equals(i.getType())));
        }

        @Test
        @DisplayName("非 Java 文件跳过复杂度检查")
        void skipsNonJavaFiles() {
            String content = "    ".repeat(30) + "deeply_nested_call()";
            List<ReviewIssue> issues = engine.scan(List.of(entry("script.py", content)));

            assertTrue(issues.stream().noneMatch(i -> "high_nesting_depth".equals(i.getType())));
        }

        @Test
        @DisplayName("Tab 缩进按 4 空格计算")
        void tabIndentation() {
            // 7 tabs = 28 spaces > 24 threshold
            String content = "class A {\n\t\t\t\t\t\t\tdeep();\n}";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Tabbed.java", content)));

            assertTrue(issues.stream().anyMatch(i -> "high_nesting_depth".equals(i.getType())));
        }
    }

    @Nested
    @DisplayName("scan 方法整体行为")
    class ScanMethodTest {

        @Test
        @DisplayName("空 diff 列表返回空 issues")
        void emptyDiffReturnsEmpty() {
            List<ReviewIssue> issues = engine.scan(Collections.emptyList());
            assertTrue(issues.isEmpty());
        }

        @Test
        @DisplayName("空内容文件返回空 issues")
        void emptyContentReturnsEmpty() {
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", "")));
            assertTrue(issues.isEmpty());
        }

        @Test
        @DisplayName("多条规则同时触发")
        void multipleRulesTriggered() {
            String content = "Runtime.getRuntime().exec(cmd);\n" +
                    "String query = \" + userId + \" WHERE id = 1";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertTrue(issues.size() >= 2, "Should have at least 2 issues from different rules");
        }

        @Test
        @DisplayName("多个文件分别扫描")
        void multipleFilesScanned() {
            String sqlContent = "String query = \" + id + \" FROM users WHERE 1=1";
            String safeContent = "int x = 1;";
            List<ReviewIssue> issues = engine.scan(List.of(
                    entry("A.java", sqlContent),
                    entry("B.java", safeContent)
            ));

            long sqlIssues = issues.stream()
                    .filter(i -> "sql_injection".equals(i.getType()))
                    .count();
            assertEquals(1, sqlIssues);
        }
    }

    @Nested
    @DisplayName("Issue 属性验证")
    class IssueAttributeTest {

        @Test
        @DisplayName("Issue severity 为 WARNING")
        void issueSeverityIsWarning() {
            String content = "Runtime.getRuntime().exec(cmd);";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertFalse(issues.isEmpty());
            for (ReviewIssue issue : issues) {
                assertEquals(Severity.WARNING, issue.getSeverity());
            }
        }

        @Test
        @DisplayName("Issue file 字段设置正确")
        void issueFileFieldSet() {
            String content = "Runtime.getRuntime().exec(cmd);";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertFalse(issues.isEmpty());
            assertEquals("Service.java", issues.get(0).getFile());
        }

        @Test
        @DisplayName("Issue 包含 suggestion")
        void issueHasSuggestion() {
            String content = "Runtime.getRuntime().exec(cmd);";
            List<ReviewIssue> issues = engine.scan(List.of(entry("Service.java", content)));

            assertFalse(issues.isEmpty());
            assertNotNull(issues.get(0).getSuggestion());
            assertFalse(issues.get(0).getSuggestion().isEmpty());
        }
    }

    @Nested
    @DisplayName("stripDiffMarker 辅助方法")
    class StripDiffMarkerTest {

        @Test
        @DisplayName("新增行去除 + 前缀后扫描")
        void addedLineStripsMarker() {
            String line = "+String query = \"SELECT * FROM users WHERE id = \" + userId;";
            String stripped = RuleEngine.stripDiffMarker(line);
            assertNotNull(stripped);
            assertTrue(stripped.startsWith("String query"));
        }

        @Test
        @DisplayName("删除行被跳过")
        void removedLineSkipped() {
            String line = "-String query = \"SELECT * FROM users WHERE id = \" + userId;";
            assertNull(RuleEngine.stripDiffMarker(line));
        }

        @Test
        @DisplayName("diff 元数据行被跳过")
        void metadataLinesSkipped() {
            assertNull(RuleEngine.stripDiffMarker("--- a/Service.java"));
            assertNull(RuleEngine.stripDiffMarker("+++ b/Service.java"));
            assertNull(RuleEngine.stripDiffMarker("@@ -10,5 +10,6 @@"));
            assertNull(RuleEngine.stripDiffMarker("diff --git a/Service.java b/Service.java"));
            assertNull(RuleEngine.stripDiffMarker("index abc1234..def5678 100644"));
        }

        @Test
        @DisplayName("上下文行正常扫描")
        void contextLineScanned() {
            String line = "String query = \"SELECT * FROM users WHERE id = \" + userId;";
            assertEquals(line, RuleEngine.stripDiffMarker(line));
        }
    }

    @Nested
    @DisplayName("辅助方法")
    class HelperMethodTest {

        @Test
        @DisplayName("isJavaOrPython 识别 .java 文件")
        void javaFileRecognized() {
            assertTrue(RuleEngine.isJavaOrPython("Test.java"));
        }

        @Test
        @DisplayName("isJavaOrPython 识别 .py 文件")
        void pythonFileRecognized() {
            assertTrue(RuleEngine.isJavaOrPython("test.py"));
        }

        @Test
        @DisplayName("isJavaOrPython 对其他文件返回 false")
        void otherFileNotRecognized() {
            assertFalse(RuleEngine.isJavaOrPython("test.js"));
            assertFalse(RuleEngine.isJavaOrPython("test.go"));
            assertFalse(RuleEngine.isJavaOrPython("config.xml"));
        }
    }
}
