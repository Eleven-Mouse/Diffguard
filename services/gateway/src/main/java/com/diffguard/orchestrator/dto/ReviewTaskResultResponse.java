package com.diffguard.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewTaskResultResponse {
    @JsonProperty("task_id")
    public String taskId;
    public String status;
    @JsonProperty("has_critical_flag")
    public boolean hasCriticalFlag;
    @JsonProperty("total_tokens_used")
    public int totalTokensUsed;
    @JsonProperty("review_duration_ms")
    public long reviewDurationMs;
    public String summary;
    public String error;
    public List<IssueResponseDto> issues = new ArrayList<>();
}
