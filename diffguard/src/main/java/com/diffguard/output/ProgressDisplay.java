package com.diffguard.output;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static com.diffguard.output.AnsiColors.*;

public class ProgressDisplay {

    private static final String VERSION = loadVersion();

    private static final String[] SPINNER = {"\u280B", "\u2819", "\u2839", "\u2838", "\u283C", "\u2834", "\u2826", "\u2827", "\u2807", "\u280F"};
    private static final AtomicInteger spinnerIndex = new AtomicInteger(0);

    private static String loadVersion() {
        try (InputStream is = ProgressDisplay.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return "v" + props.getProperty("version", "unknown");
            }
        } catch (IOException ignored) {}
        return "v1.0.0";
    }

    private static String spinner() {
        String frame = SPINNER[spinnerIndex.getAndIncrement() % SPINNER.length];
        return CYAN + frame + RESET;
    }

    /**
     * 打印启动横幅。
     */
    public static void printBanner() {
        System.out.println();
        System.out.println(CYAN + BOLD + "  ╔════════════════════════════════════════════════════════╗" + RESET);
        System.out.println(CYAN + BOLD + "  ║    (\\_/)                DiffGuard                      ║" + RESET);
        System.out.println(CYAN + BOLD + "  ║    (•.•)             AI coding review                  ║" + RESET);
        System.out.println(CYAN + BOLD + "  ║    / >⌨              \" + VERSION + \"                   ║" + RESET);
        System.out.println(CYAN + BOLD + "  ╚════════════════════════════════════════════════════════╝" + RESET);
        System.out.println();
    }

    /**
     * 打印差异收集阶段。
     */
    public static void printCollectingDiffs() {
        System.out.println("  " + spinner() + " " + CYAN + "正在收集暂存区变更..." + RESET);
    }

    /**
     * 打印差异收集结果。
     */
    public static void printDiffCollected(int fileCount, int totalLines) {
        System.out.println("  " + GREEN + "✓" + RESET + " 发现 " + BOLD + fileCount + RESET + " 个文件变更，" + BOLD + totalLines + RESET + " 行");
        System.out.println();
    }

    /**
     * 打印未找到变更的提示。
     */
    public static void printNoChanges() {
        System.out.println();
        System.out.println("  " + GRAY + "未找到暂存区变更。" + RESET);
        System.out.println("  " + GRAY + "请使用 git add <文件> 暂存你的变更" + RESET);
        System.out.println();
    }

    /**
     * 打印审查开始。
     */
    public static void printReviewStart(int batches) {
        System.out.println("  " + CYAN + "⟩" + RESET + " 正在进行审查...");
        System.out.println(DIM + "  │" + RESET);
    }

    /**
     * 打印等待状态的旋转动画。请重复调用以产生动画效果。
     */
    public static void printWaiting() {
        System.out.print("\r  " + DIM + "│" + RESET + " " + spinner() + " 正在分析代码...   ");
        System.out.flush();
    }

    /**
     * 清除等待旋转动画行。
     */
    public static void clearWaiting() {
        System.out.print("\r  " + DIM + "│" + RESET + "                           \r");
        System.out.flush();
    }

    /**
     * 打印频率限制重试提示。
     */
    public static void printRateLimitRetry(int attempt, int maxAttempts, int waitSeconds) {
        System.out.println("  " + DIM + "│" + RESET + " " + YELLOW + "⚡ 请求频率受限" + RESET + " — " + BOLD + waitSeconds + "秒" + RESET
                + GRAY + "后重试 (" + attempt + "/" + maxAttempts + ")" + RESET);
    }

    /**
     * 打印批次进度。
     */
    public static void printBatchProgress(int current, int total) {
        System.out.println("  " + DIM + "│" + RESET + " " + CYAN + "批次 " + BOLD + current + "/" + total + RESET + " 正在分析...");
    }

    /**
     * 打印审查完成。
     */
    public static void printReviewComplete(int issues) {
        System.out.println(DIM + "  │" + RESET);
        System.out.println("  " + DIM + "╰──" + RESET + " " + GREEN + "分析完成" + RESET
                + GRAY + "（发现 " + issues + " 个问题）" + RESET);
        System.out.println();
    }
}
