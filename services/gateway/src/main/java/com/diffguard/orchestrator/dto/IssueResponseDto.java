package com.diffguard.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IssueResponseDto {
    public String severity;
    public String file;
    public int line;
    public String type;
    public String message;
    public String suggestion;
}
