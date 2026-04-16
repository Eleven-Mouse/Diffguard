package com.diffguard.hook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public class GitHookInstaller {

    private static final String PRE_COMMIT_HOOK = """
            #!/bin/sh
            # DiffGuard - AI Code Review pre-commit hook
            echo "🔍 DiffGuard: Running AI code review..."
            java -jar "$(dirname "$0")/diffguard.jar" review --staged
            exit_code=$?
            if [ $exit_code -ne 0 ]; then
                echo "❌ DiffGuard: Critical issues found. Commit aborted."
                echo "   Use --no-verify to bypass (not recommended)."
                exit 1
            fi
            echo "✅ DiffGuard: Review passed."
            exit 0
            """;

    private static final String PRE_PUSH_HOOK = """
            #!/bin/sh
            # DiffGuard - AI Code Review pre-push hook
            remote="$1"
            url="$2"

            while read local_ref local_sha remote_ref remote_sha; do
                echo "🔍 DiffGuard: Reviewing changes for push to $remote..."
                java -jar "$(dirname "$0")/diffguard.jar" review --from "$local_sha" --to "$remote_sha"
                exit_code=$?
                if [ $exit_code -ne 0 ]; then
                    echo "❌ DiffGuard: Critical issues found. Push aborted."
                    exit 1
                fi
            done

            echo "✅ DiffGuard: Review passed."
            exit 0
            """;

    /**
     * Install a pre-commit hook in the given git repository.
     */
    public static void installPreCommit(Path projectDir) throws IOException {
        Path gitDir = findGitDir(projectDir);
        if (gitDir == null) {
            throw new IOException("Not a git repository: " + projectDir);
        }

        Path hooksDir = gitDir.resolve("hooks");
        Files.createDirectories(hooksDir);

        Path hookFile = hooksDir.resolve("pre-commit");
        if (Files.exists(hookFile)) {
            // Backup existing hook
            Path backup = hooksDir.resolve("pre-commit.diffguard-backup");
            Files.copy(hookFile, backup);
            System.out.println("Existing pre-commit hook backed up to: " + backup);
        }

        Files.writeString(hookFile, PRE_COMMIT_HOOK, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        makeExecutable(hookFile);
        System.out.println("✅ pre-commit hook installed: " + hookFile);
    }

    /**
     * Install a pre-push hook in the given git repository.
     */
    public static void installPrePush(Path projectDir) throws IOException {
        Path gitDir = findGitDir(projectDir);
        if (gitDir == null) {
            throw new IOException("Not a git repository: " + projectDir);
        }

        Path hooksDir = gitDir.resolve("Hooks");
        Files.createDirectories(hooksDir);

        Path hookFile = hooksDir.resolve("pre-push");
        if (Files.exists(hookFile)) {
            Path backup = hooksDir.resolve("pre-push.diffguard-backup");
            Files.copy(hookFile, backup);
            System.out.println("Existing pre-push hook backed up to: " + backup);
        }

        Files.writeString(hookFile, PRE_PUSH_HOOK, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        makeExecutable(hookFile);
        System.out.println("✅ pre-push hook installed: " + hookFile);
    }

    /**
     * Remove DiffGuard hooks.
     */
    public static void uninstall(Path projectDir) throws IOException {
        Path gitDir = findGitDir(projectDir);
        if (gitDir == null) return;

        Path hooksDir = gitDir.resolve("hooks");
        for (String hookName : new String[]{"pre-commit", "pre-push"}) {
            Path hook = hooksDir.resolve(hookName);
            if (Files.exists(hook)) {
                String content = Files.readString(hook);
                if (content.contains("DiffGuard")) {
                    Files.delete(hook);
                    System.out.println("Removed " + hookName + " hook");

                    // Restore backup if exists
                    Path backup = hooksDir.resolve(hookName + ".diffguard-backup");
                    if (Files.exists(backup)) {
                        Files.copy(backup, hook);
                        Files.delete(backup);
                        System.out.println("Restored previous " + hookName + " hook");
                    }
                }
            }
        }
    }

    private static Path findGitDir(Path projectDir) {
        Path current = projectDir;
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current.resolve(".git");
            }
            // Check if this is a bare repo or worktree
            if (Files.isRegularFile(current.resolve(".git"))) {
                // Git worktree - read .git file to find actual git dir
                try {
                    String content = Files.readString(current.resolve(".git"));
                    if (content.startsWith("gitdir: ")) {
                        return Path.of(content.substring(8).trim());
                    }
                } catch (IOException ignored) {}
            }
            current = current.getParent();
        }
        return null;
    }

    private static void makeExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException e) {
            // Windows - try setting executable via File API
            file.toFile().setExecutable(true);
        }
    }
}
