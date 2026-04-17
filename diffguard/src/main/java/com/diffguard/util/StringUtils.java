package com.diffguard.util;

/**
 * 字符串工具方法。
 */
public final class StringUtils {

    private StringUtils() {}

    /**
     * 截断字符串到指定长度，超出部分以 "...(truncated)" 结尾。
     * 用于日志输出时限制错误消息长度。
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }
}
