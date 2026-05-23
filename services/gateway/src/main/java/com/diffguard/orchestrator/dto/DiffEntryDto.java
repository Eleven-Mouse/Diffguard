package com.diffguard.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DiffEntryDto {
    @JsonProperty("file_path")
    public String filePath;
    public String content;
    @JsonProperty("token_count")
    public int tokenCount;
}
