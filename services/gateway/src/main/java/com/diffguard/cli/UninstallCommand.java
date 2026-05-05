package com.diffguard.cli;

import com.diffguard.infrastructure.git.GitHookInstaller;
import com.diffguard.infrastructure.output.TerminalUI;
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
            TerminalUI.error("Hook uninstall failed: " + e.getMessage());
            parent.setExitCode(1);
        }
    }
}
