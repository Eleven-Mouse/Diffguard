package com.diffguard.model;

public enum Severity {
    CRITICAL("严重", "🔴"),
    WARNING("警告", "🟡"),
    INFO("提示", "🔵");

    private final String label;
    private final String icon;

    Severity(String label, String icon) {
        this.label = label;
        this.icon = icon;
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
