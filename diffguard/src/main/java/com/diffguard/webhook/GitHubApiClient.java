package com.diffguard.webhook;

import com.diffguard.config.ReviewConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * GitHub API 客户端，用于向 PR 发布审查评论。
 */
public class GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GITHUB_API_VERSION = "2022-11-28";

    private final HttpClient httpClient;
    private final String githubToken;

    public GitHubApiClient(ReviewConfig config) {
        this.githubToken = config.getWebhook().resolveGitHubToken();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 向指定 PR 发布评论。
     *
     * @param repoFullName 仓库全名（owner/repo）
     * @param prNumber     PR 编号
     * @param markdownBody Markdown 格式的评论内容
     */
    public void postComment(String repoFullName, int prNumber, String markdownBody) {
        try {
            String url = String.format("https://api.github.com/repos/%s/issues/%d/comments",
                    repoFullName, prNumber);

            String jsonBody = MAPPER.writeValueAsString(java.util.Map.of("body", markdownBody));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 201) {
                log.error("GitHub API 评论失败：status={}, repo={}, pr={}, body={}",
                        response.statusCode(), repoFullName, prNumber,
                        response.body().length() > 200 ? response.body().substring(0, 200) + "..." : response.body());
            } else {
                log.info("已发布审查评论：{}/pull/{}", repoFullName, prNumber);
            }
        } catch (Exception e) {
            log.error("GitHub API 调用异常：{}", e.getMessage(), e);
        }
    }
}
