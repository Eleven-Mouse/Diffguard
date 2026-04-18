package com.diffguard.output;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.diffguard.output.AnsiColors.*;

/**
 * 高质量终端 Spinner 渲染器。
 * <p>
 * 支持：
 * - 平滑 braille 动画 + 光标隐藏
 * - 动态文本更新
 * - 完成状态过渡（✔）
 * - 耗时显示
 */
public final class SpinnerRenderer {

    private static final String[] FRAMES = {
            "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    };
    private static final int INTERVAL_MS = 100;

    private static final AtomicInteger frameIndex = new AtomicInteger(0);
    private static final AtomicReference<String> currentLabel = new AtomicReference<>("");
    private static volatile Thread spinnerThread;
    private static volatile boolean running = false;
    private static volatile long startTime = 0;

    private SpinnerRenderer() {}

    public static void start(String label) {
        if (TerminalUI.isSilent()) return;
        stop();
        currentLabel.set(label);
        running = true;
        startTime = System.currentTimeMillis();
        TerminalUI.hideCursor();

        spinnerThread = new Thread(() -> {
            while (running) {
                String frame = FRAMES[frameIndex.getAndIncrement() % FRAMES.length];
                String elapsed = formatElapsed(startTime);
                String lbl = currentLabel.get();
                TerminalUI.print("\r  " + CYAN + frame + RESET + " " + lbl + " " + GRAY + elapsed + RESET + "  ");
                TerminalUI.flush();
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "spinner");
        spinnerThread.setDaemon(true);
        spinnerThread.start();
    }

    public static void updateLabel(String label) {
        currentLabel.set(label);
    }

    public static void succeed(String label) {
        if (TerminalUI.isSilent()) return;
        stop();
        TerminalUI.println("\r  " + GREEN + "✔" + RESET + " " + label);
    }

    public static void fail(String label) {
        if (TerminalUI.isSilent()) return;
        stop();
        TerminalUI.println("\r  " + RED + "✗" + RESET + " " + label);
    }

    public static void stop() {
        running = false;
        if (spinnerThread != null) {
            spinnerThread.interrupt();
            try { spinnerThread.join(200); } catch (InterruptedException ignored) {}
            spinnerThread = null;
        }
        if (!TerminalUI.isSilent()) {
            TerminalUI.print("\r" + ERASE_LINE + "\r");
            TerminalUI.flush();
            TerminalUI.showCursor();
        }
    }

    public static boolean isRunning() {
        return running;
    }

    private static String formatElapsed(long startMs) {
        long seconds = (System.currentTimeMillis() - startMs) / 1000;
        long mins = seconds / 60;
        long secs = seconds % 60;
        if (mins > 0) {
            return String.format("%dm%02ds", mins, secs);
        }
        return secs + "s";
    }
}
