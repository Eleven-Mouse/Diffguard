package com.diffguard.cli;

import com.diffguard.hook.GitHookInstaller;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "uninstall", description = "移除 DiffGuard Git钩子")
public class UninstallCommand implements Runnable {

    @CommandLine.ParentCommand
    DiffGuardMain parent;

    @Override
    public void run() {
        try {
            GitHookInstaller.uninstall(Path.of("").toAbsolutePath());
            parent.setExitCode(0);
        } catch (Exception e) {
            System.err.println("卸载钩子失败：" + e.getMessage());
            parent.setExitCode(1);
        }
    }
}
