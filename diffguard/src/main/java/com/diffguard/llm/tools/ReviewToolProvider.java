package com.diffguard.llm.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 可调用的代码审查工具集。
 * <p>
 * 通过 LangChain4j 的 {@link Tool} 注解声明，LLM 在审查过程中
 * 可以主动调用这些工具获取 diff 之外的代码上下文。
 * <p>
 * 所有文件访问通过 {@link FileAccessSandbox} 进行安全控制。
 */
public class ReviewToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ReviewToolProvider.class);

    /** 匹配 Java 方法签名 */
    private static final Pattern JAVA_METHOD_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:(?:public|protected|private|static|final|synchronized|native|abstract)\\s+)*"
            + "(?:<[\\w\\s,? extends super>&]*>\\s+)?"
            + "(\\w[\\w<>\\[\\]]*)\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w\\s,.]+)?\\s*\\{"
    );

    /** 匹配 import 语句 */
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?);", Pattern.MULTILINE
    );

    private final FileAccessSandbox sandbox;
    private final AtomicInteger callCount = new AtomicInteger(0);
    private static final int MAX_TOOL_CALLS = 10;

    public ReviewToolProvider(FileAccessSandbox sandbox) {
        this.sandbox = sandbox;
    }

    private String checkCallLimit() {
        if (callCount.incrementAndGet() > MAX_TOOL_CALLS) {
            log.warn("Tool 调用次数已达上限（{}次），阻止后续调用", MAX_TOOL_CALLS);
            return "Tool 调用次数已达上限（" + MAX_TOOL_CALLS + "次），请基于已有信息完成审查。";
        }
        return null;
    }

    public void resetCallCount() {
        callCount.set(0);
    }

    @Tool("Read the full content of a file being reviewed, not just the diff. "
          + "Use this to understand the broader context around changed lines, "
          + "check class structure, field declarations, and surrounding code.")
    public String readFile(@P("Relative file path, e.g. 'src/main/java/com/example/Service.java'") String filePath) {
        String limitMsg = checkCallLimit();
        if (limitMsg != null) return limitMsg;
        try {
            String content = sandbox.readFile(filePath);
            log.debug("Tool readFile 成功：{}", filePath);
            return content;
        } catch (SecurityException e) {
            return "Access denied: " + e.getMessage();
        } catch (IOException e) {
            return "File not found: " + filePath;
        }
    }

    @Tool("List all method signatures in a Java file. "
          + "Returns method names, return types, and parameter types. "
          + "Use this to understand the class structure and method contracts.")
    public String listMethods(@P("Relative file path") String filePath) {
        String limitMsg = checkCallLimit();
        if (limitMsg != null) return limitMsg;
        try {
            String content = sandbox.readFile(filePath);
            List<String> signatures = extractMethodSignatures(content);
            if (signatures.isEmpty()) {
                return "No method signatures found in " + filePath;
            }
            return String.join("\n", signatures);
        } catch (SecurityException e) {
            return "Access denied: " + e.getMessage();
        } catch (IOException e) {
            return "File not found: " + filePath;
        }
    }

    @Tool("Check import statements in a file. "
          + "Returns all imports and identifies potentially unused or missing ones. "
          + "Use this to detect missing imports for newly added code.")
    public String checkImports(@P("Relative file path") String filePath) {
        String limitMsg = checkCallLimit();
        if (limitMsg != null) return limitMsg;
        try {
            String content = sandbox.readFile(filePath);
            List<String> imports = extractImports(content);
            if (imports.isEmpty()) {
                return "No import statements found in " + filePath;
            }
            return "Imports in " + filePath + ":\n" + String.join("\n", imports);
        } catch (SecurityException e) {
            return "Access denied: " + e.getMessage();
        } catch (IOException e) {
            return "File not found: " + filePath;
        }
    }

    List<String> extractMethodSignatures(String content) {
        List<String> signatures = new ArrayList<>();
        Matcher matcher = JAVA_METHOD_PATTERN.matcher(content);
        while (matcher.find()) {
            String fullMatch = matcher.group().trim();
            // 简化：移除尾部的大括号
            String signature = fullMatch.endsWith("{")
                    ? fullMatch.substring(0, fullMatch.length() - 1).trim()
                    : fullMatch;
            signatures.add(signature);
        }
        return signatures;
    }

    List<String> extractImports(String content) {
        List<String> imports = new ArrayList<>();
        Matcher matcher = IMPORT_PATTERN.matcher(content);
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
    }
}
