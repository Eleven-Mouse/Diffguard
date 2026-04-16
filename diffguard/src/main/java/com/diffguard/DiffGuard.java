package com.diffguard;

import com.diffguard.cache.ReviewCache;
import com.diffguard.config.ConfigLoader;
import com.diffguard.config.ReviewConfig;
import com.diffguard.diff.DiffCollector;
import com.diffguard.hook.GitHookInstaller;
import com.diffguard.llm.LlmClient;
import com.diffguard.model.DiffFileEntry;
import com.diffguard.model.ReviewIssue;
import com.diffguard.model.ReviewResult;
import com.diffguard.output.ConsoleFormatter;
import com.diffguard.prompt.PromptBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
/*
这是代码第一次测试
 */
@Command(
        name = "diffguard",
        mixinStandardHelpOptions = true,
        version = "DiffGuard 1.0.0",
        description = "AI-powered Code Review CLI tool integrated with Git Hooks"
)
public class DiffGuard implements Callable<Integer> {

    @Command(name = "review", description = "Review code changes using AI")
    public int review(
            @Option(names = "--staged", description = "Review staged changes (git diff --cached)") boolean staged,
            @Option(names = "--from", description = "Source ref for diff comparison") String fromRef,
            @Option(names = "--to", description = "Target ref for diff comparison") String toRef,
            @Option(names = "--force", description = "Skip review (bypass critical issues)") boolean force,
            @Option(names = "--config", description = "Path to config file") Path configPath,
            @Option(names = "--no-cache", description = "Disable result cache") boolean noCache
    ) {
        Path projectDir = Path.of("").toAbsolutePath();

        // Load config
        ReviewConfig config = configPath != null
                ? ConfigLoader.load(configPath.getParent())
                : ConfigLoader.load(projectDir);

        // Collect diff
        List<DiffFileEntry> diffEntries;
        if (staged) {
            diffEntries = DiffCollector.collectStagedDiff(projectDir, config);
        } else if (fromRef != null && toRef != null) {
            diffEntries = DiffCollector.collectDiffBetweenRefs(projectDir, fromRef, toRef, config);
        } else {
            System.err.println("Error: Specify --staged or --from/--to refs");
            return 1;
        }

        if (diffEntries.isEmpty()) {
            System.out.println("No changes to review.");
            return 0;
        }

        System.out.println("Reviewing " + diffEntries.size() + " file(s)...");

        // Build prompts (with splitting for large diffs)
        PromptBuilder promptBuilder = new PromptBuilder(config);
        List<PromptBuilder.PromptContent> prompts = promptBuilder.buildPrompts(diffEntries);

        // Check cache
        ReviewCache cache = noCache ? null : new ReviewCache();
        ReviewResult result = new ReviewResult();

        if (cache != null) {
            List<PromptBuilder.PromptContent> uncached = new ArrayList<>();
            for (int i = 0; i < diffEntries.size(); i++) {
                DiffFileEntry entry = diffEntries.get(i);
                String cacheKey = ReviewCache.buildKey(entry.getFilePath(), entry.getContent());
                List<ReviewIssue> cached = cache.get(cacheKey);
                if (cached != null) {
                    cached.forEach(result::addIssue);
                    result.setTotalFilesReviewed(result.getTotalFilesReviewed() + 1);
                } else {
                    uncached.add(prompts.get(i));
                }
            }

            if (!uncached.isEmpty()) {
                LlmClient llmClient = new LlmClient(config);
                ReviewResult freshResult = llmClient.review(uncached);
                for (ReviewIssue issue : freshResult.getIssues()) {
                    result.addIssue(issue);
                }
                result.setTotalTokensUsed(result.getTotalTokensUsed() + freshResult.getTotalTokensUsed());
                result.setReviewDurationMs(freshResult.getReviewDurationMs());
            }
        } else {
            LlmClient llmClient = new LlmClient(config);
            result = llmClient.review(prompts);
        }

        // Output
        ConsoleFormatter.printReport(result);

        // Exit code: 1 if critical issues found and not forced
        if (result.hasCriticalIssues() && !force) {
            return 1;
        }

        return 0;
    }

    @Command(name = "install", description = "Install DiffGuard git hooks")
    public int install(
            @Option(names = {"--pre-commit"}, description = "Install pre-commit hook") boolean preCommit,
            @Option(names = {"--pre-push"}, description = "Install pre-push hook") boolean prePush
    ) {
        Path projectDir = Path.of("").toAbsolutePath();

        try {
            if (!preCommit && !prePush) {
                // Default: install both
                GitHookInstaller.installPreCommit(projectDir);
                GitHookInstaller.installPrePush(projectDir);
            } else {
                if (preCommit) GitHookInstaller.installPreCommit(projectDir);
                if (prePush) GitHookInstaller.installPrePush(projectDir);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Failed to install hooks: " + e.getMessage());
            return 1;
        }
    }

    @Command(name = "uninstall", description = "Remove DiffGuard git hooks")
    public int uninstall() {
        try {
            GitHookInstaller.uninstall(Path.of("").toAbsolutePath());
            return 0;
        } catch (Exception e) {
            System.err.println("Failed to uninstall hooks: " + e.getMessage());
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
}
