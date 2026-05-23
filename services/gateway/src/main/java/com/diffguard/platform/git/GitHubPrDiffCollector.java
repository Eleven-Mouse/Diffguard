package com.diffguard.platform.git;

import com.diffguard.exception.DiffCollectionException;
import com.diffguard.platform.common.JacksonMapper;
import com.diffguard.platform.common.TokenEstimator;
import com.diffguard.platform.config.ReviewConfig;
import com.diffguard.review.model.DiffFileEntry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collect PR diffs directly from GitHub REST API.
 * Input format: owner/repo#123
 */
public final class GitHubPrDiffCollector {

    private static final Logger log = LoggerFactory.getLogger(GitHubPrDiffCollector.class);
    private static final Pattern PR_SPEC_PATTERN = Pattern.compile("^([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)#(\\d+)$");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final String API_BASE = "https://api.github.com";

    private GitHubPrDiffCollector() {}

    /**
     * Fetch PR file patches from GitHub and convert to DiffFileEntry list.
     */
    public static List<DiffFileEntry> collectPrDiff(Path projectDir, String prSpec, ReviewConfig config)
            throws DiffCollectionException {
        Matcher matcher = PR_SPEC_PATTERN.matcher(prSpec == null ? "" : prSpec.trim());
        if (!matcher.matches()) {
            throw new DiffCollectionException("PR 参数格式错误，期望 owner/repo#number，实际：" + prSpec);
        }
        String repo = matcher.group(1);
        int prNumber = Integer.parseInt(matcher.group(2));

        String token = resolveGitHubToken();
        if (token == null || token.isBlank()) {
            throw new DiffCollectionException("缺少 GitHub Token。请设置环境变量 GITHUB_TOKEN / GH_TOKEN / DIFFGUARD_GITHUB_TOKEN");
        }

        int maxFiles = config.getReview().getMaxDiffFiles();
        int maxTokens = config.getReview().getMaxTokensPerFile();
        String provider = config.getLlm().getProvider();

        List<DiffFileEntry> entries = new ArrayList<>();
        int page = 1;
        while (entries.size() < maxFiles) {
            JsonNode files = fetchPrFiles(repo, prNumber, page, token);
            if (!files.isArray() || files.isEmpty()) break;

            for (JsonNode fileNode : files) {
                if (entries.size() >= maxFiles) break;

                String status = text(fileNode, "status");
                if ("removed".equalsIgnoreCase(status)) {
                    continue;
                }

                String filePath = text(fileNode, "filename");
                if (filePath == null || filePath.isBlank() || shouldIgnore(filePath, config)) {
                    continue;
                }

                String patch = text(fileNode, "patch");
                if (patch == null || patch.isBlank()) {
                    // Binary / too large file doesn't include patch in GitHub API.
                    continue;
                }

                String diffContent = toUnifiedDiff(filePath, patch);
                int tokenCount = TokenEstimator.estimate(diffContent, provider);
                if (tokenCount > maxTokens) {
                    log.warn("PR 文件 {} 超出 token 限制（{} > {}），已跳过", filePath, tokenCount, maxTokens);
                    continue;
                }
                entries.add(new DiffFileEntry(filePath, diffContent, tokenCount));
            }

            if (files.size() < 100) break;
            page++;
        }

        if (entries.size() >= maxFiles) {
            log.warn("已达到最大差异文件数量（{}），跳过剩余文件", maxFiles);
        }
        return entries;
    }

    private static JsonNode fetchPrFiles(String repo, int prNumber, int page, String token) throws DiffCollectionException {
        String encodedRepo = URLEncoder.encode(repo, StandardCharsets.UTF_8).replace("%2F", "/");
        String url = API_BASE + "/repos/" + encodedRepo + "/pulls/" + prNumber + "/files?per_page=100&page=" + page;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new DiffCollectionException("GitHub API 请求失败（HTTP " + response.statusCode() + "）: " + response.body());
            }
            return JacksonMapper.MAPPER.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DiffCollectionException("调用 GitHub API 获取 PR diff 失败: " + e.getMessage(), e);
        }
    }

    private static String resolveGitHubToken() {
        String[] envs = {"GITHUB_TOKEN", "GH_TOKEN", "DIFFGUARD_GITHUB_TOKEN"};
        for (String env : envs) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String toUnifiedDiff(String filePath, String patch) {
        return "diff --git a/" + filePath + " b/" + filePath + "\n"
                + "--- a/" + filePath + "\n"
                + "+++ b/" + filePath + "\n"
                + patch
                + (patch.endsWith("\n") ? "" : "\n");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return "";
        return value.asText("");
    }

    private static boolean shouldIgnore(String filePath, ReviewConfig config) {
        if (filePath == null) return true;
        List<String> ignorePatterns = config.getIgnore().getFiles();
        for (String pattern : ignorePatterns) {
            if (matchGlob(filePath, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static final ConcurrentHashMap<String, Pattern> GLOB_PATTERN_CACHE = new ConcurrentHashMap<>();

    private static boolean matchGlob(String path, String pattern) {
        Pattern compiled = GLOB_PATTERN_CACHE.computeIfAbsent(pattern, GitHubPrDiffCollector::compileGlob);
        return compiled.matcher(path).matches();
    }

    private static Pattern compileGlob(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    if (i + 2 < pattern.length() && pattern.charAt(i + 2) == '/') {
                        regex.append("(.*/)?");
                        i += 3;
                    } else {
                        regex.append(".*");
                        i += 2;
                    }
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if ("\\[]{}()+^$|.".indexOf(c) >= 0) {
                regex.append('\\').append(c);
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }
}
