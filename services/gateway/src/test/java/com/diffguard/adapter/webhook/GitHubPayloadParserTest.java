package com.diffguard.adapter.webhook;

import com.diffguard.exception.WebhookException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitHubPayloadParserTest {

    // ------------------------------------------------------------------
    // 正常解析
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("正常 payload 解析")
    class NormalParsing {

        @Test
        @DisplayName("完整 pull_request opened 事件")
        void parseOpenedEvent() throws WebhookException {
            String payload = """
                {
                  "action": "opened",
                  "number": 42,
                  "pull_request": {
                    "base": { "ref": "main" },
                    "head": {
                      "ref": "feature-branch",
                      "sha": "abc123def456",
                      "repo": { "full_name": "owner/repo" }
                    }
                  }
                }
                """;

            GitHubPayloadParser.ParsedPullRequest pr = GitHubPayloadParser.parse(payload);

            assertEquals("opened", pr.getAction());
            assertEquals("owner/repo", pr.getRepoFullName());
            assertEquals(42, pr.getPrNumber());
            assertEquals("main", pr.getBaseRef());
            assertEquals("feature-branch", pr.getHeadRef());
            assertEquals("abc123def456", pr.getHeadSha());
            assertTrue(pr.isRelevantAction());
        }

        @Test
        @DisplayName("synchronize 事件也是 relevant")
        void synchronizeIsRelevant() throws WebhookException {
            String payload = """
                {
                  "action": "synchronize",
                  "number": 10,
                  "pull_request": {
                    "base": { "ref": "main" },
                    "head": { "ref": "fix", "sha": "deadbeef", "repo": { "full_name": "a/b" } }
                  }
                }
                """;

            GitHubPayloadParser.ParsedPullRequest pr = GitHubPayloadParser.parse(payload);

            assertEquals("synchronize", pr.getAction());
            assertTrue(pr.isRelevantAction());
        }
    }

    // ------------------------------------------------------------------
    // action 过滤
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("action 过滤")
    class ActionFilter {

        @Test
        @DisplayName("closed 事件不 relevant")
        void closedNotRelevant() throws WebhookException {
            String payload = """
                {
                  "action": "closed",
                  "number": 5,
                  "pull_request": {
                    "base": { "ref": "main" },
                    "head": { "ref": "fix", "sha": "aaa", "repo": { "full_name": "a/b" } }
                  }
                }
                """;

            GitHubPayloadParser.ParsedPullRequest pr = GitHubPayloadParser.parse(payload);
            assertFalse(pr.isRelevantAction());
        }

        @Test
        @DisplayName("reopened 事件 relevant")
        void reopenedIsRelevant() throws WebhookException {
            String payload = """
                {
                  "action": "reopened",
                  "number": 5,
                  "pull_request": {
                    "base": { "ref": "main" },
                    "head": { "ref": "fix", "sha": "aaa", "repo": { "full_name": "a/b" } }
                  }
                }
                """;

            GitHubPayloadParser.ParsedPullRequest pr = GitHubPayloadParser.parse(payload);
            assertTrue(pr.isRelevantAction());
        }
    }

    // ------------------------------------------------------------------
    // 容错与回退
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("字段缺失容错")
    class MissingFields {

        @Test
        @DisplayName("head.repo.full_name 缺失时从 repository 回退")
        void repoFallback() throws WebhookException {
            String payload = """
                {
                  "action": "opened",
                  "number": 1,
                  "repository": { "full_name": "fallback/repo" },
                  "pull_request": {
                    "base": { "ref": "main" },
                    "head": { "ref": "feat", "sha": "abc" }
                  }
                }
                """;

            GitHubPayloadParser.ParsedPullRequest pr = GitHubPayloadParser.parse(payload);
            assertEquals("fallback/repo", pr.getRepoFullName());
        }

        @Test
        @DisplayName("空 JSON 对象 → 字段为默认值")
        void emptyJsonObject() throws WebhookException {
            GitHubPayloadParser.ParsedPullRequest pr = GitHubPayloadParser.parse("{}");
            assertEquals("", pr.getAction());
            assertEquals(0, pr.getPrNumber());
            assertEquals("", pr.getRepoFullName());
        }

        @Test
        @DisplayName("无效 JSON → 抛出 WebhookException")
        void invalidJson() {
            assertThrows(WebhookException.class, () -> GitHubPayloadParser.parse("not json"));
        }
    }
}
