package com.diffguard.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewTaskCreateResponse {
    @JsonProperty("task_id")
    public String taskId;
    public String status;
    @JsonProperty("review_mode")
    public String reviewMode;
    @JsonProperty("created_at")
    public long createdAt;
}
