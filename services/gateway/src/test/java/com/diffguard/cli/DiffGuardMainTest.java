package com.diffguard.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DiffGuardMain")
class DiffGuardMainTest {

    @Test
    @DisplayName("root help should return 0 and print usage")
    void rootHelpShouldReturnZero() {
        Capture capture = captureStdIo(() -> {
            int exit = DiffGuardMain.execute(new String[]{"--help"});
            assertEquals(0, exit);
        });

        assertTrue(capture.out.contains("Usage: diffguard"), "help should include usage");
        assertTrue(capture.out.contains("review"), "help should list review command");
    }

    @Test
    @DisplayName("review without required --pr should return non-zero")
    void reviewWithoutRequiredPrShouldReturnNonZero() {
        Capture capture = captureStdIo(() -> {
            int exit = DiffGuardMain.execute(new String[]{"review"});
            assertNotEquals(0, exit);
        });

        String output = capture.out + capture.err;
        assertTrue(output.contains("--pr"), "error output should mention --pr");
    }

    @Test
    @DisplayName("version should return 0 and print DiffGuard version")
    void versionShouldReturnZero() {
        Capture capture = captureStdIo(() -> {
            int exit = DiffGuardMain.execute(new String[]{"--version"});
            assertEquals(0, exit);
        });

        assertTrue(capture.out.contains("DiffGuard"), "version output should contain DiffGuard");
    }

    @Test
    @DisplayName("unknown command should return non-zero")
    void unknownCommandShouldReturnNonZero() {
        Capture capture = captureStdIo(() -> {
            int exit = DiffGuardMain.execute(new String[]{"unknown-cmd"});
            assertNotEquals(0, exit);
        });

        assertTrue(!capture.out.isBlank() || !capture.err.isBlank(), "unknown command should produce diagnostics");
    }

    private static Capture captureStdIo(Runnable runnable) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            runnable.run();
            return new Capture(
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8)
            );
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private record Capture(String out, String err) {
    }
}
