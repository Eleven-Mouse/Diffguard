package com.diffguard;

import com.diffguard.cli.DiffGuardMain;

/**
 * DiffGuard 应用入口。
 * 委托给 {@link DiffGuardMain} 处理 CLI 解析和子命令分发。
 */
public class DiffGuard {

    public static void main(String[] args) {
        int exitCode = DiffGuardMain.execute(args);
        System.exit(exitCode);
    }
}
