package com.diffguard.infrastructure.git;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.exception.DiffCollectionException;
import com.diffguard.adapter.toolserver.model.DiffFileEntry;
import com.diffguard.infrastructure.common.TokenEstimator;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class DiffCollector {

    private static final Logger log = LoggerFactory.getLogger(DiffCollector.class);

    /**
     * 收集暂存区差异（索引 vs HEAD），等同于 "git diff --cached"。
     */
    public static List<DiffFileEntry> collectStagedDiff(Path projectDir, ReviewConfig config)
            throws DiffCollectionException {
        try (Repository repository = new FileRepositoryBuilder()
                .findGitDir(projectDir.toFile())
                .build();
             Git git = new Git(repository)) {

            List<DiffEntry> diffs = git.diff()
                    .setCached(true)
                    .call();

            return processDiffEntries(diffs, repository, config);

        } catch (IOException | GitAPIException e) {
            throw new DiffCollectionException("收集差异失败：" + e.getMessage(), e);
        }
    }

    /**
     * 收集两个引用之间的差异（用于 pre-push 钩子）。
     */
    public static List<DiffFileEntry> collectDiffBetweenRefs(
            Path projectDir, String fromRef, String toRef, ReviewConfig config)
            throws DiffCollectionException {
        try (Repository repository = new FileRepositoryBuilder()
                .findGitDir(projectDir.toFile())
                .build()) {

            ObjectId fromId = repository.resolve(fromRef);
            ObjectId toId = repository.resolve(toRef);

            if (fromId == null || toId == null) {
                throw new DiffCollectionException("无法解析引用：" + fromRef + ".." + toRef);
            }

            CanonicalTreeParser oldTree = prepareTreeParser(repository, fromId);
            CanonicalTreeParser newTree = prepareTreeParser(repository, toId);

            try (Git git = new Git(repository)) {
                List<DiffEntry> diffs = git.diff()
                        .setOldTree(oldTree)
                        .setNewTree(newTree)
                        .call();

                return processDiffEntries(diffs, repository, config);
            }

        } catch (IOException | GitAPIException e) {
            throw new DiffCollectionException("收集引用间差异失败：" + e.getMessage(), e);
        }
    }

    /**
     * 通用的 diff 条目处理逻辑：过滤、格式化、token 限制检查。
     */
    private static List<DiffFileEntry> processDiffEntries(
            List<DiffEntry> diffs, Repository repository, ReviewConfig config) throws IOException {
        List<DiffFileEntry> entries = new ArrayList<>();
        int maxFiles = config.getReview().getMaxDiffFiles();
        int maxTokens = config.getReview().getMaxTokensPerFile();
        int fileCount = 0;

        for (DiffEntry diff : diffs) {
            if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) {
                continue;
            }

            String filePath = diff.getNewPath();
            if (shouldIgnore(filePath, config)) {
                continue;
            }

            if (++fileCount > maxFiles) {
                log.warn("已达到最大差异文件数量（{}），跳过剩余文件", maxFiles);
                break;
            }

            String diffContent = formatDiff(repository, diff);
            if (diffContent != null && !diffContent.isBlank()) {
                String provider = config.getLlm().getProvider();
                int tokenCount = TokenEstimator.estimate(diffContent, provider);
                if (tokenCount > maxTokens) {
                    log.warn("文件 {} 超出 token 限制（{} > {}），已跳过", filePath, tokenCount, maxTokens);
                    continue;
                }
                entries.add(new DiffFileEntry(filePath, diffContent, tokenCount));
            }
        }

        return entries;
    }

    private static CanonicalTreeParser prepareTreeParser(Repository repository, ObjectId commitId) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            treeParser.reset(repository.newObjectReader(), revWalk.parseTree(commitId));
            return treeParser;
        }
    }

    private static String formatDiff(Repository repository, DiffEntry diff) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DiffFormatter formatter = new DiffFormatter(baos)) {
            formatter.setRepository(repository);
            formatter.format(diff);
            return baos.toString();
        }
    }

    private static boolean shouldIgnore(String filePath, ReviewConfig config) {
        if (filePath == null) return true;

        List<String> ignorePatterns = config.getIgnore().getFiles();
        for (String pattern : ignorePatterns) {
            if (matchGlob(filePath, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static final ConcurrentHashMap<String, Pattern> globPatternCache = new ConcurrentHashMap<>();

    private static boolean matchGlob(String path, String pattern) {
        Pattern compiled = globPatternCache.computeIfAbsent(pattern, DiffCollector::compileGlob);
        return compiled.matcher(path).matches();
    }

    private static Pattern compileGlob(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    if (i + 2 < pattern.length() && pattern.charAt(i + 2) == '/') {
                        regex.append("(.*/)?");
                        i += 3;
                    } else {
                        regex.append(".*");
                        i += 2;
                    }
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if ("\\[]{}()+^$|.".indexOf(c) >= 0) {
                regex.append('\\').append(c);
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }
}
