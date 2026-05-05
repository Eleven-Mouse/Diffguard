package com.diffguard.infrastructure.output;

import java.io.PrintStream;

import static com.diffguard.infrastructure.output.AnsiColors.*;

/**
 * 统一的控制台输出层。所有终端 UI 输出都应通过此类，
 * 避免在业务代码中直接调用 System.out/System.err。
 */
public final class TerminalUI {

    private static volatile boolean silent = false;
    private static final int DEFAULT_WIDTH = 60;

    private TerminalUI() {}

    public static String sanitize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\u0000-\\u001F\\u007F&&[^\n\r\t]]", "");
    }

    public static void setSilent(boolean value) {
        silent = value;
    }

    public static boolean isSilent() {
        return silent;
    }

    public static void print(String text) {
        if (!silent) System.out.print(text);
    }

    public static void println(String text) {
        if (!silent) System.out.println(text);
    }

    public static void println() {
        if (!silent) System.out.println();
    }

    public static void printf(String format, Object... args) {
        if (!silent) System.out.printf(format, args);
    }

    public static void flush() {
        if (!silent) System.out.flush();
    }

    public static void error(String text) {
        if (!silent) System.err.println(text);
    }

    public static void errorPrintf(String format, Object... args) {
        if (!silent) System.err.printf(format, args);
    }

    public static String horizontalLine(char ch) {
        return String.valueOf(ch).repeat(DEFAULT_WIDTH);
    }

    public static String horizontalLine(char ch, int width) {
        return String.valueOf(ch).repeat(width);
    }

    public static String bold(String text) {
        return BOLD + text + RESET;
    }

    public static String dim(String text) {
        return DIM + text + RESET;
    }

    public static String colored(String text, String color) {
        return color + text + RESET;
    }

    public static String coloredBold(String text, String color) {
        return color + BOLD + text + RESET;
    }

    public static void hideCursor() {
        print(CURSOR_HIDE);
        flush();
    }

    public static void showCursor() {
        print(CURSOR_SHOW);
        flush();
    }

    public static void eraseLine() {
        print("\r" + ERASE_LINE);
        flush();
    }

    public static void printStage(int current, int total, String label) {
        if (silent) return;
        String stageNum = GRAY + String.format("%d/%d", current, total) + RESET;
        println("  " + dim("Stage " + stageNum + "  ") + CYAN + label + RESET);
    }
}
