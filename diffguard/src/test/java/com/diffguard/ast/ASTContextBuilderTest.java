package com.diffguard.ast;

import com.diffguard.ast.model.ASTAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ASTContextBuilderTest {

    private final ASTAnalyzer analyzer = new ASTAnalyzer();
    private final ASTContextBuilder builder = new ASTContextBuilder();

    @Test
    void buildContext_producesNonEmptyOutput() {
        String code = """
                public class UserService {
                    private String name;
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """;
        ASTAnalysisResult result = analyzer.analyze("UserService.java", code);
        String diff = "diff --git a/UserService.java\n@@ -1,5 +1,6 @@\n+public class UserService {\n+    private String name;";

        String context = builder.buildContext(result, diff, 1000, "openai");

        assertFalse(context.isEmpty());
        assertTrue(context.contains("[AST Context"));
        assertTrue(context.contains("UserService"));
        assertTrue(context.contains("getName"));
        assertTrue(context.contains("[/AST Context]"));
    }

    @Test
    void buildContext_nullResult() {
        String context = builder.buildContext(null, "diff", 1000, "openai");
        assertEquals("", context);
    }

    @Test
    void buildContext_failedResult() {
        ASTAnalysisResult failed = ASTAnalysisResult.failure("Bad.java", "abc", "parse error");
        String context = builder.buildContext(failed, "diff", 1000, "openai");
        assertEquals("", context);
    }

    @Test
    void buildContext_includesCallGraph() {
        String code = """
                public class OrderService {
                    public void process() {
                        validate();
                        save();
                    }
                    private void validate() {}
                    private void save() {}
                }
                """;
        ASTAnalysisResult result = analyzer.analyze("OrderService.java", code);
        String context = builder.buildContext(result, "diff", 2000, "openai");

        assertTrue(context.contains("calls validate, save") || context.contains("calls validate") || context.contains("calls save"));
    }

    @Test
    void buildContext_includesClassHierarchy() {
        String code = """
                public class AdminService extends UserService implements IAdmin {
                    public void adminAction() {}
                }
                """;
        ASTAnalysisResult result = analyzer.analyze("AdminService.java", code);
        String context = builder.buildContext(result, "diff", 2000, "openai");

        assertTrue(context.contains("extends UserService"));
        assertTrue(context.contains("implements IAdmin"));
    }

    @Test
    void buildContext_includesControlFlow() {
        String code = """
                public class Service {
                    public void process(String input) {
                        if (input == null) return;
                        try {
                            doWork(input);
                        } catch (Exception e) {}
                    }
                    private void doWork(String s) {}
                }
                """;
        ASTAnalysisResult result = analyzer.analyze("Service.java", code);
        String diff = "diff --git a/Service.java\n@@ -2,4 +2,6 @@\n+        if (input == null) return;\n+        try {";

        String context = builder.buildContext(result, diff, 2000, "openai");

        assertTrue(context.contains("IF") || context.contains("TRY_CATCH"));
    }

    @Test
    void buildContext_respectsTokenBudget() {
        StringBuilder code = new StringBuilder("public class BigClass {\n");
        for (int i = 0; i < 50; i++) {
            code.append("    public void method").append(i).append("() {\n");
            for (int j = 0; j < 5; j++) {
                code.append("        helper").append(j).append("();\n");
            }
            code.append("    }\n");
            code.append("    private void helper").append(i).append("() {}\n");
        }
        code.append("}");

        ASTAnalysisResult result = analyzer.analyze("BigClass.java", code.toString());
        String context = builder.buildContext(result, "diff", 500, "openai");

        // Should produce output even with large class
        assertFalse(context.isEmpty());
    }

    @Test
    void extractChangedLines_basicDiff() {
        String diff = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -5,3 +5,4 @@
                 line5
                +added_line
                 line6
                """;

        Set<Integer> lines = builder.extractChangedLines(diff);

        assertFalse(lines.isEmpty());
        assertTrue(lines.contains(6));
    }

    @Test
    void extractChangedLines_emptyInput() {
        Set<Integer> lines = builder.extractChangedLines("");
        assertTrue(lines.isEmpty());

        Set<Integer> nullLines = builder.extractChangedLines(null);
        assertTrue(nullLines.isEmpty());
    }

    @Test
    void buildContext_includesFields() {
        String code = """
                public class Config {
                    private String host;
                    private int port;
                    public String getHost() { return host; }
                }
                """;
        ASTAnalysisResult result = analyzer.analyze("Config.java", code);
        String context = builder.buildContext(result, "diff", 2000, "openai");

        assertTrue(context.contains("Fields"));
        assertTrue(context.contains("String host"));
    }
}
