package com.diffguard.output;

public class ProgressDisplay {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GRAY = "\u001B[90m";
    private static final String DIM = "\u001B[2m";

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static int spinnerIndex = 0;

    private static String spinner() {
        String frame = SPINNER[spinnerIndex % SPINNER.length];
        spinnerIndex++;
        return CYAN + frame + RESET;
    }

    /**
     * Print the startup banner.
     */
    public static void printBanner() {
        System.out.println();
        System.out.println(CYAN + BOLD + "  ╔══════════════════════════════════════╗" + RESET);
        System.out.println(CYAN + BOLD + "  ║    (\\_/)    DiffGuard v1.0.0         ║" + RESET);
        System.out.println(CYAN + BOLD + "  ║   ( -.-)   AI-Powered Code Review    ║" + RESET);
        System.out.println(CYAN + BOLD + "  ║   /|☕|~                              ║" + RESET);
        System.out.println(CYAN + BOLD + "  ╚══════════════════════════════════════╝" + RESET);
        System.out.println();
    }

    /**
     * Print diff collection phase.
     */
    public static void printCollectingDiffs() {
        System.out.println("  " + spinner() + " " + CYAN + "Collecting staged changes..." + RESET);
    }

    /**
     * Print diff collection result.
     */
    public static void printDiffCollected(int fileCount, int totalLines) {
        System.out.println("  " + GREEN + "✓" + RESET + " Found " + BOLD + fileCount + RESET + " file(s) changed, " + BOLD + totalLines + RESET + " line(s)");
        System.out.println();
    }

    /**
     * Print no changes found.
     */
    public static void printNoChanges() {
        System.out.println();
        System.out.println("  " + GRAY + "No staged changes found." + RESET);
        System.out.println("  " + GRAY + "Stage your changes with: git add <file>" + RESET);
        System.out.println();
    }

    /**
     * Print review start.
     */
    public static void printReviewStart(int batches) {
        System.out.println("  " + CYAN + "⟩" + RESET + " Sending to AI for review...");
        System.out.println(DIM + "  │" + RESET);
    }

    /**
     * Print a spinner for waiting state. Call repeatedly to animate.
     */
    public static void printWaiting() {
        System.out.print("\r  " + DIM + "│" + RESET + " " + spinner() + " Analyzing code...   ");
        System.out.flush();
    }

    /**
     * Clear the waiting spinner line.
     */
    public static void clearWaiting() {
        System.out.print("\r  " + DIM + "│" + RESET + "                           \r");
        System.out.flush();
    }

    /**
     * Print rate limit retry.
     */
    public static void printRateLimitRetry(int attempt, int maxAttempts, int waitSeconds) {
        System.out.println("  " + DIM + "│" + RESET + " " + YELLOW + "⚡ Rate limited" + RESET + " — retrying in " + BOLD + waitSeconds + "s" + RESET
                + GRAY + " (" + attempt + "/" + maxAttempts + ")" + RESET);
    }

    /**
     * Print batch progress.
     */
    public static void printBatchProgress(int current, int total) {
        System.out.println("  " + DIM + "│" + RESET + " " + CYAN + "Batch " + BOLD + current + "/" + total + RESET + " analyzing...");
    }

    /**
     * Print review complete.
     */
    public static void printReviewComplete(int issues) {
        System.out.println(DIM + "  │" + RESET);
        System.out.println("  " + DIM + "╰──" + RESET + " " + GREEN + "Analysis complete" + RESET
                + GRAY + " (" + issues + " issue(s) found)" + RESET);
        System.out.println();
    }
}
