package com.diffguard.domain.ast.spi;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Java AST Provider（基于 JavaParser）。
 * <p>
 * 向后兼容现有 JavaParser 实现，通过 SPI 接口暴露。
 * 未来可替换为 Tree-sitter JNI 实现以获得更好的多语言支持和性能。
 */
public class JavaASTProvider implements LanguageASTProvider {

    private static final Logger log = LoggerFactory.getLogger(JavaASTProvider.class);

    @Override
    public Language language() {
        return Language.JAVA;
    }

    @Override
    public List<ASTNodeInfo> parse(String sourceCode, String filePath) {
        List<ASTNodeInfo> nodes = new ArrayList<>();
        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(MethodDeclaration md, Void arg) {
                    super.visit(md, arg);
                    nodes.add(new ASTNodeInfo(
                            "METHOD",
                            md.getNameAsString(),
                            filePath,
                            md.getRange().map(r -> r.begin.line).orElse(-1),
                            md.getRange().map(r -> r.end.line).orElse(-1),
                            md.toString()
                    ));
                }

                @Override
                public void visit(ClassOrInterfaceDeclaration cid, Void arg) {
                    super.visit(cid, arg);
                    nodes.add(new ASTNodeInfo(
                            cid.isInterface() ? "INTERFACE" : "CLASS",
                            cid.getNameAsString(),
                            filePath,
                            cid.getRange().map(r -> r.begin.line).orElse(-1),
                            cid.getRange().map(r -> r.end.line).orElse(-1),
                            cid.getNameAsString()
                    ));
                }
            }, null);
        } catch (Exception e) {
            log.debug("Java parse failed for {}: {}", filePath, e.getMessage());
        }
        return nodes;
    }

    @Override
    public List<MethodInfo> extractMethods(String sourceCode, String filePath) {
        List<MethodInfo> methods = new ArrayList<>();
        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);
            String className = cu.findFirst(ClassOrInterfaceDeclaration.class)
                    .map(c -> c.getNameAsString())
                    .orElse("Unknown");

            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(MethodDeclaration md, Void arg) {
                    super.visit(md, arg);
                    List<String> params = md.getParameters().stream()
                            .map(p -> p.getType().asString() + " " + p.getNameAsString())
                            .toList();

                    methods.add(new MethodInfo(
                            md.getNameAsString(),
                            className,
                            md.getType().asString(),
                            params,
                            filePath,
                            md.getRange().map(r -> r.begin.line).orElse(-1),
                            md.getRange().map(r -> r.end.line).orElse(-1),
                            computeComplexity(md)
                    ));
                }
            }, null);
        } catch (Exception e) {
            log.debug("Java method extraction failed for {}: {}", filePath, e.getMessage());
        }
        return methods;
    }

    @Override
    public List<CallEdgeInfo> extractCallEdges(String sourceCode, String filePath) {
        List<CallEdgeInfo> edges = new ArrayList<>();
        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);
            String className = cu.findFirst(ClassOrInterfaceDeclaration.class)
                    .map(c -> c.getNameAsString())
                    .orElse("Unknown");

            cu.accept(new VoidVisitorAdapter<Void>() {
                private String currentMethod = "<init>";

                @Override
                public void visit(MethodDeclaration md, Void arg) {
                    String prev = currentMethod;
                    currentMethod = md.getNameAsString();
                    super.visit(md, arg);
                    currentMethod = prev;
                }

                @Override
                public void visit(MethodCallExpr mce, Void arg) {
                    super.visit(mce, arg);
                    String calleeClass = mce.getScope()
                            .map(s -> s.toString())
                            .orElse(className);
                    edges.add(new CallEdgeInfo(
                            currentMethod, className,
                            mce.getNameAsString(), calleeClass,
                            filePath,
                            mce.getRange().map(r -> r.begin.line).orElse(-1)
                    ));
                }
            }, null);
        } catch (Exception e) {
            log.debug("Java call edge extraction failed for {}: {}", filePath, e.getMessage());
        }
        return edges;
    }

    /**
     * 计算圈复杂度（简化版：统计 if/for/while/switch/catch/&&/|| 数量 + 1）。
     */
    private int computeComplexity(MethodDeclaration md) {
        String body = md.toString();
        int complexity = 1;
        complexity += countOccurrences(body, "if(");
        complexity += countOccurrences(body, "if (");
        complexity += countOccurrences(body, "for(");
        complexity += countOccurrences(body, "for (");
        complexity += countOccurrences(body, "while(");
        complexity += countOccurrences(body, "while (");
        complexity += countOccurrences(body, "case ");
        complexity += countOccurrences(body, "catch(");
        complexity += countOccurrences(body, "catch (");
        complexity += countOccurrences(body, "&&");
        complexity += countOccurrences(body, "||");
        return complexity;
    }

    private int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
