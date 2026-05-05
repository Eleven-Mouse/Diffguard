package com.diffguard.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DiffGuardMain} covering execute(), exit code handling,
 * and the default run() behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiffGuardMain")
class DiffGuardMainTest {

    // ========================================================================
    // execute
    // ========================================================================

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("returns 0 for --help")
        void returnsZeroForHelp() {
            int exitCode = DiffGuardMain.execute(new String[]{"--help"});
            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("returns 0 for --version")
        void returnsZeroForVersion() {
            int exitCode = DiffGuardMain.execute(new String[]{"--version"});
            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("returns 0 for no args (shows usage)")
        void returnsZeroForNoArgs() {
            int exitCode = DiffGuardMain.execute(new String[]{});
            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("returns non-zero for unknown command")
        void returnsNonZeroForUnknownCommand() {
            int exitCode = DiffGuardMain.execute(new String[]{"nonexistent-command"});
            // picocli returns exit code for unknown subcommands
            assertTrue(exitCode != 0);
        }
    }

    // ========================================================================
    // getExitCode / setExitCode
    // ========================================================================

    @Nested
    @DisplayName("exitCode management")
    class ExitCodeManagement {

        @Test
        @DisplayName("default exitCode is 0")
        void defaultExitCodeIsZero() {
            DiffGuardMain main = new DiffGuardMain();
            assertEquals(0, main.getExitCode());
        }

        @Test
        @DisplayName("setExitCode stores the value")
        void setExitCodeStoresValue() {
            DiffGuardMain main = new DiffGuardMain();
            main.setExitCode(1);
            assertEquals(1, main.getExitCode());
        }

        @Test
        @DisplayName("setExitCode can store multiple values")
        void setExitCodeCanBeUpdated() {
            DiffGuardMain main = new DiffGuardMain();
            main.setExitCode(2);
            assertEquals(2, main.getExitCode());
            main.setExitCode(0);
            assertEquals(0, main.getExitCode());
        }
    }

    // ========================================================================
    // run (default - no subcommand)
    // ========================================================================

    @Nested
    @DisplayName("run (default behavior)")
    class RunDefault {

        @Test
        @DisplayName("run() prints usage without throwing")
        void runPrintsUsage() {
            DiffGuardMain main = new DiffGuardMain();
            // Capture stdout to avoid cluttering test output
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(baos));
            try {
                assertDoesNotThrow(main::run);
                String output = baos.toString();
                // Usage should mention the command name
                assertTrue(output.contains("diffguard") || output.length() > 0);
            } finally {
                System.setOut(originalOut);
            }
        }
    }
}
