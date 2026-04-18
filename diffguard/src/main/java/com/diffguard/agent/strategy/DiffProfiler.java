package com.diffguard.agent.strategy;

import com.diffguard.model.DiffFileEntry;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Diff 分析器：分析 diff 条目，生成变更画像。
 * <p>
 * 基于文件路径、文件名模式、diff 内容中的关键字来识别变更类型和风险指标。
 */
public class DiffProfiler {

    // 关键字模式检测
    private static final Pattern DB_PATTERN = Pattern.compile(
            "(?i)(PreparedStatement|Statement|executeQuery|executeUpdate|INSERT|UPDATE|DELETE|SELECT|JdbcTemplate|Repository|DAO|EntityManager|createQuery|nativeQuery)");

    private static final Pattern SECURITY_PATTERN = Pattern.compile(
            "(?i)(password|secret|apiKey|api_key|token|credential|auth|login|encrypt|decrypt|cipher|SSL|TLS|certificate|Runtime\\.exec|ProcessBuilder)");

    private static final Pattern CONCURRENCY_PATTERN = Pattern.compile(
            "(?i)(synchronized|volatile|AtomicInteger|AtomicLong|ConcurrentHashMap|CountDownLatch|Semaphore|ThreadPool|CompletableFuture|Thread\\.|Runnable|@Lock|@Transactional)");

    private static final Pattern API_PATTERN = Pattern.compile(
            "(?i)(@GetMapping|@PostMapping|@PutMapping|@DeleteMapping|@RequestMapping|@RestController|@Controller|HttpServletRequest|HttpServletResponse|ResponseEntity|@PathVariable|@RequestBody)");

    /**
     * 分析 diff 条目列表，生成变更画像。
     */
    public static DiffProfile profile(List<DiffFileEntry> entries) {
        if (entries == null) {
            entries = List.of();
        }

        DiffProfile.Builder builder = DiffProfile.builder();

        int totalLines = 0;
        int totalTokens = 0;
        boolean hasDb = false;
        boolean hasSecurity = false;
        boolean hasConcurrency = false;
        boolean hasApi = false;

        for (DiffFileEntry entry : entries) {
            String path = entry.getFilePath();
            String content = entry.getContent();

            builder.filePath(path);
            totalLines += entry.getLineCount();
            totalTokens += entry.getTokenCount();

            // 分类文件
            DiffProfile.FileCategory category = categorizeFile(path);
            builder.addCategory(category, 1);

            // 提取类名
            extractClassName(path).ifPresent(builder::className);

            // 检测操作类型
            if (content != null) {
                if (DB_PATTERN.matcher(content).find()) {
                    hasDb = true;
                    builder.changedOperation("database");
                }
                if (SECURITY_PATTERN.matcher(content).find()) {
                    hasSecurity = true;
                    builder.changedOperation("security-sensitive");
                }
                if (CONCURRENCY_PATTERN.matcher(content).find()) {
                    hasConcurrency = true;
                    builder.changedOperation("concurrency");
                }
                if (API_PATTERN.matcher(content).find()) {
                    hasApi = true;
                    builder.changedOperation("api-endpoint");
                }
            }
        }

        builder.totalFiles(entries.size())
                .totalLines(totalLines)
                .totalTokens(totalTokens)
                .hasDatabaseOperations(hasDb)
                .hasSecuritySensitiveCode(hasSecurity)
                .hasConcurrencyCode(hasConcurrency)
                .hasExternalApiCalls(hasApi)
                .overallRisk(calculateRisk(entries.size(), totalLines, hasDb, hasSecurity, hasConcurrency));

        return builder.build();
    }

    static DiffProfile.FileCategory categorizeFile(String filePath) {
        if (filePath == null) return DiffProfile.FileCategory.OTHER;
        String lower = filePath.toLowerCase();

        // Extract filename for classification (avoid matching path segments like /resources/)
        int lastSlash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String fileName = lastSlash >= 0 ? lower.substring(lastSlash + 1) : lower;

        // Test files
        if (lower.contains("/test/") || fileName.endsWith("test.java") || fileName.endsWith("tests.java")) {
            return DiffProfile.FileCategory.TEST;
        }
        // Controller (check filename only to avoid matching /resources/ path)
        if (fileName.contains("controller") || fileName.contains("endpoint")) {
            return DiffProfile.FileCategory.CONTROLLER;
        }
        // DAO / Repository
        if (fileName.contains("dao") || fileName.contains("repository") || fileName.contains("mapper")) {
            return DiffProfile.FileCategory.DAO;
        }
        // Service
        if (fileName.contains("service") || fileName.contains("manager") || fileName.contains("handler")
                || fileName.contains("processor") || fileName.contains("engine")) {
            return DiffProfile.FileCategory.SERVICE;
        }
        // Model / DTO
        if (fileName.contains("model") || fileName.contains("dto") || fileName.contains("entity")
                || fileName.contains("vo") || fileName.contains("request") || fileName.contains("response")) {
            return DiffProfile.FileCategory.MODEL;
        }
        // Config
        if (fileName.contains("config") || fileName.contains("configuration") || fileName.contains("properties")
                || fileName.endsWith(".yml") || fileName.endsWith(".yaml") || fileName.endsWith(".properties")
                || fileName.endsWith(".xml")) {
            return DiffProfile.FileCategory.CONFIG;
        }
        // Utility
        if (fileName.contains("util") || fileName.contains("helper") || fileName.contains("common")
                || fileName.contains("constant") || fileName.contains("exception")) {
            return DiffProfile.FileCategory.UTILITY;
        }

        return DiffProfile.FileCategory.OTHER;
    }

    private static java.util.Optional<String> extractClassName(String filePath) {
        if (filePath == null || !filePath.endsWith(".java")) return java.util.Optional.empty();
        int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String fileName = lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
        return java.util.Optional.of(fileName.replace(".java", ""));
    }

    private static DiffProfile.RiskLevel calculateRisk(int fileCount, int totalLines,
                                                        boolean hasDb, boolean hasSecurity,
                                                        boolean hasConcurrency) {
        int riskScore = 0;

        // Scale factor
        if (totalLines > 200) riskScore += 2;
        else if (totalLines > 50) riskScore += 1;

        if (fileCount > 5) riskScore += 1;

        // Risk multipliers
        if (hasDb) riskScore += 2;
        if (hasSecurity) riskScore += 3;
        if (hasConcurrency) riskScore += 2;

        if (riskScore >= 6) return DiffProfile.RiskLevel.CRITICAL;
        if (riskScore >= 4) return DiffProfile.RiskLevel.HIGH;
        if (riskScore >= 2) return DiffProfile.RiskLevel.MEDIUM;
        return DiffProfile.RiskLevel.LOW;
    }
}
