package com.diffguard.domain.ast;

import com.diffguard.domain.ast.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ASTAnalyzerEnhancedTest {

    private final ASTAnalyzer analyzer = new ASTAnalyzer();

    // --- ForEach and DoWhile ---

    @Test
    void extractControlFlow_forEachLoop() {
        String code = """
                public class Service {
                    public void process(List<String> items) {
                        for (String item : items) {
                            handle(item);
                        }
                    }
                    private void handle(String s) {}
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("Service.java", code);
        assertTrue(result.isParseSucceeded());

        boolean hasForEach = result.getControlFlowNodes().stream()
                .anyMatch(n -> "FOR_EACH".equals(n.getType()));
        assertTrue(hasForEach, "Should extract FOR_EACH control flow node");
    }

    @Test
    void extractControlFlow_doWhile() {
        String code = """
                public class Service {
                    public void retry() {
                        int attempts = 0;
                        do {
                            attempts++;
                        } while (attempts < 3);
                    }
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("Service.java", code);
        assertTrue(result.isParseSucceeded());

        boolean hasDoWhile = result.getControlFlowNodes().stream()
                .anyMatch(n -> "DO_WHILE".equals(n.getType()));
        assertTrue(hasDoWhile, "Should extract DO_WHILE control flow node");
    }

    // --- Resolved Call Edges ---

    @Test
    void extractResolvedCallEdges_withScope() {
        String code = """
                public class OrderController {
                    private OrderService orderService;
                    public void create() {
                        orderService.processOrder();
                        validate();
                    }
                    private void validate() {}
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("OrderController.java", code);
        assertTrue(result.isParseSucceeded());

        List<ResolvedCallEdge> resolved = result.getResolvedCallEdges();
        assertFalse(resolved.isEmpty());

        // Check for cross-class call with scope
        ResolvedCallEdge scopedCall = resolved.stream()
                .filter(e -> "orderService".equals(e.getCalleeScope()) && "processOrder".equals(e.getCalleeMethod()))
                .findFirst().orElse(null);
        assertNotNull(scopedCall);
        assertEquals("OrderController", scopedCall.getCallerClass());
        assertEquals("create", scopedCall.getCallerMethod());

        // Check for intra-class call without scope
        ResolvedCallEdge localCall = resolved.stream()
                .filter(e -> e.getCalleeScope().isEmpty() && "validate".equals(e.getCalleeMethod()))
                .findFirst().orElse(null);
        assertNotNull(localCall);
        assertEquals("OrderController", localCall.getCallerClass());
    }

    // --- Field Access ---

    @Test
    void extractFieldAccess_readAndWrite() {
        String code = """
                public class UserService {
                    private String name;
                    private int age;

                    public String getName() {
                        return this.name;
                    }

                    public void setName(String name) {
                        this.name = name;
                    }
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("UserService.java", code);
        assertTrue(result.isParseSucceeded());

        List<FieldAccessInfo> accesses = result.getFieldAccesses();
        assertFalse(accesses.isEmpty());

        // Check write access
        boolean hasWrite = accesses.stream()
                .anyMatch(f -> "name".equals(f.getFieldName()) && "write".equals(f.getAccessType()));
        assertTrue(hasWrite, "Should detect field write (this.name = name)");

        // Check read access
        boolean hasRead = accesses.stream()
                .anyMatch(f -> "name".equals(f.getFieldName()) && "read".equals(f.getAccessType()));
        assertTrue(hasRead, "Should detect field read (this.name)");
    }

    // --- Data Flow ---

    @Test
    void extractDataFlow_declarations() {
        String code = """
                public class Service {
                    public void process() {
                        String result = "hello";
                        int count = 0;
                        count = count + 1;
                    }
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("Service.java", code);
        assertTrue(result.isParseSucceeded());

        List<DataFlowNode> dataFlow = result.getDataFlowNodes();
        assertFalse(dataFlow.isEmpty());

        // Check declarations
        boolean hasStringDecl = dataFlow.stream()
                .anyMatch(d -> "result".equals(d.getVariableName())
                        && "String".equals(d.getVariableType())
                        && "declaration".equals(d.getFlowType()));
        assertTrue(hasStringDecl, "Should detect String variable declaration");

        boolean hasIntDecl = dataFlow.stream()
                .anyMatch(d -> "count".equals(d.getVariableName())
                        && "int".equals(d.getVariableType())
                        && "declaration".equals(d.getFlowType()));
        assertTrue(hasIntDecl, "Should detect int variable declaration");

        // Check assignment
        boolean hasAssignment = dataFlow.stream()
                .anyMatch(d -> "count".equals(d.getVariableName())
                        && "assignment".equals(d.getFlowType()));
        assertTrue(hasAssignment, "Should detect variable assignment");
    }

    // --- Enclosing Class Resolution for Inner Classes ---

    @Test
    void resolvedCallEdges_innerClass() {
        String code = """
                public class Outer {
                    public void outerMethod() {
                        helper();
                    }

                    class Inner {
                        public void innerMethod() {
                            helper();
                        }
                    }

                    private void helper() {}
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("Outer.java", code);
        assertTrue(result.isParseSucceeded());

        List<ResolvedCallEdge> edges = result.getResolvedCallEdges();

        // outerMethod should have callerClass = "Outer"
        ResolvedCallEdge outerCall = edges.stream()
                .filter(e -> "outerMethod".equals(e.getCallerMethod()))
                .findFirst().orElse(null);
        assertNotNull(outerCall);
        assertEquals("Outer", outerCall.getCallerClass());

        // innerMethod should have callerClass = "Inner"
        ResolvedCallEdge innerCall = edges.stream()
                .filter(e -> "innerMethod".equals(e.getCallerMethod()))
                .findFirst().orElse(null);
        assertNotNull(innerCall);
        assertEquals("Inner", innerCall.getCallerClass());
    }

    // --- Backward Compatibility ---

    @Test
    void existingCallEdges_stillWork() {
        String code = """
                public class Service {
                    public void run() {
                        step1();
                        step2();
                    }
                    private void step1() {}
                    private void step2() {}
                }
                """;

        ASTAnalysisResult result = analyzer.analyze("Service.java", code);
        assertTrue(result.isParseSucceeded());

        // Original CallEdge still works
        List<CallEdge> edges = result.getCallEdges();
        assertFalse(edges.isEmpty());

        List<String> callees = edges.stream()
                .filter(e -> "run".equals(e.getCallerMethod()))
                .map(CallEdge::getCalleeMethod)
                .toList();
        assertTrue(callees.contains("step1"));
        assertTrue(callees.contains("step2"));
    }
}
