package com.diffguard.cache;

import com.diffguard.model.ReviewIssue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于文件系统的审查结果缓存。
 * 缓存存储在 .git/diffguard-cache/ 目录下，跨进程持久化，
 * 确保 Git Hook 多次调用（pre-commit / pre-push）之间可复用审查结果。
 */
public class ReviewCache {

    private static final Logger log = LoggerFactory.getLogger(ReviewCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final long MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L; // 24 hours
    private static final int MAX_CACHE_ENTRIES = 500;

    private final Path cacheDir;

    /**
     * 创建文件持久化缓存。
     *
     * @param projectDir 项目根目录（用于定位 .git 目录）
     */
    public ReviewCache(Path projectDir) {
        this.cacheDir = resolveCacheDir(projectDir);
        ensureCacheDir();
        cleanup();
    }

    /**
     * 仅供测试使用的构造方法，可指定自定义缓存目录。
     */
    public ReviewCache(Path projectDir, boolean testing) {
        if (testing) {
            this.cacheDir = projectDir;
            ensureCacheDir();
        } else {
            this.cacheDir = resolveCacheDir(projectDir);
            ensureCacheDir();
            cleanup();
        }
    }

    /**
     * 根据文件路径和差异内容生成缓存键。
     */
    public static String buildKey(String filePath, String diffContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(diffContent.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return filePath + ":" + hexString;
        } catch (NoSuchAlgorithmException e) {
            return filePath + ":" + Integer.toHexString(diffContent.hashCode());
        }
    }

    public List<ReviewIssue> get(String key) {
        Path file = cacheFile(key);
        if (!Files.isRegularFile(file)) return null;

        try {
            if (isExpired(file)) {
                deleteQuietly(file);
                return null;
            }
            return MAPPER.readValue(file.toFile(), new TypeReference<List<ReviewIssue>>() {});
        } catch (IOException e) {
            log.debug("缓存读取失败: {}", file.getFileName());
            return null;
        }
    }

    public void put(String key, List<ReviewIssue> issues) {
        if (cacheDir == null || issues == null) return;
        Path file = cacheFile(key);
        try {
            MAPPER.writeValue(file.toFile(), issues);
        } catch (IOException e) {
            log.debug("缓存写入失败: {}", file.getFileName());
        }
    }

    public boolean contains(String key) {
        return get(key) != null;
    }

    public void clear() {
        if (cacheDir == null || !Files.isDirectory(cacheDir)) return;
        try (Stream<Path> files = Files.list(cacheDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .forEach(ReviewCache::deleteQuietly);
        } catch (IOException e) {
            log.debug("清理缓存失败", e);
        }
    }

    private Path cacheFile(String key) {
        return cacheDir.resolve(sha256(key) + ".json");
    }

    private boolean isExpired(Path file) throws IOException {
        long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
        return age > MAX_CACHE_AGE_MS;
    }

    private void ensureCacheDir() {
        if (cacheDir == null) return;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            log.warn("无法创建缓存目录: {}", cacheDir);
        }
    }

    private void cleanup() {
        if (cacheDir == null || !Files.isDirectory(cacheDir)) return;

        try (Stream<Path> stream = Files.list(cacheDir)) {
            List<Path> entries = stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .toList();

            // 删除过期条目
            long now = System.currentTimeMillis();
            for (Path entry : entries) {
                try {
                    long age = now - Files.getLastModifiedTime(entry).toMillis();
                    if (age > MAX_CACHE_AGE_MS) {
                        deleteQuietly(entry);
                    }
                } catch (IOException ignored) {}
            }

            // 限制最大条目数，删除最旧的
            try (Stream<Path> remaining = Files.list(cacheDir)) {
                List<Path> sorted = remaining
                        .filter(p -> p.toString().endsWith(".json"))
                        .sorted((a, b) -> {
                            try {
                                return Long.compare(
                                        Files.getLastModifiedTime(a).toMillis(),
                                        Files.getLastModifiedTime(b).toMillis());
                            } catch (IOException e) { return 0; }
                        })
                        .toList();

                int toDelete = sorted.size() - MAX_CACHE_ENTRIES;
                for (int i = 0; i < toDelete; i++) {
                    deleteQuietly(sorted.get(i));
                }
            }
        } catch (IOException e) {
            log.debug("清理缓存失败", e);
        }
    }

    private static Path resolveCacheDir(Path projectDir) {
        Path gitDir = projectDir.resolve(".git");

        // 处理 worktree：.git 是一个指向实际 gitdir 的文件
        if (Files.isRegularFile(gitDir)) {
            try {
                String content = Files.readString(gitDir).trim();
                if (content.startsWith("gitdir: ")) {
                    Path worktreeGitDir = Path.of(content.substring("gitdir: ".length()));
                    if (Files.isDirectory(worktreeGitDir)) {
                        return worktreeGitDir.resolve("diffguard-cache");
                    }
                }
            } catch (IOException ignored) {}
        }

        return gitDir.resolve("diffguard-cache");
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {}
    }
}
