package com.diffguard.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewTaskStatusResponse {
    @JsonProperty("task_id")
    public String taskId;
    public String status;
    public String error;
    @JsonProperty("started_at")
    public Long startedAt;
    @JsonProperty("completed_at")
    public Long completedAt;
}
