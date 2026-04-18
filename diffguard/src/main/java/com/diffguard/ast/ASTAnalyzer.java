package com.diffguard.ast;

import com.diffguard.ast.model.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 基于 JavaParser 的 AST 分析器，提取 Java 文件的结构化信息。
 * <p>
 * 提取内容：方法定义、方法调用图、类结构、控制流节点、import 列表。
 * 所有异常在内部消化，不会向外传播。
 */
public class ASTAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ASTAnalyzer.class);

    /**
     * 解析单个 Java 文件并提取结构化信息。
     *
     * @param filePath    文件相对路径（用于标识）
     * @param fileContent 完整源代码内容
     * @return AST 分析结果，解析失败时 parseSucceeded=false
     */
    public ASTAnalysisResult analyze(String filePath, String fileContent) {
        if (fileContent == null || fileContent.isBlank()) {
            String hash = fileContent == null ? "null" : computeHash(fileContent);
            return ASTAnalysisResult.failure(filePath, hash, "空内容");
        }

        String contentHash = computeHash(fileContent);
        try {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> parseResult = parser.parse(fileContent);

            if (!parseResult.isSuccessful()) {
                String errors = parseResult.getProblems().stream()
                        .map(p -> p.getMessage())
                        .limit(3)
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("未知解析错误");
                log.debug("AST 解析失败 {}: {}", filePath, errors);
                return ASTAnalysisResult.failure(filePath, contentHash, errors);
            }

            CompilationUnit cu = parseResult.getResult().orElse(null);
            if (cu == null) {
                return ASTAnalysisResult.failure(filePath, contentHash, "解析结果为 null");
            }

            ASTAnalysisResult.Builder builder = new ASTAnalysisResult.Builder(filePath, contentHash);

            // 提取 imports
            List<String> imports = cu.getImports().stream()
                    .map(imp -> imp.getNameAsString())
                    .toList();
            builder.imports(imports);

            // 提取类、方法、调用图、控制流
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cd -> extractClassInfo(cd, builder));
            cu.findAll(EnumDeclaration.class).forEach(ed -> extractEnumInfo(ed, builder));
            cu.findAll(RecordDeclaration.class).forEach(rd -> extractRecordInfo(rd, builder));

            extractMethodsAndCalls(cu, builder);
            extractControlFlow(cu, builder);

            return builder.build();

        } catch (Exception e) {
            log.debug("AST 分析异常 {}: {}", filePath, e.getMessage());
            return ASTAnalysisResult.failure(filePath, contentHash, e.getMessage());
        }
    }

    /**
     * 判断文件路径是否为可解析的 Java 源文件。
     */
    public static boolean isJavaFile(String filePath) {
        if (filePath == null || !filePath.endsWith(".java")) {
            return false;
        }
        String lower = filePath.toLowerCase();
        return !lower.contains("/generated/") && !lower.contains("/target/");
    }

    private void extractClassInfo(ClassOrInterfaceDeclaration cd, ASTAnalysisResult.Builder builder) {
        String type = cd.isInterface() ? "interface" : "class";
        String superClass = cd.getExtendedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .findFirst().orElse(null);
        List<String> interfaces = cd.getImplementedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .toList();
        List<String> fields = cd.getFields().stream()
                .map(f -> f.getVariables().stream()
                        .map(v -> f.getElementType().asString() + " " + v.getNameAsString())
                        .toList())
                .flatMap(Collection::stream)
                .toList();

        cd.getRange().ifPresent(range ->
                builder.classInfo(new ClassInfo(cd.getNameAsString(), type, superClass,
                        interfaces, fields, range.begin.line, range.end.line)));
    }

    private void extractEnumInfo(EnumDeclaration ed, ASTAnalysisResult.Builder builder) {
        List<String> interfaces = ed.getImplementedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .toList();
        List<String> fields = ed.getFields().stream()
                .map(f -> f.getVariables().stream()
                        .map(v -> f.getElementType().asString() + " " + v.getNameAsString())
                        .toList())
                .flatMap(Collection::stream)
                .toList();

        ed.getRange().ifPresent(range ->
                builder.classInfo(new ClassInfo(ed.getNameAsString(), "enum", null,
                        interfaces, fields, range.begin.line, range.end.line)));
    }

    private void extractRecordInfo(RecordDeclaration rd, ASTAnalysisResult.Builder builder) {
        List<String> interfaces = rd.getImplementedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .toList();
        List<String> fields = rd.getParameters().stream()
                .map(p -> p.getType().asString() + " " + p.getNameAsString())
                .toList();

        rd.getRange().ifPresent(range ->
                builder.classInfo(new ClassInfo(rd.getNameAsString(), "record", null,
                        interfaces, fields, range.begin.line, range.end.line)));
    }

    private void extractMethodsAndCalls(CompilationUnit cu, ASTAnalysisResult.Builder builder) {
        cu.findAll(MethodDeclaration.class).forEach(md -> {
            if (!md.getRange().isPresent()) return;

            String methodName = md.getNameAsString();
            String returnType = md.getType().asString();
            List<String> paramTypes = md.getParameters().stream()
                    .map(p -> p.getType().asString())
                    .toList();
            List<String> paramNames = md.getParameters().stream()
                    .map(p -> p.getNameAsString())
                    .toList();
            var range = md.getRange().get();
            String visibility = md.getAccessSpecifier().asString();
            Set<String> modifiers = new TreeSet<>();
            if (md.isStatic()) modifiers.add("static");
            if (md.isFinal()) modifiers.add("final");
            if (md.isSynchronized()) modifiers.add("synchronized");
            if (md.isAbstract()) modifiers.add("abstract");
            List<String> annotations = md.getAnnotations().stream()
                    .map(a -> a.getNameAsString())
                    .toList();

            builder.method(new MethodInfo(methodName, returnType, paramTypes, paramNames,
                    range.begin.line, range.end.line, visibility, modifiers, annotations));

            // Resolve enclosing class name by walking up parent nodes
            String enclosingClassName = resolveEnclosingClass(md);

            // 收集该方法内的调用边（原始 + 增强调用边）
            md.findAll(MethodCallExpr.class).forEach(call -> {
                call.getRange().ifPresent(callRange -> {
                    String callee = call.getNameAsString();
                    builder.callEdge(new CallEdge(methodName, callee, callRange.begin.line));

                    // 构建带类上下文的调用边
                    String calleeScope = call.getScope()
                            .map(s -> s.toString())
                            .orElse("");
                    builder.resolvedCallEdge(new ResolvedCallEdge(
                            enclosingClassName, methodName, calleeScope, callee, callRange.begin.line));
                });
            });

            // 提取字段访问
            extractFieldAccess(md, methodName, builder);

            // 提取数据流
            extractDataFlow(md, methodName, builder);
        });
    }

    private String resolveEnclosingClass(MethodDeclaration md) {
        var parent = md.getParentNode();
        while (parent.isPresent()) {
            var node = parent.get();
            if (node instanceof ClassOrInterfaceDeclaration cid) {
                return cid.getNameAsString();
            }
            if (node instanceof EnumDeclaration ed) {
                return ed.getNameAsString();
            }
            if (node instanceof RecordDeclaration rd) {
                return rd.getNameAsString();
            }
            parent = node.getParentNode();
        }
        return "";
    }

    private void extractFieldAccess(MethodDeclaration md, String methodName,
                                     ASTAnalysisResult.Builder builder) {
        md.findAll(FieldAccessExpr.class).forEach(fa -> {
            fa.getRange().ifPresent(range -> {
                builder.fieldAccess(new FieldAccessInfo(
                        fa.getNameAsString(),
                        "read",
                        methodName,
                        range.begin.line));
            });
        });

        // 检测字段赋值 (this.field = value)
        md.findAll(AssignExpr.class).forEach(assign -> {
            if (assign.getTarget() instanceof FieldAccessExpr fa) {
                fa.getRange().ifPresent(range -> {
                    builder.fieldAccess(new FieldAccessInfo(
                            fa.getNameAsString(),
                            "write",
                            methodName,
                            range.begin.line));
                });
            }
        });
    }

    private void extractDataFlow(MethodDeclaration md, String methodName,
                                  ASTAnalysisResult.Builder builder) {
        // Track variable declarations
        md.findAll(VariableDeclarationExpr.class).forEach(vde -> {
            vde.getRange().ifPresent(range -> {
                vde.getVariables().forEach(v -> {
                    builder.dataFlowNode(new DataFlowNode(
                            v.getNameAsString(),
                            vde.getCommonType().asString(),
                            "declaration",
                            methodName,
                            range.begin.line));
                });
            });
        });

        // Track assignments to existing variables
        md.findAll(AssignExpr.class).forEach(assign -> {
            if (assign.getTarget() instanceof NameExpr ne) {
                ne.getRange().ifPresent(range -> {
                    builder.dataFlowNode(new DataFlowNode(
                            ne.getNameAsString(),
                            "",
                            "assignment",
                            methodName,
                            range.begin.line));
                });
            }
        });
    }

    private void extractControlFlow(CompilationUnit cu, ASTAnalysisResult.Builder builder) {
        cu.findAll(IfStmt.class).forEach(stmt ->
                stmt.getRange().ifPresent(range ->
                        builder.controlFlowNode(new ControlFlowNode("IF", range.begin.line,
                                range.end.line, simplifyCondition(stmt.getCondition().toString())))));

        cu.findAll(ForStmt.class).forEach(stmt ->
                stmt.getRange().ifPresent(range ->
                        builder.controlFlowNode(new ControlFlowNode("FOR", range.begin.line,
                                range.end.line, simplifyCondition(stmt.getCompare().toString())))));

        cu.findAll(ForEachStmt.class).forEach(stmt ->
                stmt.getRange().ifPresent(range ->
                        builder.controlFlowNode(new ControlFlowNode("FOR_EACH", range.begin.line,
                                range.end.line, simplifyCondition(stmt.getIterable().toString())))));

        cu.findAll(DoStmt.class).forEach(stmt ->
                stmt.getRange().ifPresent(range ->
                        builder.controlFlowNode(new ControlFlowNode("DO_WHILE", range.begin.line,
                                range.end.line, simplifyCondition(stmt.getCondition().toString())))));

        cu.findAll(WhileStmt.class).forEach(stmt ->
                stmt.getRange().ifPresent(range ->
                        builder.controlFlowNode(new ControlFlowNode("WHILE", range.begin.line,
                                range.end.line, simplifyCondition(stmt.getCondition().toString())))));

        cu.findAll(TryStmt.class).forEach(stmt ->
                stmt.getRange().ifPresent(range -> {
                    String catches = stmt.getCatchClauses().stream()
                            .map(c -> c.getParameter().getType().asString())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");
                    builder.controlFlowNode(new ControlFlowNode("TRY_CATCH", range.begin.line,
                            range.end.line, catches.isEmpty() ? "" : "catches: " + catches));
                }));

        cu.findAll(SwitchStmt.class).forEach(stmt ->
                stmt.getRange().ifPresent(range ->
                        builder.controlFlowNode(new ControlFlowNode("SWITCH", range.begin.line,
                                range.end.line, simplifyCondition(stmt.getSelector().toString())))));

        cu.findAll(SynchronizedStmt.class).forEach(stmt ->
                stmt.getRange().ifPresent(range ->
                        builder.controlFlowNode(new ControlFlowNode("SYNCHRONIZED", range.begin.line,
                                range.end.line, ""))));
    }

    private String simplifyCondition(String condition) {
        if (condition.length() > 60) {
            return condition.substring(0, 57) + "...";
        }
        return condition;
    }

    static String computeHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }
}
