package com.diffguard.infrastructure.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public class GitHookInstaller {

    private static final Logger log = LoggerFactory.getLogger(GitHookInstaller.class);

    private static final String PRE_COMMIT_HOOK = """
            #!/bin/sh
            # DiffGuard - AI 代码审查 pre-commit 钩子

            # Resolve java: prefer JAVA_HOME, fall back to PATH
            if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
                JAVA="$JAVA_HOME/bin/java"
            else
                JAVA="java"
            fi

            echo "🔍 DiffGuard：正在运行AI代码审查..."
            "$JAVA" -jar "$(dirname "$0")/diffguard.jar" review --staged
            exit_code=$?
            if [ $exit_code -ne 0 ]; then
                echo "❌ DiffGuard：发现严重问题，提交已中止。"
                echo "   使用 --no-verify 可跳过审查（不推荐）。"
                exit 1
            fi
            echo "✅ DiffGuard：审查通过。"
            exit 0
            """;

    private static final String PRE_PUSH_HOOK = """
            #!/bin/sh
            # DiffGuard - AI 代码审查 pre-push 钩子

            # Resolve java: prefer JAVA_HOME, fall back to PATH
            if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
                JAVA="$JAVA_HOME/bin/java"
            else
                JAVA="java"
            fi

            remote="$1"
            url="$2"

            while read local_ref local_sha remote_ref remote_sha; do
                echo "🔍 DiffGuard：正在审查推送到 $remote 的变更..."
                "$JAVA" -jar "$(dirname "$0")/diffguard.jar" review --from "$local_sha" --to "$remote_sha"
                exit_code=$?
                if [ $exit_code -ne 0 ]; then
                    echo "❌ DiffGuard：发现严重问题，推送已中止。"
                    exit 1
                fi
            done

            echo "✅ DiffGuard：审查通过。"
            exit 0
            """;

    /**
     * 在指定的Git仓库中安装 pre-commit 钩子。
     */
    public static void installPreCommit(Path projectDir) throws IOException {
        Path gitDir = findGitDir(projectDir);
        if (gitDir == null) {
            throw new IOException("不是Git仓库：" + projectDir);
        }

        Path hooksDir = gitDir.resolve("hooks");
        Files.createDirectories(hooksDir);

        Path hookFile = hooksDir.resolve("pre-commit");
        if (Files.exists(hookFile)) {
            // 备份现有钩子
            Path backup = hooksDir.resolve("pre-commit.diffguard-backup");
            Files.copy(hookFile, backup);
            log.info("已将现有 pre-commit 钩子备份到：{}", backup);
        }

        Files.writeString(hookFile, PRE_COMMIT_HOOK, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        makeExecutable(hookFile);
        log.info("pre-commit 钩子已安装：{}", hookFile);
    }

    /**
     * 在指定的Git仓库中安装 pre-push 钩子。
     */
    public static void installPrePush(Path projectDir) throws IOException {
        Path gitDir = findGitDir(projectDir);
        if (gitDir == null) {
            throw new IOException("不是Git仓库：" + projectDir);
        }

        Path hooksDir = gitDir.resolve("hooks");
        Files.createDirectories(hooksDir);

        Path hookFile = hooksDir.resolve("pre-push");
        if (Files.exists(hookFile)) {
            Path backup = hooksDir.resolve("pre-push.diffguard-backup");
            Files.copy(hookFile, backup);
            log.info("已将现有 pre-push 钩子备份到：{}", backup);
        }

        Files.writeString(hookFile, PRE_PUSH_HOOK, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        makeExecutable(hookFile);
        log.info("pre-push 钩子已安装：{}", hookFile);
    }

    /**
     * 移除 DiffGuard 钩子。
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
                    log.info("已移除 {} 钩子", hookName);

                    // 如果存在备份则恢复
                    Path backup = hooksDir.resolve(hookName + ".diffguard-backup");
                    if (Files.exists(backup)) {
                        Files.copy(backup, hook);
                        Files.delete(backup);
                        log.info("已恢复之前的 {} 钩子", hookName);
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
            // 检查是否为裸仓库或工作树
            if (Files.isRegularFile(current.resolve(".git"))) {
                // Git工作树 - 读取 .git 文件以找到实际的Git目录
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
            // Windows系统 - 尝试通过 File API 设置可执行权限
            file.toFile().setExecutable(true);
        }
    }
}
