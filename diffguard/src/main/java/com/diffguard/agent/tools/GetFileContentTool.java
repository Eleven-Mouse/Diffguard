package com.diffguard.agent.tools;

import com.diffguard.agent.core.AgentContext;
import com.diffguard.agent.core.AgentTool;
import com.diffguard.agent.core.ToolResult;
import com.diffguard.llm.tools.FileAccessSandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读取项目源文件内容。
 */
public class GetFileContentTool implements AgentTool {

    private final Path projectDir;
    private final FileAccessSandbox sandbox;

    public GetFileContentTool(Path projectDir) {
        this(projectDir, null);
    }

    public GetFileContentTool(Path projectDir, FileAccessSandbox sandbox) {
        this.projectDir = projectDir;
        this.sandbox = sandbox;
    }

    @Override
    public String name() { return "get_file_content"; }

    @Override
    public String description() {
        return "读取项目中指定文件的完整内容。输入: 文件相对路径 (如 src/main/java/com/example/Service.java)";
    }

    @Override
    public ToolResult execute(String input, AgentContext context) {
        String filePath = input.trim().strip();
        if (filePath.isEmpty()) {
            return ToolResult.error("文件路径不能为空");
        }

        // 安全检查：防止路径遍历
        if (filePath.contains("..")) {
            return ToolResult.error("不允许使用 .. 的路径");
        }

        try {
            Path fullPath = projectDir.resolve(filePath).normalize();
            if (!fullPath.startsWith(projectDir)) {
                return ToolResult.error("路径超出项目范围");
            }

            if (sandbox != null && !sandbox.isFileInScope(filePath)) {
                return ToolResult.error("文件不在审查范围内: " + filePath);
            }

            if (!Files.isRegularFile(fullPath)) {
                return ToolResult.error("文件不存在: " + filePath);
            }

            long fileSize = Files.size(fullPath);
            if (fileSize > 100_000) {
                return ToolResult.error("文件过大 (" + fileSize + " 字节)，请使用 get_method_definition 查看特定方法");
            }

            String content = Files.readString(fullPath, StandardCharsets.UTF_8);
            return ToolResult.ok("文件: " + filePath + "\n" + content);
        } catch (IOException e) {
            return ToolResult.error("读取文件失败: " + e.getMessage());
        }
    }
}
