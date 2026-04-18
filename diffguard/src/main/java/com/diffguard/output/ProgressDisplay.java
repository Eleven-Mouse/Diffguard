package com.diffguard.output;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static com.diffguard.output.AnsiColors.*;

/**
 * CLI 进度显示。
 * 所有输出委托给 TerminalUI / SpinnerRenderer，不在本类中直接调用 System.out。
 */
public class ProgressDisplay {

    private static final String VERSION = loadVersion();

    private static final int TOTAL_STAGES = 4;

    public static void setSilent(boolean value) {
        TerminalUI.setSilent(value);
    }

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

    public static void printBanner() {
        if (TerminalUI.isSilent()) return;
        TerminalUI.println();
        TerminalUI.println("  " + CYAN + BOLD + "╭──────────────────────────────────────────────────╮" + RESET);
        TerminalUI.println("  " + CYAN + BOLD + "│" + RESET + "    (\\_/)           " + BOLD + "DiffGuard" + RESET
                + "                     " + CYAN + BOLD + "│" + RESET);
        TerminalUI.println("  " + CYAN + BOLD + "│" + RESET + "    (•.•)        " + DIM + "AI code review" + RESET
                + "                   " + CYAN + BOLD + "│" + RESET);
        TerminalUI.println("  " + CYAN + BOLD + "│" + RESET + "    / >⌨             " + GRAY + VERSION + RESET
                + "                       " + CYAN + BOLD + "│" + RESET);
        TerminalUI.println("  " + CYAN + BOLD + "╰──────────────────────────────────────────────────╯" + RESET);
        TerminalUI.println();
    }

    public static void printCollectingDiffs() {
        if (TerminalUI.isSilent()) return;
        TerminalUI.printStage(1, TOTAL_STAGES, "Collecting diff...");
    }

    public static void printDiffCollected(int fileCount, int totalLines) {
        if (TerminalUI.isSilent()) return;
        TerminalUI.println("  " + GREEN + "✔" + RESET + " Found "
                + BOLD + fileCount + RESET + " file(s), "
                + BOLD + totalLines + RESET + " line(s) changed");
        TerminalUI.println();
    }

    public static void printNoChanges() {
        if (TerminalUI.isSilent()) return;
        TerminalUI.println();
        TerminalUI.println("  " + GRAY + "No staged changes found." + RESET);
        TerminalUI.println("  " + GRAY + "Use git add <file> to stage your changes." + RESET);
        TerminalUI.println();
    }

    public static void printReviewStart(int batches) {
        if (TerminalUI.isSilent()) return;
        TerminalUI.printStage(3, TOTAL_STAGES, "AI analysis...");
    }

    @Deprecated
    public static void printWaiting() {
        startSpinner();
    }

    @Deprecated
    public static void clearWaiting() {
        stopSpinner();
    }

    public static synchronized void startSpinner() {
        SpinnerRenderer.start("Analyzing code...");
    }

    public static synchronized void stopSpinner() {
        SpinnerRenderer.stop();
    }

    public static void printRateLimitRetry(int attempt, int maxAttempts, int waitSeconds) {
        printRetry("Rate limited", attempt, maxAttempts, waitSeconds);
    }

    public static void printServerErrorRetry(int attempt, int maxAttempts, int waitSeconds) {
        printRetry("Server error", attempt, maxAttempts, waitSeconds);
    }

    private static void printRetry(String reason, int attempt, int maxAttempts, int waitSeconds) {
        if (TerminalUI.isSilent()) return;
        synchronized (ProgressDisplay.class) {
            boolean wasRunning = SpinnerRenderer.isRunning();
            SpinnerRenderer.stop();
            TerminalUI.println("  " + YELLOW + "⚡ " + reason + RESET + " — "
                    + BOLD + waitSeconds + "s" + RESET
                    + GRAY + " until retry (" + attempt + "/" + maxAttempts + ")" + RESET);
            if (wasRunning) {
                SpinnerRenderer.start("Analyzing code...");
            }
        }
    }

    public static void printBatchProgress(int current, int total) {
        if (TerminalUI.isSilent()) return;
        synchronized (ProgressDisplay.class) {
            boolean wasRunning = SpinnerRenderer.isRunning();
            SpinnerRenderer.stop();
            TerminalUI.println("  " + GRAY + "├─" + RESET + " Batch "
                    + BOLD + current + "/" + total + RESET + " analyzing...");
            if (wasRunning) {
                SpinnerRenderer.start("Analyzing code...");
            }
        }
    }

    public static void printReviewComplete(int issues) {
        if (TerminalUI.isSilent()) return;
        SpinnerRenderer.stop();
        TerminalUI.printStage(4, TOTAL_STAGES, "Generating report...");
        TerminalUI.println("  " + GREEN + "✔" + RESET + " Analysis complete"
                + GRAY + " (" + issues + " issue(s) found)" + RESET);
        TerminalUI.println();
    }
}
