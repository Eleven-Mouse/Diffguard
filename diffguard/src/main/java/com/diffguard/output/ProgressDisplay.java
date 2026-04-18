package com.diffguard.output;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static com.diffguard.output.AnsiColors.*;

public class ProgressDisplay {

    private static final String VERSION = loadVersion();

    /** 全局静默开关，为 true 时所有输出方法不做任何操作 */
    private static volatile boolean silent = false;

    /**
     * 设置静默模式。Webhook 服务器模式下应设为 true，避免控制台输出干扰。
     */
    public static void setSilent(boolean value) {
        silent = value;
    }

    private static final String[] SPINNER = {"⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏"};
    private static final AtomicInteger spinnerIndex = new AtomicInteger(0);

    /** 后台 spinner 线程 */
    private static volatile Thread spinnerThread;
    private static volatile boolean spinnerRunning = false;
    private static volatile long spinnerStartTime = 0;

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

    private static String formatElapsed(long startMs) {
        long seconds = (System.currentTimeMillis() - startMs) / 1000;
        long mins = seconds / 60;
        long secs = seconds % 60;
        if (mins > 0) {
            return GRAY + String.format("%dm%02ds", mins, secs) + RESET;
        }
        return GRAY + secs + "s" + RESET;
    }

    /**
     * 启动后台 spinner 动画线程，持续显示旋转动画和已用时间。
     */
    public static void startSpinner() {
        if (silent) return;
        stopSpinner(); // 确保没有残留线程
        spinnerRunning = true;
        spinnerStartTime = System.currentTimeMillis();
        spinnerThread = new Thread(() -> {
            while (spinnerRunning) {
                String elapsed = formatElapsed(spinnerStartTime);
                System.out.print("\r  " + DIM + "│" + RESET + " " + spinner() + " 正在分析代码... " + elapsed + "  ");
                System.out.flush();
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "spinner");
        spinnerThread.setDaemon(true);
        spinnerThread.start();
    }

    /**
     * 停止 spinner 动画并清除动画行。
     */
    public static void stopSpinner() {
        if (silent) return;
        spinnerRunning = false;
        if (spinnerThread != null) {
            spinnerThread.interrupt();
            try { spinnerThread.join(200); } catch (InterruptedException ignored) {}
            spinnerThread = null;
        }
        // 清除 spinner 行
        System.out.print("\r  " + DIM + "│" + RESET + "                              \r");
        System.out.flush();
    }

    /**
     * 打印启动横幅。
     */
    public static void printBanner() {
        if (silent) return;
        System.out.println();
        System.out.println(CYAN + BOLD + "  ╔════════════════════════════════════════════════════════╗" + RESET);
        System.out.println(CYAN + BOLD + "  ║    (\\_/)                DiffGuard                      ║" + RESET);
        System.out.println(CYAN + BOLD + "  ║    (•.•)             AI coding review                  ║" + RESET);
        System.out.println(CYAN + BOLD + "  ║    / >⌨                  " + VERSION + "                        ║" + RESET);
        System.out.println(CYAN + BOLD + "  ╚════════════════════════════════════════════════════════╝" + RESET);
        System.out.println();
    }

    /**
     * 打印差异收集阶段。
     */
    public static void printCollectingDiffs() {
        if (silent) return;
        System.out.println("  " + spinner() + " " + CYAN + "正在收集暂存区变更..." + RESET);
    }

    /**
     * 打印差异收集结果。
     */
    public static void printDiffCollected(int fileCount, int totalLines) {
        if (silent) return;
        System.out.println("  " + GREEN + "✓" + RESET + " 发现 " + BOLD + fileCount + RESET + " 个文件变更，" + BOLD + totalLines + RESET + " 行");
        System.out.println();
    }

    /**
     * 打印未找到变更的提示。
     */
    public static void printNoChanges() {
        if (silent) return;
        System.out.println();
        System.out.println("  " + GRAY + "未找到暂存区变更。" + RESET);
        System.out.println("  " + GRAY + "请使用 git add <文件> 暂存你的变更" + RESET);
        System.out.println();
    }

    /**
     * 打印审查开始。
     */
    public static void printReviewStart(int batches) {
        if (silent) return;
        System.out.println("  " + CYAN + "⟩" + RESET + " 正在进行审查...");
        System.out.println(DIM + "  │" + RESET);
    }

    /**
     * 打印等待状态的旋转动画。请重复调用以产生动画效果。
     * @deprecated 使用 {@link #startSpinner()} / {@link #stopSpinner()} 替代
     */
    @Deprecated
    public static void printWaiting() {
        startSpinner();
    }

    /**
     * 清除等待旋转动画行。
     * @deprecated 使用 {@link #stopSpinner()} 替代
     */
    @Deprecated
    public static void clearWaiting() {
        stopSpinner();
    }

    /**
     * 打印频率限制重试提示。
     */
    public static void printRateLimitRetry(int attempt, int maxAttempts, int waitSeconds) {
        printRetry("请求频率受限", attempt, maxAttempts, waitSeconds);
    }

    /**
     * 打印服务端错误重试提示。
     */
    public static void printServerErrorRetry(int attempt, int maxAttempts, int waitSeconds) {
        printRetry("服务端临时错误", attempt, maxAttempts, waitSeconds);
    }

    private static void printRetry(String reason, int attempt, int maxAttempts, int waitSeconds) {
        if (silent) return;
        // 先暂停 spinner，打印重试信息，再重新启动
        boolean wasRunning = spinnerRunning;
        stopSpinner();
        System.out.println("  " + DIM + "│" + RESET + " " + YELLOW + "⚡ " + reason + RESET + " — " + BOLD + waitSeconds + "秒" + RESET
                + GRAY + "后重试 (" + attempt + "/" + maxAttempts + ")" + RESET);
        if (wasRunning) {
            startSpinner();
        }
    }

    /**
     * 打印批次进度。
     */
    public static void printBatchProgress(int current, int total) {
        if (silent) return;
        boolean wasRunning = spinnerRunning;
        stopSpinner();
        System.out.println("  " + DIM + "│" + RESET + " " + CYAN + "批次 " + BOLD + current + "/" + total + RESET + " 正在分析...");
        if (wasRunning) {
            startSpinner();
        }
    }

    /**
     * 打印审查完成。
     */
    public static void printReviewComplete(int issues) {
        if (silent) return;
        stopSpinner();
        System.out.println(DIM + "  │" + RESET);
        System.out.println("  " + DIM + "╰──" + RESET + " " + GREEN + "分析完成" + RESET
                + GRAY + "（发现 " + issues + " 个问题）" + RESET);
        System.out.println();
    }
}
