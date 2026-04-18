package com.diffguard.review;

import com.diffguard.model.ReviewIssue;
import com.diffguard.util.JacksonMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 审查结果缓存，采用 Caffeine 内存缓存 + 文件持久化的双层架构。
 * <p>
 * 缓存键生成委托给 {@link CacheKeyGenerator}，
 * 本类仅负责缓存的存取、持久化和生命周期管理。
 */
public class ReviewCache {

    private static final Logger log = LoggerFactory.getLogger(ReviewCache.class);

    private static final long MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L; // 24 hours
    private static final int MAX_CACHE_ENTRIES = 500;
    /** 超过此大小（字节）的 JSON 缓存使用 gzip 压缩 */
    private static final int COMPRESSION_THRESHOLD = 4096;

    private final Path cacheDir;
    private final Cache<String, List<ReviewIssue>> memoryCache;

    /**
     * 创建双层缓存，使用项目 .git 目录下的 diffguard-cache 子目录。
     */
    public ReviewCache(Path projectDir) {
        this(projectDir, resolveCacheDir(projectDir));
    }

    /**
     * 使用指定缓存目录创建缓存（主要用于测试）。
     */
    public static ReviewCache withCustomCacheDir(Path projectDir, Path cacheDir) {
        return new ReviewCache(projectDir, cacheDir);
    }

    private ReviewCache(Path projectDir, Path cacheDir) {
        this.cacheDir = cacheDir;
        ensureCacheDir();
        cleanupDiskCache();
        this.memoryCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(24, TimeUnit.HOURS)
                .build();
    }

    // ========== 向后兼容的静态委托方法 ==========

    /**
     * @see CacheKeyGenerator#buildKey(String, String)
     */
    public static String buildKey(String filePath, String diffContent) {
        return CacheKeyGenerator.buildKey(filePath, diffContent);
    }

    /**
     * @see CacheKeyGenerator#buildKey(String, String, String)
     */
    public static String buildKey(String filePath, String diffContent, String contextHash) {
        return CacheKeyGenerator.buildKey(filePath, diffContent, contextHash);
    }

    /**
     * @see CacheKeyGenerator#computeContextHash(String, List, String, boolean)
     */
    public static String computeContextHash(String model, List<String> enabledRules,
                                            String language, boolean pipelineEnabled) {
        return CacheKeyGenerator.computeContextHash(model, enabledRules, language, pipelineEnabled);
    }

    // ========== 缓存操作 ==========

    /**
     * 获取缓存：先查内存，再查磁盘（自动检测 gzip 压缩）。
     */
    public List<ReviewIssue> get(String key) {
        List<ReviewIssue> cached = memoryCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        Path gzipFile = cacheFile(key, ".json.gz");
        Path jsonFile = cacheFile(key, ".json");

        try {
            if (Files.isRegularFile(gzipFile)) {
                if (isExpired(gzipFile)) {
                    deleteQuietly(gzipFile);
                    memoryCache.invalidate(key);
                    return null;
                }
                List<ReviewIssue> issues = readCompressed(gzipFile);
                memoryCache.put(key, issues);
                return issues;
            }

            if (Files.isRegularFile(jsonFile)) {
                if (isExpired(jsonFile)) {
                    deleteQuietly(jsonFile);
                    memoryCache.invalidate(key);
                    return null;
                }
                List<ReviewIssue> issues = JacksonMapper.MAPPER.readValue(jsonFile.toFile(), new TypeReference<List<ReviewIssue>>() {});
                memoryCache.put(key, issues);
                return issues;
            }
        } catch (IOException e) {
            log.debug("缓存读取失败: {}", key);
        }
        return null;
    }

    /**
     * 写入缓存：同时写入内存和磁盘（超过阈值自动压缩）。
     */
    public void put(String key, List<ReviewIssue> issues) {
        if (issues == null) return;

        memoryCache.put(key, issues);

        if (cacheDir == null) return;
        try {
            byte[] jsonBytes = JacksonMapper.MAPPER.writeValueAsBytes(issues);
            if (jsonBytes.length >= COMPRESSION_THRESHOLD) {
                writeCompressed(cacheFile(key, ".json.gz"), jsonBytes);
                deleteQuietly(cacheFile(key, ".json"));
            } else {
                Files.write(cacheFile(key, ".json"), jsonBytes);
                deleteQuietly(cacheFile(key, ".json.gz"));
            }
        } catch (IOException e) {
            log.debug("缓存写入失败: {}", key);
        }
    }

    public boolean contains(String key) {
        return get(key) != null;
    }

    public void clear() {
        memoryCache.invalidateAll();
        if (cacheDir == null || !Files.isDirectory(cacheDir)) return;
        try (Stream<Path> files = Files.list(cacheDir)) {
            files.filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".json.gz"))
                    .forEach(ReviewCache::deleteQuietly);
        } catch (IOException e) {
            log.debug("清理缓存失败", e);
        }
    }

    // ========== 内部方法 ==========

    private Path cacheFile(String key, String suffix) {
        return cacheDir.resolve(CacheKeyGenerator.sha256(key) + suffix);
    }

    private List<ReviewIssue> readCompressed(Path file) throws IOException {
        try (InputStream fis = Files.newInputStream(file);
             InputStream gis = new GZIPInputStream(fis)) {
            return JacksonMapper.MAPPER.readValue(gis, new TypeReference<List<ReviewIssue>>() {});
        }
    }

    private void writeCompressed(Path file, byte[] jsonBytes) throws IOException {
        try (OutputStream fos = Files.newOutputStream(file);
             OutputStream gos = new GZIPOutputStream(fos)) {
            gos.write(jsonBytes);
        }
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

    private void cleanupDiskCache() {
        if (cacheDir == null || !Files.isDirectory(cacheDir)) return;

        try (Stream<Path> stream = Files.list(cacheDir)) {
            List<Path> entries = stream
                    .filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".json.gz"))
                    .toList();

            long now = System.currentTimeMillis();
            for (Path entry : entries) {
                try {
                    long age = now - Files.getLastModifiedTime(entry).toMillis();
                    if (age > MAX_CACHE_AGE_MS) {
                        deleteQuietly(entry);
                    }
                } catch (IOException ignored) {}
            }

            try (Stream<Path> remaining = Files.list(cacheDir)) {
                List<Path> sorted = remaining
                        .filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".json.gz"))
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

    static Path resolveCacheDir(Path projectDir) {
        Path gitDir = projectDir.resolve(".git");

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

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {}
    }
}
