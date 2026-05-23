package com.diffguard.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewTaskCreateRequest {
    public String mode;
    @JsonProperty("project_dir")
    public String projectDir;
    @JsonProperty("tool_server_url")
    public String toolServerUrl;
    @JsonProperty("diff_entries")
    public List<DiffEntryDto> diffEntries;
    @JsonProperty("repo_name")
    public String repoName;
    @JsonProperty("pr_number")
    public Integer prNumber;
    @JsonProperty("head_sha")
    public String headSha;
    @JsonProperty("allowed_files")
    public List<String> allowedFiles;
}
