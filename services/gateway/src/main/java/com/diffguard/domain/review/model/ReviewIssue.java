package com.diffguard.domain.review.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewIssue {

    private Severity severity = Severity.INFO;
    private String file = "";
    private int line;
    private String type = "";
    private String message = "";
    private String suggestion = "";

    @JsonProperty("severity")
    public String getSeverityRaw() {
        return severity.getLabel();
    }

    @JsonProperty("severity")
    public void setSeverityRaw(String severity) {
        this.severity = Severity.fromString(severity);
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s:%d %s - %s", severity.getLabel(), file, line, type, message);
    }
}
