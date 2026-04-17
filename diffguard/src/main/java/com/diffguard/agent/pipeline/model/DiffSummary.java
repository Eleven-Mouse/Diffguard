package com.diffguard.agent.pipeline.model;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * Stage 1 输出：Diff 变更总结。
 */
public record DiffSummary(
        @Description("2-4 句话总结本次代码变更的主要内容和目的")
        String summary,
        @Description("变更涉及的文件列表")
        List<String> changedFiles,
        @Description("变更类型：new_feature, bug_fix, refactor, test, config, docs 等")
        List<String> changeTypes,
        @Description("预估风险等级 1-5（1=低风险，5=高风险）")
        int estimatedRiskLevel
) {}
