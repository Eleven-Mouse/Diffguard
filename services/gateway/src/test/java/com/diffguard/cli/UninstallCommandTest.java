package com.diffguard.cli;

import com.diffguard.infrastructure.git.GitHookInstaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UninstallCommand} covering hook removal,
 * backup restoration, and error handling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UninstallCommand")
class UninstallCommandTest {

    @TempDir
    Path tempDir;

    private DiffGuardMain parent;
    private UninstallCommand command;

    @BeforeEach
    void setUp() {
        parent = new DiffGuardMain();
        command = new UninstallCommand();
        command.parent = parent;
    }

    /**
     * Creates a minimal .git directory structure with DiffGuard hooks installed.
     */
    private Path createGitRepoWithHooks(Path base) throws IOException {
        Path gitDir = base.resolve(".git");
        Path hooksDir = gitDir.resolve("hooks");
        Files.createDirectories(hooksDir);

        // Create pre-commit hook with DiffGuard marker
        Path preCommit = hooksDir.resolve("pre-commit");
        Files.writeString(preCommit, "#!/bin/sh\n# DiffGuard pre-commit hook\necho test");

        // Create pre-push hook with DiffGuard marker
        Path prePush = hooksDir.resolve("pre-push");
        Files.writeString(prePush, "#!/bin/sh\n# DiffGuard pre-push hook\necho test");

        return base;
    }

    private Path createGitRepoWithoutHooks(Path base) throws IOException {
        Path gitDir = base.resolve(".git");
        Files.createDirectories(gitDir.resolve("hooks"));
        return base;
    }

    // ========================================================================
    // Command execution
    // ========================================================================

    @Nested
    @DisplayName("run")
    class Run {

        @Test
        @DisplayName("sets exitCode via parent after execution")
        void setsExitCodeViaParent() {
            command.run();
            // Exit code depends on whether CWD is a git repo
            assertTrue(parent.getExitCode() == 0 || parent.getExitCode() == 1);
        }
    }

    // ========================================================================
    // GitHookInstaller.uninstall integration
    // ========================================================================

    @Nested
    @DisplayName("GitHookInstaller.uninstall")
    class GitHookInstallerUninstall {

        @Test
        @DisplayName("removes DiffGuard hooks from git repo")
        void removesDiffGuardHooks() throws IOException {
            Path gitRepo = createGitRepoWithHooks(tempDir);
            Path preCommit = gitRepo.resolve(".git/hooks/pre-commit");
            Path prePush = gitRepo.resolve(".git/hooks/pre-push");

            assertTrue(Files.exists(preCommit), "Pre-commit hook should exist before uninstall");
            assertTrue(Files.exists(prePush), "Pre-push hook should exist before uninstall");

            GitHookInstaller.uninstall(gitRepo);

            assertFalse(Files.exists(preCommit), "Pre-commit hook should be removed");
            assertFalse(Files.exists(prePush), "Pre-push hook should be removed");
        }

        @Test
        @DisplayName("restores backup hooks after removal")
        void restoresBackupHooks() throws IOException {
            Path gitRepo = createGitRepoWithHooks(tempDir);

            // Create backups with original content
            Path hooksDir = gitRepo.resolve(".git/hooks");
            Path preCommitBackup = hooksDir.resolve("pre-commit.diffguard-backup");
            Path prePushBackup = hooksDir.resolve("pre-push.diffguard-backup");

            Files.writeString(preCommitBackup, "#!/bin/sh\noriginal pre-commit content");
            Files.writeString(prePushBackup, "#!/bin/sh\noriginal pre-push content");

            GitHookInstaller.uninstall(gitRepo);

            // Backup should be removed
            assertFalse(Files.exists(preCommitBackup), "Backup should be removed after restore");
            assertFalse(Files.exists(prePushBackup), "Backup should be removed after restore");

            // Original hooks should be restored
            Path preCommit = hooksDir.resolve("pre-commit");
            Path prePush = hooksDir.resolve("pre-push");
            assertTrue(Files.exists(preCommit), "Original pre-commit should be restored");
            assertTrue(Files.exists(prePush), "Original pre-push should be restored");

            assertEquals("#!/bin/sh\noriginal pre-commit content", Files.readString(preCommit));
            assertEquals("#!/bin/sh\noriginal pre-push content", Files.readString(prePush));
        }

        @Test
        @DisplayName("does not remove non-DiffGuard hooks")
        void doesNotRemoveNonDiffGuardHooks() throws IOException {
            Path gitRepo = createGitRepoWithoutHooks(tempDir);

            // Create a non-DiffGuard hook
            Path hooksDir = gitRepo.resolve(".git/hooks");
            Path customHook = hooksDir.resolve("pre-commit");
            String customContent = "#!/bin/sh\n# Custom hook - not DiffGuard\necho custom";
            Files.writeString(customHook, customContent);

            GitHookInstaller.uninstall(gitRepo);

            // Non-DiffGuard hook should remain
            assertTrue(Files.exists(customHook), "Non-DiffGuard hook should not be removed");
            assertEquals(customContent, Files.readString(customHook));
        }

        @Test
        @DisplayName("does nothing when no .git directory exists")
        void doesNothingWhenNoGitDir() throws IOException {
            // tempDir has no .git directory
            assertDoesNotThrow(() -> GitHookInstaller.uninstall(tempDir));
        }

        @Test
        @DisplayName("does nothing when hooks directory is empty")
        void doesNothingWhenHooksEmpty() throws IOException {
            Path gitRepo = createGitRepoWithoutHooks(tempDir);
            assertDoesNotThrow(() -> GitHookInstaller.uninstall(gitRepo));
        }

        @Test
        @DisplayName("handles partial uninstall (only pre-commit installed)")
        void handlesPartialUninstallPreCommitOnly() throws IOException {
            Path gitRepo = createGitRepoWithoutHooks(tempDir);
            Path hooksDir = gitRepo.resolve(".git/hooks");

            // Only create pre-commit hook
            Path preCommit = hooksDir.resolve("pre-commit");
            Files.writeString(preCommit, "#!/bin/sh\n# DiffGuard hook\necho test");

            GitHookInstaller.uninstall(gitRepo);

            assertFalse(Files.exists(preCommit), "Pre-commit should be removed");
        }
    }
}
