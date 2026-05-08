package com.diffguard.domain.ast;

import com.diffguard.domain.ast.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ASTAnalyzerTest {

    private final ASTAnalyzer analyzer = new ASTAnalyzer();

    @Test
    void isJavaFile_validPath() {
        assertTrue(ASTAnalyzer.isJavaFile("src/main/java/Foo.java"));
        assertTrue(ASTAnalyzer.isJavaFile("Service.java"));
    }

    @Test
    void isJavaFile_nonJava() {
        assertFalse(ASTAnalyzer.isJavaFile("config.yml"));
        assertFalse(ASTAnalyzer.isJavaFile("app.js"));
        assertFalse(ASTAnalyzer.isJavaFile(null));
    }

    @Test
    void isJavaFile_generatedOrTarget() {
        assertFalse(ASTAnalyzer.isJavaFile("target/generated/Foo.java"));
        assertFalse(ASTAnalyzer.isJavaFile("build/generated/Foo.java"));
    }

    @Test
    void analyze_extractsMethods() {
        String code = """
                public class UserService {
                    public String getName() { return name; }
                    private void validate(String input) {
                        if (input == null) throw new IllegalArgumentException();
                    }
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("UserService.java", code);

        assertTrue(result.isParseSucceeded());
        assertEquals("UserService.java", result.getFilePath());
        assertFalse(result.getMethods().isEmpty());

        MethodInfo getName = result.getMethods().stream()
                .filter(m -> "getName".equals(m.getName()))
                .findFirst().orElse(null);
        assertNotNull(getName);
        assertEquals("String", getName.getReturnType());
        assertEquals("public", getName.getVisibility());
        assertTrue(getName.getParameterTypes().isEmpty());

        MethodInfo validate = result.getMethods().stream()
                .filter(m -> "validate".equals(m.getName()))
                .findFirst().orElse(null);
        assertNotNull(validate);
        assertEquals("void", validate.getReturnType());
        assertEquals("private", validate.getVisibility());
        assertEquals(List.of("String"), validate.getParameterTypes());
    }

    @Test
    void analyze_extractsCallGraph() {
        String code = """
                public class OrderService {
                    public void processOrder() {
                        validate();
                        calculate();
                    }
                    private void validate() {}
                    private void calculate() {}
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("OrderService.java", code);

        assertTrue(result.isParseSucceeded());
        List<CallEdge> edges = result.getCallEdges();
        assertFalse(edges.isEmpty());

        List<String> calleesFromProcessOrder = edges.stream()
                .filter(e -> "processOrder".equals(e.getCallerMethod()))
                .map(CallEdge::getCalleeMethod)
                .toList();
        assertTrue(calleesFromProcessOrder.contains("validate"));
        assertTrue(calleesFromProcessOrder.contains("calculate"));
    }

    @Test
    void analyze_extractsClassStructure() {
        String code = """
                public class UserService extends BaseService implements IUserService {
                    private String name;
                    private int age;
                    public String getName() { return name; }
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("UserService.java", code);

        assertTrue(result.isParseSucceeded());
        assertFalse(result.getClasses().isEmpty());

        ClassInfo cls = result.getClasses().get(0);
        assertEquals("UserService", cls.getName());
        assertEquals("class", cls.getType());
        assertEquals("BaseService", cls.getSuperClass());
        assertTrue(cls.getInterfaces().contains("IUserService"));
        assertEquals(2, cls.getFields().size());
    }

    @Test
    void analyze_extractsControlFlow() {
        String code = """
                public class Service {
                    public void process(String input) {
                        if (input == null) return;
                        for (int i = 0; i < 10; i++) {
                            doWork(i);
                        }
                        try {
                            riskyOperation();
                        } catch (Exception e) {
                            handle(e);
                        }
                    }
                    private void doWork(int i) {}
                    private void riskyOperation() {}
                    private void handle(Exception e) {}
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("Service.java", code);

        assertTrue(result.isParseSucceeded());
        List<ControlFlowNode> cfNodes = result.getControlFlowNodes();
        assertFalse(cfNodes.isEmpty());

        Set<String> types = cfNodes.stream().map(ControlFlowNode::getType).collect(java.util.stream.Collectors.toSet());
        assertTrue(types.contains("IF"));
        assertTrue(types.contains("FOR"));
        assertTrue(types.contains("TRY_CATCH"));
    }

    @Test
    void analyze_handlesSyntaxErrors() {
        String code = "public class Broken { public void method( { }";

        ASTAnalysisResult result = analyzer.analyze("Broken.java", code);

        assertFalse(result.isParseSucceeded());
        assertNotNull(result.getParseError());
    }

    @Test
    void analyze_handlesEmptyContent() {
        ASTAnalysisResult result = analyzer.analyze("Empty.java", "");

        assertFalse(result.isParseSucceeded());
    }

    @Test
    void analyze_handlesNullContent() {
        ASTAnalysisResult result = analyzer.analyze("Null.java", null);

        assertFalse(result.isParseSucceeded());
    }

    @Test
    void analyze_extractsEnum() {
        String code = """
                public enum Status {
                    ACTIVE, INACTIVE, DELETED;
                    private String code;
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("Status.java", code);

        assertTrue(result.isParseSucceeded());
        assertFalse(result.getClasses().isEmpty());

        ClassInfo cls = result.getClasses().get(0);
        assertEquals("Status", cls.getName());
        assertEquals("enum", cls.getType());
    }

    @Test
    void analyze_extractsImports() {
        String code = """
                import java.util.List;
                import java.util.Map;
                public class Service {}
                """;

        ASTAnalysisResult result = analyzer.analyze("Service.java", code);

        assertTrue(result.isParseSucceeded());
        assertEquals(2, result.getImports().size());
        assertTrue(result.getImports().contains("java.util.List"));
    }

    @Test
    void analyze_methodAnnotations() {
        String code = """
                public class Service {
                    @Override
                    @Deprecated
                    public void oldMethod() {}
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("Service.java", code);

        assertTrue(result.isParseSucceeded());
        MethodInfo method = result.getMethods().get(0);
        assertTrue(method.getAnnotations().contains("Override"));
        assertTrue(method.getAnnotations().contains("Deprecated"));
    }

    @Test
    void analyze_staticAndFinalModifiers() {
        String code = """
                public class Utils {
                    public static final int MAX = 100;
                    public static synchronized void doIt() {}
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("Utils.java", code);

        assertTrue(result.isParseSucceeded());
        MethodInfo method = result.getMethods().stream()
                .filter(m -> "doIt".equals(m.getName()))
                .findFirst().orElse(null);
        assertNotNull(method);
        assertTrue(method.getModifiers().contains("static"));
        assertTrue(method.getModifiers().contains("synchronized"));
    }

    @Test
    void analyze_contentHashIsConsistent() {
        String code = "public class Foo {}";

        ASTAnalysisResult r1 = analyzer.analyze("Foo.java", code);
        ASTAnalysisResult r2 = analyzer.analyze("Foo.java", code);

        assertEquals(r1.getContentHash(), r2.getContentHash());
    }

    @Test
    void analyze_contentHashDiffersForDifferentContent() {
        ASTAnalysisResult r1 = analyzer.analyze("A.java", "class A {}");
        ASTAnalysisResult r2 = analyzer.analyze("B.java", "class B {}");

        assertNotEquals(r1.getContentHash(), r2.getContentHash());
    }

    // --- P1-14: null 与空白内容的哈希处理 ---

    @Nested
    @DisplayName("null与空白内容哈希处理 (P1-14)")
    class NullAndBlankContentHashTests {

        @Test
        @DisplayName("null内容仍应产生哈希")
        void analyze_nullContent_returnsFailureWithHash() {
            ASTAnalysisResult result = analyzer.analyze("Null.java", null);

            assertFalse(result.isParseSucceeded());
            assertNotNull(result.getContentHash(), "null content should still produce a hash");
        }

        @Test
        @DisplayName("空白内容应正常计算哈希")
        void analyze_blankContent_computesHash() {
            ASTAnalysisResult result = analyzer.analyze("Blank.java", "   ");

            // Blank is NOT null, so hash should be computed normally
            assertNotNull(result.getContentHash());
            assertFalse(result.getContentHash().isEmpty());
        }
    }
}
