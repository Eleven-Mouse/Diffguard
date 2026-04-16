package com.diffguard;

import com.diffguard.config.ConfigLoader;
import com.diffguard.config.ReviewConfig;
import com.diffguard.diff.DiffCollector;
import com.diffguard.exception.ConfigException;
import com.diffguard.exception.DiffCollectionException;
import com.diffguard.exception.DiffGuardException;
import com.diffguard.exception.LlmApiException;
import com.diffguard.hook.GitHookInstaller;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewResult;
import com.diffguard.output.ConsoleFormatter;
import com.diffguard.output.ProgressDisplay;
import com.diffguard.service.ReviewService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(
        name = "diffguard",
        mixinStandardHelpOptions = true,
        versionProvider = DiffGuard.VersionProvider.class,
        description = "基于AI的代码审查命令行工具，集成Git钩子"
)
public class DiffGuard implements Callable<Integer> {

    @Command(name = "review", description = "使用AI审查代码变更")
    public int review(
            @Option(names = "--staged", description = "审查暂存区的变更（git diff --cached）") boolean staged,
            @Option(names = "--from", description = "用于差异对比的源引用") String fromRef,
            @Option(names = "--to", description = "用于差异对比的目标引用") String toRef,
            @Option(names = "--force", description = "跳过审查（忽略严重问题）") boolean force,
            @Option(names = "--config", description = "配置文件路径") Path configPath,
            @Option(names = "--no-cache", description = "禁用结果缓存") boolean noCache
    ) {
        Path projectDir = Path.of("").toAbsolutePath();

        ProgressDisplay.printBanner();

        // 1. 加载配置
        ReviewConfig config;
        try {
            config = configPath != null
                    ? ConfigLoader.loadFromFile(configPath)
                    : ConfigLoader.load(projectDir);
        } catch (ConfigException e) {
            System.err.println("  配置加载失败：" + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("  配置加载失败：" + e.getMessage());
            return 1;
        }

        // 2. 收集差异
        ProgressDisplay.printCollectingDiffs();

        List<DiffFileEntry> diffEntries;
        try {
            if (staged) {
                diffEntries = DiffCollector.collectStagedDiff(projectDir, config);
            } else if (fromRef != null && toRef != null) {
                diffEntries = DiffCollector.collectDiffBetweenRefs(projectDir, fromRef, toRef, config);
            } else {
                System.err.println("  错误：请指定 --staged 或 --from/--to 引用");
                return 1;
            }
        } catch (DiffCollectionException e) {
            System.err.println("  差异收集失败：" + e.getMessage());
            return 1;
        }

        if (diffEntries.isEmpty()) {
            ProgressDisplay.printNoChanges();
            return 0;
        }

        int totalLines = diffEntries.stream().mapToInt(DiffFileEntry::getLineCount).sum();
        ProgressDisplay.printDiffCollected(diffEntries.size(), totalLines);

        // 3. 执行审查（委托给 ReviewService）
        ReviewService reviewService = new ReviewService(config, projectDir, noCache);
        ReviewResult result;
        try {
            result = reviewService.review(diffEntries);
        } catch (LlmApiException e) {
            // LLM 调用失败，fail-closed：阻止提交
            System.err.println("  AI 审查失败：" + e.getMessage());
            System.err.println("  为确保代码安全，提交已中止。使用 --force 可跳过。");
            return 1;
        } catch (DiffGuardException e) {
            System.err.println("  审查过程出错：" + e.getMessage());
            return 1;
        }

        // 4. 输出结果
        ConsoleFormatter.printReport(result);

        // 5. 退出码：发现严重问题且未强制跳过时返回1
        if (result.hasCriticalIssues() && !force) {
            return 1;
        }

        return 0;
    }

    @Command(name = "install", description = "安装 DiffGuard Git钩子")
    public int install(
            @Option(names = {"--pre-commit"}, description = "安装 pre-commit 钩子") boolean preCommit,
            @Option(names = {"--pre-push"}, description = "安装 pre-push 钩子") boolean prePush
    ) {
        Path projectDir = Path.of("").toAbsolutePath();

        try {
            if (!preCommit && !prePush) {
                GitHookInstaller.installPreCommit(projectDir);
                GitHookInstaller.installPrePush(projectDir);
            } else {
                if (preCommit) GitHookInstaller.installPreCommit(projectDir);
                if (prePush) GitHookInstaller.installPrePush(projectDir);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("安装钩子失败：" + e.getMessage());
            return 1;
        }
    }

    @Command(name = "uninstall", description = "移除 DiffGuard Git钩子")
    public int uninstall() {
        try {
            GitHookInstaller.uninstall(Path.of("").toAbsolutePath());
            return 0;
        } catch (Exception e) {
            System.err.println("卸载钩子失败：" + e.getMessage());
            return 1;
        }
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DiffGuard()).execute(args);
        System.exit(exitCode);
    }

    /**
     * 从 version.properties 读取版本号（由 Maven 资源过滤注入）。
     */
    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            try (InputStream is = getClass().getResourceAsStream("/version.properties")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    return new String[]{"DiffGuard " + props.getProperty("version", "unknown")};
                }
            } catch (IOException ignored) {}
            return new String[]{"DiffGuard unknown"};
        }
    }
}
