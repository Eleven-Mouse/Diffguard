package com.diffguard.agent.pipeline;

import com.diffguard.agent.pipeline.model.AggregatedReview;
import com.diffguard.model.IssueRecord;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.model.Severity;

/**
 * Pipeline 结果转换器。
 * <p>
 * 将 AggregatedReview (Agent DTO) 转换为 ReviewResult (通用模型)。
 */
class PipelineResultConverter {

    PipelineResultConverter() {}

    ReviewResult convert(AggregatedReview aggregated, int fileCount) {
        ReviewResult result = new ReviewResult();
        result.setTotalFilesReviewed(fileCount);

        if (aggregated == null) {
            return result;
        }

        if (Boolean.TRUE.equals(aggregated.has_critical())) {
            result.setHasCriticalFlag(true);
        }

        if (aggregated.issues() != null) {
            for (IssueRecord ir : aggregated.issues()) {
                ReviewIssue issue = new ReviewIssue();
                issue.setSeverity(Severity.fromString(ir.severity()));
                issue.setFile(ir.file() != null ? ir.file() : "");
                issue.setLine(ir.line());
                issue.setType(ir.type() != null ? ir.type() : "");
                issue.setMessage(ir.message() != null ? ir.message() : "");
                issue.setSuggestion(ir.suggestion() != null ? ir.suggestion() : "");
                result.addIssue(issue);
            }
        }

        return result;
    }
}
