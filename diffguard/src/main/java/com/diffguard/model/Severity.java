package com.diffguard.model;

public enum Severity {
    CRITICAL("\u001B[31m", "CRITICAL", "🔴"),
    WARNING("\u001B[33m", "WARNING", "🟡"),
    INFO("\u001B[34m", "INFO", "🔵");

    private final String ansiColor;
    private final String label;
    private final String icon;

    Severity(String ansiColor, String label, String icon) {
        this.ansiColor = ansiColor;
        this.label = label;
        this.icon = icon;
    }

    public String getAnsiColor() {
        return ansiColor;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }

    public boolean shouldBlockCommit() {
        return this == CRITICAL;
    }

    public static Severity fromString(String value) {
        if (value == null) return INFO;
        return switch (value.toUpperCase().trim()) {
            case "CRITICAL", "HIGH", "ERROR" -> CRITICAL;
            case "WARNING", "MEDIUM" -> WARNING;
            default -> INFO;
        };
    }
}
