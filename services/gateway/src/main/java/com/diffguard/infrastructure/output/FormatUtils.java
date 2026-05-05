package com.diffguard.infrastructure.output;

/**
 * 共享格式化工具方法。
 */
public final class FormatUtils {

    private FormatUtils() {}

    /**
     * 将毫秒数格式化为可读的时间字符串。
     */
    public static String formatDuration(long ms) {
        if (ms <= 0) return "-";
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }
}
