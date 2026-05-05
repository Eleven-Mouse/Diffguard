package com.diffguard.domain.rules;

import com.diffguard.domain.review.model.DiffFileEntry;
import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 静态规则引擎。
 * 在 LLM 调用之前，用确定性规则快速扫描代码，零 LLM 开销。
 */
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<StaticRule> rules;

    public RuleEngine() {
        this.rules = List.of(
                new SqlInjectionRule(),
                new HardcodedSecretRule(),
                new DangerousFunctionRule(),
                new ComplexityRule()
        );
        log.info("RuleEngine initialized with {} rules", rules.size());
    }

    /**
     * 对 diff 文件执行全部静态规则。
     */
    public List<ReviewIssue> scan(List<DiffFileEntry> diffEntries) {
        List<ReviewIssue> issues = new ArrayList<>();
        for (DiffFileEntry entry : diffEntries) {
            for (StaticRule rule : rules) {
                issues.addAll(rule.check(entry));
            }
        }
        log.info("Static rule scan completed: {} issues found across {} files",
                issues.size(), diffEntries.size());
        return issues;
    }

    /**
     * 静态规则接口。
     */
    public interface StaticRule {
        String name();
        List<ReviewIssue> check(DiffFileEntry entry);
    }

    // ---- 内置规则实现 ----

    /** SQL 注入检测：字符串拼接 SQL */
    static class SqlInjectionRule implements StaticRule {
        private static final Pattern SQL_CONCAT = Pattern.compile(
                "(?i)(\"\\s*\\+\\s*\\w+.*(?:SELECT|INSERT|UPDATE|DELETE|FROM|WHERE))|" +
                "(?i)((?:SELECT|INSERT|UPDATE|DELETE|FROM|WHERE).*\\+\\s*\")"
        );

        @Override
        public String name() { return "sql_injection"; }

        @Override
        public List<ReviewIssue> check(DiffFileEntry entry) {
            List<ReviewIssue> issues = new ArrayList<>();
            if (!isJavaOrPython(entry.getFilePath())) return issues;

            String[] lines = entry.getContent().split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("+") || line.startsWith("-")) continue; // skip diff markers
                if (SQL_CONCAT.matcher(line).find()) {
                    issues.add(createIssue(entry.getFilePath(), i + 1,
                            "sql_injection",
                            "Potential SQL injection: string concatenation detected in SQL query. Use parameterized queries instead.",
                            "Use PreparedStatement with parameterized queries: `stmt.setString(1, value)`"
                    ));
                }
            }
            return issues;
        }
    }

    /** 硬编码密钥/Token 检测 */
    static class HardcodedSecretRule implements StaticRule {
        private static final Pattern SECRET_PATTERN = Pattern.compile(
                "(?i)(password|passwd|secret|api_key|apikey|token|private_key)\\s*[:=]\\s*[\"'][^\"']{8,}[\"']"
        );
        private static final Pattern AWS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");
        private static final Pattern GITHUB_TOKEN = Pattern.compile("gh[ps]_[A-Za-z0-9_]{36,}");

        @Override
        public String name() { return "hardcoded_secret"; }

        @Override
        public List<ReviewIssue> check(DiffFileEntry entry) {
            List<ReviewIssue> issues = new ArrayList<>();
            String[] lines = entry.getContent().split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("+") || line.startsWith("-")) continue;
                if (SECRET_PATTERN.matcher(line).find() ||
                    AWS_KEY.matcher(line).find() ||
                    GITHUB_TOKEN.matcher(line).find()) {
                    issues.add(createIssue(entry.getFilePath(), i + 1,
                            "hardcoded_secret",
                            "Hardcoded secret or API key detected. Use environment variables or secret management.",
                            "Move secrets to environment variables or use a secret manager (e.g., Vault, AWS Secrets Manager)"
                    ));
                }
            }
            return issues;
        }
    }

    /** 危险函数调用检测 */
    static class DangerousFunctionRule implements StaticRule {
        @Override
        public String name() { return "dangerous_function"; }

        @Override
        public List<ReviewIssue> check(DiffFileEntry entry) {
            List<ReviewIssue> issues = new ArrayList<>();
            if (!isJavaOrPython(entry.getFilePath())) return issues;

            String[] lines = entry.getContent().split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("+") || line.startsWith("-")) continue;

                // Runtime.exec()
                if (line.contains("Runtime.exec(") || line.contains("exec(")) {
                    issues.add(createIssue(entry.getFilePath(), i + 1,
                            "command_injection",
                            "Potential command injection via Runtime.exec(). Ensure input is sanitized.",
                            "Use ProcessBuilder with argument list instead of single string command"
                    ));
                }
                // ObjectOutputStream without validation
                if (line.contains("ObjectInputStream") && !line.contains("ValidatingObjectInputStream")) {
                    issues.add(createIssue(entry.getFilePath(), i + 1,
                            "insecure_deserialization",
                            "ObjectInputStream without validation may lead to insecure deserialization.",
                            "Use ValidatingObjectInputStream or implement ObjectInputFilter"
                    ));
                }
            }
            return issues;
        }
    }

    /** 方法复杂度检测：行数超过阈值 */
    static class ComplexityRule implements StaticRule {
        @Override
        public String name() { return "complexity"; }

        @Override
        public List<ReviewIssue> check(DiffFileEntry entry) {
            List<ReviewIssue> issues = new ArrayList<>();
            if (!entry.getFilePath().endsWith(".java")) return issues;

            String content = entry.getContent();
            // 简单检测: 统计嵌套深度 (缩进层级)
            String[] lines = content.split("\n");
            int maxIndent = 0;
            int maxIndentLine = 0;
            for (int i = 0; i < lines.length; i++) {
                int indent = countLeadingSpaces(lines[i]);
                if (indent > maxIndent) {
                    maxIndent = indent;
                    maxIndentLine = i + 1;
                }
            }
            // 4 空格 = 1 级缩进, 超过 6 级 (24 空格) 告警
            if (maxIndent > 24) {
                issues.add(createIssue(entry.getFilePath(), maxIndentLine,
                        "high_nesting_depth",
                        "Code nesting depth exceeds 6 levels (indent: " + maxIndent + " spaces). Consider extracting methods.",
                        "Extract nested logic into separate methods to reduce cognitive complexity"
                ));
            }
            return issues;
        }

        private int countLeadingSpaces(String line) {
            int count = 0;
            for (char c : line.toCharArray()) {
                if (c == ' ') count++;
                else if (c == '\t') count += 4;
                else break;
            }
            return count;
        }
    }

    // ---- helpers ----

    static boolean isJavaOrPython(String path) {
        return path.endsWith(".java") || path.endsWith(".py");
    }

    static ReviewIssue createIssue(String file, int line, String type,
                                   String message, String suggestion) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSeverity(Severity.WARNING);
        issue.setFile(file);
        issue.setLine(line);
        issue.setType(type);
        issue.setMessage(message);
        issue.setSuggestion(suggestion);
        return issue;
    }
}
