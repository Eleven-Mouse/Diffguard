package com.diffguard.model;

import java.util.Locale;

public enum Severity {
    CRITICAL("严重", "Critical", "🔴"),
    WARNING("警告", "Warning", "🟡"),
    INFO("提示", "Info", "🔵");

    private final String zhLabel;
    private final String enLabel;
    private final String icon;

    Severity(String zhLabel, String enLabel, String icon) {
        this.zhLabel = zhLabel;
        this.enLabel = enLabel;
        this.icon = icon;
    }

    /**
     * 获取中文标签（向后兼容）。
     */
    public String getLabel() {
        return zhLabel;
    }

    /**
     * 根据语言获取标签。
     */
    public String getLabel(String language) {
        if (language == null) return zhLabel;
        String lang = language.toLowerCase(Locale.ROOT);
        if (lang.startsWith("en")) return enLabel;
        return zhLabel;
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
