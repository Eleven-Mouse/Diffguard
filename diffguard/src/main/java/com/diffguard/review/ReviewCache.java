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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 审查结果缓存，采用 Caffeine 内存缓存 + 文件持久化的双层架构。
 * - 内存层：Caffeine 提供高性能并发安全的 in-process 缓存
 * - 磁盘层：文件持久化保证跨 JVM 进程（CLI 多次调用）间缓存复用
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
     *
     * @param projectDir 项目根目录（用于定位 .git 目录）
     */
    public ReviewCache(Path projectDir) {
        this(projectDir, resolveCacheDir(projectDir));
    }

    /**
     * 使用指定缓存目录创建缓存（主要用于测试）。
     *
     * @param projectDir 项目根目录（语义占位）
     * @param cacheDir   自定义缓存目录路径
     * @return ReviewCache 实例
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

    /**
     * 根据文件路径和差异内容生成缓存键（不含审查上下文，向后兼容）。
     */
    public static String buildKey(String filePath, String diffContent) {
        return buildKey(filePath, diffContent, null);
    }

    /**
     * 根据文件路径、差异内容和审查上下文生成缓存键。
     * 上下文包含模型名称、规则配置、语言、流水线模式等因子，
     * 确保切换模型或规则后不会命中旧缓存。
     *
     * @param filePath     文件路径
     * @param diffContent  diff 内容
     * @param contextHash  审查上下文哈希（由 {@link #computeContextHash} 生成），可为 null
     */
    public static String buildKey(String filePath, String diffContent, String contextHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(filePath.getBytes(StandardCharsets.UTF_8));
            digest.update(diffContent.getBytes(StandardCharsets.UTF_8));
            if (contextHash != null && !contextHash.isEmpty()) {
                digest.update(contextHash.getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((filePath + diffContent + (contextHash != null ? contextHash : "")).hashCode());
        }
    }

    /**
     * 根据审查配置计算上下文哈希，用于区分不同模型/规则/语言下的审查结果。
     *
     * @param model        LLM 模型名称
     * @param enabledRules 启用的审查规则列表
     * @param language     审查语言
     * @param pipelineEnabled 是否启用多阶段流水线
     * @return 上下文哈希字符串
     */
    public static String computeContextHash(String model, List<String> enabledRules,
                                            String language, boolean pipelineEnabled) {
        String raw = model
                + "|rules=" + String.join(",", enabledRules != null ? enabledRules : List.of())
                + "|lang=" + (language != null ? language : "")
                + "|pipeline=" + pipelineEnabled;
        return sha256(raw);
    }

    /**
     * 获取缓存：先查内存，再查磁盘（自动检测 gzip 压缩）。
     */
    public List<ReviewIssue> get(String key) {
        // 1. 内存缓存
        List<ReviewIssue> cached = memoryCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        // 2. 磁盘缓存：优先尝试压缩文件，回退到 JSON 文件
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

        // 1. 内存缓存
        memoryCache.put(key, issues);

        // 2. 磁盘持久化
        if (cacheDir == null) return;
        try {
            byte[] jsonBytes = JacksonMapper.MAPPER.writeValueAsBytes(issues);
            if (jsonBytes.length >= COMPRESSION_THRESHOLD) {
                writeCompressed(cacheFile(key, ".json.gz"), jsonBytes);
                // 清理可能存在的旧格式文件
                deleteQuietly(cacheFile(key, ".json"));
            } else {
                Files.write(cacheFile(key, ".json"), jsonBytes);
                // 清理可能存在的旧压缩文件
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

    private Path cacheFile(String key, String suffix) {
        return cacheDir.resolve(sha256(key) + suffix);
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
