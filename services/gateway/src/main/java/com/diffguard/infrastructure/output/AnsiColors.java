package com.diffguard.infrastructure.output;

/**
 * 集中管理 ANSI 转义码，避免在多个类中重复定义。
 */
public final class AnsiColors {

    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";
    public static final String GRAY = "\u001B[90m";

    public static final String BG_RED = "\u001B[41m";
    public static final String BG_YELLOW = "\u001B[43m";

    public static final String CURSOR_HIDE = "\u001B[?25l";
    public static final String CURSOR_SHOW = "\u001B[?25h";
    public static final String ERASE_LINE = "\u001B[2K";

    private AnsiColors() {}
}
