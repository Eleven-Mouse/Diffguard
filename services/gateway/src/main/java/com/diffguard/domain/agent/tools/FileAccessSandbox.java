package com.diffguard.domain.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 文件访问安全沙箱，限制 LLM Tool 只能访问 diff 中出现的文件。
 * <p>
 * 安全约束：
 * <ul>
 *   <li>仅允许访问 diff 中出现的文件（allowedFiles）</li>
 *   <li>禁止路径穿越（../ 攻击）</li>
 *   <li>文件必须在项目目录内</li>
 * </ul>
 */
public class FileAccessSandbox {

    private static final Logger log = LoggerFactory.getLogger(FileAccessSandbox.class);

    private final Path projectRoot;
    private final Set<String> allowedFiles;

    public FileAccessSandbox(Path projectRoot, Set<String> diffFilePaths) {
        this.projectRoot = projectRoot.normalize().toAbsolutePath();
        this.allowedFiles = Set.copyOf(diffFilePaths);
        log.debug("文件沙箱初始化：projectRoot={}, allowedFiles={}", projectRoot, diffFilePaths.size());
    }

    /**
     * 读取文件内容，执行安全检查。
     *
     * @param relativePath 相对文件路径
     * @return 文件内容
     * @throws SecurityException 路径不合法或不在允许范围内
     * @throws IOException 文件读取失败
     */
    public String readFile(String relativePath) throws SecurityException, IOException {
        Path resolved = projectRoot.resolve(relativePath).normalize().toAbsolutePath();

        // 1. 验证路径在项目目录内（防路径穿越）
        if (!resolved.startsWith(projectRoot)) {
            log.warn("路径穿越被阻止：{}", relativePath);
            throw new SecurityException("路径穿越被阻止：" + relativePath);
        }

        // 2. 验证文件在 diff 允许范围内
        String relative = projectRoot.relativize(resolved).toString().replace('\\', '/');
        if (!allowedFiles.contains(relative)) {
            log.warn("文件不在审查范围内：{}", relativePath);
            throw new SecurityException("文件不在审查范围内：" + relativePath);
        }

        return Files.readString(resolved);
    }

    /**
     * 检查文件是否在允许范围内。
     */
    public boolean isFileInScope(String relativePath) {
        Path resolved = projectRoot.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(projectRoot)) {
            return false;
        }
        String relative = projectRoot.relativize(resolved).toString().replace('\\', '/');
        return allowedFiles.contains(relative);
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public Set<String> getAllowedFiles() {
        return allowedFiles;
    }
}
