package com.diffguard.cli;

import com.diffguard.git.GitHookInstaller;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "install", description = "安装 DiffGuard Git钩子")
public class InstallCommand implements Runnable {

    @CommandLine.Option(names = {"--pre-commit"}, description = "安装 pre-commit 钩子")
    boolean preCommit;

    @CommandLine.Option(names = {"--pre-push"}, description = "安装 pre-push 钩子")
    boolean prePush;

    @CommandLine.ParentCommand
    DiffGuardMain parent;

    @Override
    public void run() {
        Path projectDir = Path.of("").toAbsolutePath();

        try {
            if (!preCommit && !prePush) {
                GitHookInstaller.installPreCommit(projectDir);
                GitHookInstaller.installPrePush(projectDir);
            } else {
                if (preCommit) GitHookInstaller.installPreCommit(projectDir);
                if (prePush) GitHookInstaller.installPrePush(projectDir);
            }
            parent.setExitCode(0);
        } catch (Exception e) {
            System.err.println("安装钩子失败：" + e.getMessage());
            parent.setExitCode(1);
        }
    }
}
