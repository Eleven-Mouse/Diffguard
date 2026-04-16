package com.diffguard.diff;

import com.diffguard.config.ReviewConfig;
import com.diffguard.model.DiffFileEntry;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DiffCollector {

    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    /**
     * 收集暂存区差异（索引 vs HEAD），等同于 "git diff --cached"。
     */
    public static List<DiffFileEntry> collectStagedDiff(Path projectDir, ReviewConfig config) {
        try (Repository repository = new FileRepositoryBuilder()
                .findGitDir(projectDir.toFile())
                .build();
             Git git = new Git(repository)) {

            List<DiffEntry> diffs = git.diff()
                    .setCached(true)
                    .call();

            return processDiffEntries(diffs, repository, config);

        } catch (IOException | GitAPIException e) {
            System.err.println("收集差异失败：" + e.getMessage());
            return List.of();
        }
    }

    /**
     * 收集两个引用之间的差异（用于 pre-push 钩子）。
     */
    public static List<DiffFileEntry> collectDiffBetweenRefs(
            Path projectDir, String fromRef, String toRef, ReviewConfig config) {
        try (Repository repository = new FileRepositoryBuilder()
                .findGitDir(projectDir.toFile())
                .build()) {

            ObjectId fromId = repository.resolve(fromRef);
            ObjectId toId = repository.resolve(toRef);

            if (fromId == null || toId == null) {
                System.err.println("无法解析引用：" + fromRef + ".." + toRef);
                return List.of();
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
            System.err.println("收集引用间差异失败：" + e.getMessage());
            return List.of();
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
                System.err.println("已达到最大差异文件数量（" + maxFiles + "），跳过剩余文件。");
                break;
            }

            String diffContent = formatDiff(repository, diff);
            if (diffContent != null && !diffContent.isBlank()) {
                int tokenCount = ENCODING.countTokens(diffContent);
                if (tokenCount > maxTokens) {
                    System.err.println("  文件 " + filePath + " 超出 token 限制（"
                            + tokenCount + " > " + maxTokens + "），已跳过。");
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

    private static boolean matchGlob(String path, String pattern) {
        String regex = pattern
                .replace(".", "\\.")
                .replace("**/", "(.*/)?")
                .replace("**", ".*")
                .replace("*", "[^/]*")
                .replace("?", ".");
        return path.matches(regex);
    }
}
