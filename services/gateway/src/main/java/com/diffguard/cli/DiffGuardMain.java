package com.diffguard.cli;

import picocli.CommandLine;

/**
 * DiffGuard CLI 主入口，定义顶层命令和子命令注册。
 * 各子命令通过 @CommandLine.ParentCommand 回调设置退出码。
 */
@CommandLine.Command(
        name = "diffguard",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "基于AI的代码审查命令行工具，集成Git钩子",
        subcommands = {
                ReviewCommand.class,
                InstallCommand.class,
                UninstallCommand.class,
                ServerCommand.class
        }
)
public class DiffGuardMain implements Runnable {

    private int exitCode = 0;

    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /**
     * CLI 入口方法。
     */
    public static int execute(String[] args) {
        DiffGuardMain main = new DiffGuardMain();
        new CommandLine(main).execute(args);
        return main.getExitCode();
    }
}
