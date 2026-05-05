package com.diffguard.domain.codegraph;

import com.diffguard.domain.ast.ProjectASTAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CodeGraphTest {

    @TempDir
    Path tempDir;

    private CodeGraph graph;

    @BeforeEach
    void buildSampleGraph() throws IOException {
        // Controller -> Service -> DAO
        writeFile("src/main/java/com/example/Controller.java", """
                package com.example;
                import com.example.Service;
                public class Controller {
                    private Service service;
                    public void handle() {
                        service.process();
                    }
                }
                """);

        writeFile("src/main/java/com/example/Service.java", """
                package com.example;
                import com.example.OrderDAO;
                public class Service {
                    private OrderDAO orderDAO;
                    public void process() {
                        orderDAO.save();
                    }
                    public void validate() {}
                }
                """);

        writeFile("src/main/java/com/example/OrderDAO.java", """
                package com.example;
                public class OrderDAO {
                    public void save() {}
                    public void findById() {}
                }
                """);

        writeFile("src/main/java/com/example/IRoleService.java", """
                package com.example;
                public interface IRoleService {
                    void assign();
                }
                """);

        writeFile("src/main/java/com/example/RoleService.java", """
                package com.example;
                public class RoleService implements IRoleService {
                    public void assign() {}
                }
                """);

        writeFile("src/main/java/com/example/BaseService.java", """
                package com.example;
                public class BaseService {
                    public void init() {}
                }
                """);

        writeFile("src/main/java/com/example/UserService.java", """
                package com.example;
                public class UserService extends BaseService {
                    public void find() {}
                }
                """);

        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        analyzer.scanProject(tempDir);
        graph = CodeGraphBuilder.buildFromAnalyzer(analyzer);
    }

    // --- Node Creation ---

    @Test
    void graphContainsFileNodes() {
        List<GraphNode> files = graph.getNodesByType(GraphNode.Type.FILE);
        assertFalse(files.isEmpty());
        assertTrue(files.stream().anyMatch(n -> n.getName().equals("Controller.java")));
        assertTrue(files.stream().anyMatch(n -> n.getName().equals("Service.java")));
    }

    @Test
    void graphContainsClassNodes() {
        List<GraphNode> classes = graph.getNodesByType(GraphNode.Type.CLASS);
        assertTrue(classes.stream().anyMatch(n -> n.getName().equals("Controller")));
        assertTrue(classes.stream().anyMatch(n -> n.getName().equals("Service")));
        assertTrue(classes.stream().anyMatch(n -> n.getName().equals("OrderDAO")));
    }

    @Test
    void graphContainsInterfaceNodes() {
        List<GraphNode> interfaces = graph.getNodesByType(GraphNode.Type.INTERFACE);
        assertTrue(interfaces.stream().anyMatch(n -> n.getName().equals("IRoleService")));
    }

    @Test
    void graphContainsMethodNodes() {
        List<GraphNode> methods = graph.getNodesByType(GraphNode.Type.METHOD);
        assertTrue(methods.stream().anyMatch(n -> n.getName().equals("handle")));
        assertTrue(methods.stream().anyMatch(n -> n.getName().equals("process")));
        assertTrue(methods.stream().anyMatch(n -> n.getName().equals("save")));
    }

    // --- CONTAINS Edges ---

    @Test
    void fileContainsClasses() {
        GraphNode controllerFile = graph.getNodesByType(GraphNode.Type.FILE).stream()
                .filter(n -> n.getName().equals("Controller.java"))
                .findFirst().orElse(null);
        assertNotNull(controllerFile);

        List<GraphNode> classes = graph.getClassesInFile(controllerFile.getId());
        assertEquals(1, classes.size());
        assertEquals("Controller", classes.get(0).getName());
    }

    @Test
    void classContainsMethods() {
        GraphNode serviceClass = findClassNode("Service");
        List<GraphNode> methods = graph.getMethodsInClass(serviceClass.getId());
        assertTrue(methods.size() >= 2);
        Set<String> methodNames = methods.stream().map(GraphNode::getName).collect(Collectors.toSet());
        assertTrue(methodNames.contains("process"));
        assertTrue(methodNames.contains("validate"));
    }

    // --- EXTENDS Edges ---

    @Test
    void extendsEdge_userServiceExtendsBaseService() {
        GraphNode userService = findClassNode("UserService");
        GraphNode parent = graph.getParentClass(userService.getId()).orElse(null);
        assertNotNull(parent);
        assertEquals("BaseService", parent.getName());
    }

    @Test
    void subClasses_baseServiceHasUserService() {
        GraphNode baseService = findClassNode("BaseService");
        List<GraphNode> subs = graph.getSubClasses(baseService.getId());
        assertTrue(subs.stream().anyMatch(n -> n.getName().equals("UserService")));
    }

    // --- IMPLEMENTS Edges ---

    @Test
    void implementsEdge_roleServiceImplementsInterface() {
        GraphNode iRoleService = findClassNode("IRoleService");
        List<GraphNode> impls = graph.getImplementationsOf(iRoleService.getId());
        assertEquals(1, impls.size());
        assertEquals("RoleService", impls.get(0).getName());
    }

    // --- CALLS Edges (intra-file) ---

    @Test
    void callsEdge_serviceProcessCallsDaoSave() {
        GraphNode processMethod = findMethodNode("Service", "process");
        List<GraphNode> callees = graph.getCalleesOf(processMethod.getId());
        assertTrue(callees.stream().anyMatch(n -> n.getName().equals("save")),
                "process() should call save()");
    }

    @Test
    void callersOf_daoSave() {
        GraphNode saveMethod = findMethodNode("OrderDAO", "save");
        List<GraphNode> callers = graph.getCallersOf(saveMethod.getId());
        assertFalse(callers.isEmpty(), "save() should have callers");
    }

    // --- Impact Analysis ---

    @Test
    void impactSet_changeInDaoSave() {
        GraphNode saveMethod = findMethodNode("OrderDAO", "save");
        Set<GraphNode> impacted = graph.computeImpactSet(Set.of(saveMethod.getId()), 3);
        assertFalse(impacted.isEmpty());

        Set<String> impactedNames = impacted.stream().map(GraphNode::getName).collect(Collectors.toSet());
        assertTrue(impactedNames.contains("process"),
                "Changing save() should impact process()");
    }

    @Test
    void impactSet_changeInServiceProcess() {
        GraphNode processMethod = findMethodNode("Service", "process");
        Set<GraphNode> impacted = graph.computeImpactSet(Set.of(processMethod.getId()), 3);

        Set<String> impactedNames = impacted.stream().map(GraphNode::getName).collect(Collectors.toSet());
        assertTrue(impactedNames.contains("handle"),
                "Changing process() should impact handle()");
    }

    // --- Dependency Queries ---

    @Test
    void dependencies_serviceProcessMethod() {
        GraphNode processMethod = findMethodNode("Service", "process");
        Set<GraphNode> deps = graph.getDependencies(processMethod.getId());
        // process() depends on save()
        assertTrue(deps.stream().anyMatch(n -> "save".equals(n.getName())),
                "process() should depend on save()");
    }

    @Test
    void dependents_daoSaveMethod() {
        GraphNode saveMethod = findMethodNode("OrderDAO", "save");
        Set<GraphNode> dependents = graph.getDependents(saveMethod.getId());
        assertTrue(dependents.stream().anyMatch(n -> "process".equals(n.getName())),
                "process() should be a dependent of save()");
    }

    // --- Shortest Path ---

    @Test
    void shortestPath_controllerToDao() {
        GraphNode handleMethod = findMethodNode("Controller", "handle");
        GraphNode saveMethod = findMethodNode("OrderDAO", "save");

        List<GraphEdge> path = graph.findShortestPath(handleMethod.getId(), saveMethod.getId());
        assertFalse(path.isEmpty(), "Should find path from handle() to save()");

        // Path should be: handle -> process -> save (at least 2 edges)
        assertTrue(path.size() >= 2, "Path should traverse at least 2 call edges");
    }

    // --- Statistics ---

    @Test
    void statistics_nonEmpty() {
        var stats = graph.getStatistics();
        assertTrue(stats.get("totalNodes") > 0);
        assertTrue(stats.get("totalEdges") > 0);
        assertTrue(stats.get("fileNodes") > 0);
        assertTrue(stats.get("classNodes") > 0);
        assertTrue(stats.get("methodNodes") > 0);
    }

    // --- Mermaid Export ---

    @Test
    void mermaidExport_containsAllElements() {
        String mermaid = graph.toMermaid();
        assertTrue(mermaid.startsWith("graph TD"));
        assertTrue(mermaid.contains("Controller"));
        assertTrue(mermaid.contains("Service"));
        assertTrue(mermaid.contains("-->"));
    }

    // --- Helpers ---

    private void writeFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private GraphNode findClassNode(String className) {
        return graph.getNodesByType(GraphNode.Type.CLASS).stream()
                .filter(n -> n.getName().equals(className))
                .findFirst()
                .or(() -> graph.getNodesByType(GraphNode.Type.INTERFACE).stream()
                        .filter(n -> n.getName().equals(className))
                        .findFirst())
                .orElse(null);
    }

    private GraphNode findMethodNode(String className, String methodName) {
        return graph.getNodesByType(GraphNode.Type.METHOD).stream()
                .filter(n -> n.getName().equals(methodName) && n.getId().contains(className + "."))
                .findFirst().orElse(null);
    }
}
