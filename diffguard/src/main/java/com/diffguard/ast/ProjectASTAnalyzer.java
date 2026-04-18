package com.diffguard.ast;

import com.diffguard.ast.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目级 AST 分析器，扫描项目中的所有 Java 文件并构建跨文件关系。
 * <p>
 * 提供能力：
 * - 跨文件调用图（通过 import 解析关联调用）
 * - 接口-实现关系
 * - 继承链
 * - 按类名索引的方法签名
 * <p>
 * 设计为增量构建：可多次调用 {@link #addFile}，结果持续累积。
 */
public class ProjectASTAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ProjectASTAnalyzer.class);

    private final ASTAnalyzer analyzer = new ASTAnalyzer();
    private final Map<String, ASTAnalysisResult> fileResults = new ConcurrentHashMap<>();

    // 索引：类名 -> 文件路径（用于跨文件解析）
    private final Map<String, String> classNameToPath = new ConcurrentHashMap<>();
    // 索引：类名 -> 该类的所有方法
    private final Map<String, List<MethodInfo>> classMethods = new ConcurrentHashMap<>();
    // 索引：接口名 -> 实现类名列表
    private final Map<String, List<String>> interfaceImplementations = new ConcurrentHashMap<>();
    // 索引：类名 -> 父类名
    private final Map<String, String> inheritanceMap = new ConcurrentHashMap<>();

    /**
     * 扫描项目目录下所有 Java 源文件。
     *
     * @param projectDir 项目根目录
     * @return 扫描到的文件数量
     */
    public int scanProject(Path projectDir) {
        fileResults.clear();
        classNameToPath.clear();
        classMethods.clear();
        interfaceImplementations.clear();
        inheritanceMap.clear();

        List<Path> javaFiles = new ArrayList<>();
        try {
            Files.walkFileTree(projectDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relativePath = projectDir.relativize(file).toString().replace('\\', '/');
                    if (ASTAnalyzer.isJavaFile(relativePath)) {
                        javaFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    return (name.equals("target") || name.equals("build") || name.equals(".git"))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("扫描项目目录失败: {}", e.getMessage());
            return 0;
        }

        int count = 0;
        for (Path file : javaFiles) {
            try {
                String content = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                String relativePath = projectDir.relativize(file).toString().replace('\\', '/');
                addFile(relativePath, content);
                count++;
            } catch (IOException e) {
                log.debug("读取文件失败: {} - {}", file, e.getMessage());
            }
        }

        buildInheritanceIndex();
        log.info("项目扫描完成: {} 个 Java 文件", count);
        return count;
    }

    /**
     * 添加单个文件的分析结果。
     */
    public void addFile(String filePath, String content) {
        ASTAnalysisResult result = analyzer.analyze(filePath, content);
        fileResults.put(filePath, result);

        if (!result.isParseSucceeded()) return;

        for (ClassInfo cls : result.getClasses()) {
            classNameToPath.put(cls.getName(), filePath);

            // 索引方法到类
            List<MethodInfo> classMethodsList = new ArrayList<>();
            for (MethodInfo method : result.getMethods()) {
                if (isMethodInClass(method, cls)) {
                    classMethodsList.add(method);
                }
            }
            classMethods.put(cls.getName(), Collections.unmodifiableList(classMethodsList));

            // 索引继承关系
            if (cls.getSuperClass() != null && !cls.getSuperClass().isEmpty()) {
                inheritanceMap.put(cls.getName(), cls.getSuperClass());
            }
        }
    }

    /**
     * 获取指定文件的分析结果。
     */
    public Optional<ASTAnalysisResult> getFileResult(String filePath) {
        return Optional.ofNullable(fileResults.get(filePath));
    }

    /**
     * 获取所有文件的分析结果。
     */
    public Map<String, ASTAnalysisResult> getAllResults() {
        return Collections.unmodifiableMap(fileResults);
    }

    /**
     * 通过类名查找文件路径。
     */
    public Optional<String> findFileByClassName(String className) {
        return Optional.ofNullable(classNameToPath.get(className));
    }

    /**
     * 获取指定类的所有方法。
     */
    public List<MethodInfo> getMethodsOfClass(String className) {
        return classMethods.getOrDefault(className, List.of());
    }

    /**
     * 获取指定接口的所有实现类。
     */
    public List<String> getImplementationsOf(String interfaceName) {
        return interfaceImplementations.getOrDefault(interfaceName, List.of());
    }

    /**
     * 获取指定类的父类。
     */
    public Optional<String> getParentClass(String className) {
        return Optional.ofNullable(inheritanceMap.get(className));
    }

    /**
     * 解析调用边：给定一个 calleeScope 和 calleeMethod，尝试找到目标文件。
     * <p>
     * 例如：scope="orderDAO", method="save" → 查找 OrderDAO.java
     */
    public Optional<String> resolveCallTarget(String calleeScope, String calleeMethod) {
        if (calleeScope == null || calleeScope.isEmpty()) {
            return Optional.empty();
        }

        // 直接匹配类名
        String targetPath = classNameToPath.get(calleeScope);
        if (targetPath != null) {
            return Optional.of(targetPath);
        }

        // 尝试首字母大写（字段名转类名：orderDAO → OrderDAO）
        String capitalizedName = Character.toUpperCase(calleeScope.charAt(0)) + calleeScope.substring(1);
        targetPath = classNameToPath.get(capitalizedName);
        if (targetPath != null) {
            return Optional.of(targetPath);
        }

        // 尝试移除常见后缀（service → ServiceService? 不太可能，但 impl → XxxImpl 有可能）
        for (String suffix : List.of("Impl", "Service", "DAO", "Repository", "Controller")) {
            if (calleeScope.endsWith(suffix.toLowerCase())) {
                continue;
            }
            targetPath = classNameToPath.get(calleeScope + suffix);
            if (targetPath != null) {
                return Optional.of(targetPath);
            }
        }

        return Optional.empty();
    }

    /**
     * 构建跨文件调用图：返回所有可以跨文件解析的调用关系。
     */
    public List<CrossFileCall> buildCrossFileCallGraph() {
        List<CrossFileCall> crossFileCalls = new ArrayList<>();

        for (var entry : fileResults.entrySet()) {
            String sourcePath = entry.getKey();
            ASTAnalysisResult result = entry.getValue();

            if (!result.isParseSucceeded()) continue;

            for (ResolvedCallEdge edge : result.getResolvedCallEdges()) {
                if (edge.getCalleeScope().isEmpty()) continue;

                resolveCallTarget(edge.getCalleeScope(), edge.getCalleeMethod())
                        .ifPresent(targetPath -> {
                            if (!targetPath.equals(sourcePath)) {
                                crossFileCalls.add(new CrossFileCall(
                                        sourcePath, edge.getCallerClass(), edge.getCallerMethod(),
                                        targetPath, edge.getCalleeScope(), edge.getCalleeMethod(),
                                        edge.getLineNumber()));
                            }
                        });
            }
        }

        return crossFileCalls;
    }

    private void buildInheritanceIndex() {
        for (var entry : fileResults.entrySet()) {
            ASTAnalysisResult result = entry.getValue();
            if (!result.isParseSucceeded()) continue;

            for (ClassInfo cls : result.getClasses()) {
                for (String iface : cls.getInterfaces()) {
                    interfaceImplementations.computeIfAbsent(iface, k -> new ArrayList<>())
                            .add(cls.getName());
                }
            }
        }
    }

    private boolean isMethodInClass(MethodInfo method, ClassInfo cls) {
        return method.getStartLine() >= cls.getStartLine() && method.getEndLine() <= cls.getEndLine();
    }

    /**
     * 跨文件调用关系。
     */
    public static class CrossFileCall {
        private final String sourceFile;
        private final String sourceClass;
        private final String sourceMethod;
        private final String targetFile;
        private final String targetClass;
        private final String targetMethod;
        private final int lineNumber;

        public CrossFileCall(String sourceFile, String sourceClass, String sourceMethod,
                             String targetFile, String targetClass, String targetMethod,
                             int lineNumber) {
            this.sourceFile = sourceFile;
            this.sourceClass = sourceClass;
            this.sourceMethod = sourceMethod;
            this.targetFile = targetFile;
            this.targetClass = targetClass;
            this.targetMethod = targetMethod;
            this.lineNumber = lineNumber;
        }

        public String getSourceFile() { return sourceFile; }
        public String getSourceClass() { return sourceClass; }
        public String getSourceMethod() { return sourceMethod; }
        public String getTargetFile() { return targetFile; }
        public String getTargetClass() { return targetClass; }
        public String getTargetMethod() { return targetMethod; }
        public int getLineNumber() { return lineNumber; }

        @Override
        public String toString() {
            return sourceFile + ":" + sourceClass + "." + sourceMethod +
                    " -> " + targetFile + ":" + targetClass + "." + targetMethod +
                    " (line " + lineNumber + ")";
        }
    }
}
