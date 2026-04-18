package com.diffguard.agent.pipeline;

import com.diffguard.model.DiffFileEntry;
import com.diffguard.util.TokenEstimator;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Pipeline Diff 处理工具。
 * <p>
 * 负责 Diff 拼接和 Token 截断。
 */
class PipelineDiffHelper {

    PipelineDiffHelper() {}

    String concatenateDiffs(List<DiffFileEntry> entries) {
        return entries.stream()
                .map(e -> "--- 文件：" + e.getFilePath() + " ---\n" + e.getContent())
                .collect(Collectors.joining("\n\n"));
    }

    String truncateToTokenLimit(String content, int maxTokens, String provider) {
        String truncated = content;
        while (TokenEstimator.estimate(truncated, provider) > maxTokens && truncated.length() > 100) {
            truncated = truncated.substring(0, truncated.length() * 2 / 3);
        }
        return truncated + "\n\n... (内容已截断，超出 token 限制)";
    }
}
