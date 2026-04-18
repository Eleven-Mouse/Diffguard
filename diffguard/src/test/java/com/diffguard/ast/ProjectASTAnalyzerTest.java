package com.diffguard.ast;

import com.diffguard.ast.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProjectASTAnalyzerTest {

    @TempDir
    Path tempDir;

    private void writeJavaFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void scanProject_findsJavaFiles() throws IOException {
        writeJavaFile("src/main/java/com/example/Service.java",
                "package com.example; public class Service { public void run() {} }");
        writeJavaFile("src/main/java/com/example/Controller.java",
                "package com.example; public class Controller { public void handle() {} }");
        writeJavaFile("config.yml", "key: value");

        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        int count = analyzer.scanProject(tempDir);

        assertEquals(2, count);
        assertTrue(analyzer.findFileByClassName("Service").isPresent());
        assertTrue(analyzer.findFileByClassName("Controller").isPresent());
        assertFalse(analyzer.findFileByClassName("Config").isPresent());
    }

    @Test
    void scanProject_skipsTargetAndBuild() throws IOException {
        writeJavaFile("src/main/java/App.java", "public class App {}");
        writeJavaFile("target/generated/Gen.java", "public class Gen {}");
        writeJavaFile("build/classes/Build.java", "public class Build {}");

        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        int count = analyzer.scanProject(tempDir);

        assertEquals(1, count);
        assertTrue(analyzer.findFileByClassName("App").isPresent());
        assertFalse(analyzer.findFileByClassName("Gen").isPresent());
        assertFalse(analyzer.findFileByClassName("Build").isPresent());
    }

    @Test
    void crossFileCallGraph_resolvesServiceCalls() throws IOException {
        writeJavaFile("src/Controller.java", """
                public class Controller {
                    private Service service;
                    public void handle() {
                        service.process();
                    }
                }
                """);
        writeJavaFile("src/Service.java", """
                public class Service {
                    public void process() {
                        doWork();
                    }
                    private void doWork() {}
                }
                """);

        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        analyzer.scanProject(tempDir);

        List<ProjectASTAnalyzer.CrossFileCall> calls = analyzer.buildCrossFileCallGraph();
        assertFalse(calls.isEmpty());

        ProjectASTAnalyzer.CrossFileCall crossCall = calls.stream()
                .filter(c -> "process".equals(c.getTargetMethod()))
                .findFirst().orElse(null);
        assertNotNull(crossCall);
        assertEquals("Controller", crossCall.getSourceClass());
        // targetClass is the calleeScope from the source code ("service")
        assertEquals("service", crossCall.getTargetClass());
        // but the target file should be resolved to Service.java
        assertTrue(crossCall.getTargetFile().contains("Service.java"));
    }

    @Test
    void inheritanceMap_tracksExtends() throws IOException {
        writeJavaFile("src/BaseService.java", "public class BaseService { public void init() {} }");
        writeJavaFile("src/UserService.java", """
                public class UserService extends BaseService {
                    public void find() {}
                }
                """);

        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        analyzer.scanProject(tempDir);

        Optional<String> parent = analyzer.getParentClass("UserService");
        assertTrue(parent.isPresent());
        assertEquals("BaseService", parent.get());
    }

    @Test
    void interfaceImplementations_tracksImplements() throws IOException {
        writeJavaFile("src/IRoleService.java", "public interface IRoleService { void assign(); }");
        writeJavaFile("src/RoleService.java", """
                public class RoleService implements IRoleService {
                    public void assign() {}
                }
                """);

        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        analyzer.scanProject(tempDir);

        List<String> impls = analyzer.getImplementationsOf("IRoleService");
        assertEquals(1, impls.size());
        assertEquals("RoleService", impls.get(0));
    }

    @Test
    void methodsOfClass_returnsClassMethods() throws IOException {
        writeJavaFile("src/OrderService.java", """
                public class OrderService {
                    public void create() {}
                    public void cancel() {}
                    private void validate() {}
                }
                """);

        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        analyzer.scanProject(tempDir);

        List<MethodInfo> methods = analyzer.getMethodsOfClass("OrderService");
        assertEquals(3, methods.size());

        List<String> methodNames = methods.stream().map(MethodInfo::getName).toList();
        assertTrue(methodNames.contains("create"));
        assertTrue(methodNames.contains("cancel"));
        assertTrue(methodNames.contains("validate"));
    }

    @Test
    void resolveCallTarget_findsByClassName() throws IOException {
        writeJavaFile("src/OrderDAO.java", "public class OrderDAO { public void save() {} }");

        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        analyzer.scanProject(tempDir);

        Optional<String> target = analyzer.resolveCallTarget("OrderDAO", "save");
        assertTrue(target.isPresent());
    }

    @Test
    void addFile_incrementalBuild() throws IOException {
        writeJavaFile("src/A.java", "public class A { public void run() {} }");

        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        analyzer.scanProject(tempDir);

        // Add another file incrementally
        analyzer.addFile("src/B.java", "public class B { public void exec() {} }");

        assertTrue(analyzer.findFileByClassName("A").isPresent());
        assertTrue(analyzer.findFileByClassName("B").isPresent());
        assertEquals(2, analyzer.getAllResults().size()); // A from scan + B from addFile
    }

    @Test
    void crossFileCall_excludesIntraFileCalls() throws IOException {
        writeJavaFile("src/Service.java", """
                public class Service {
                    public void run() {
                        helper();
                    }
                    private void helper() {}
                }
                """);

        ProjectASTAnalyzer analyzer = new ProjectASTAnalyzer();
        analyzer.scanProject(tempDir);

        List<ProjectASTAnalyzer.CrossFileCall> calls = analyzer.buildCrossFileCallGraph();
        // helper() has no scope (intra-class call), so no cross-file calls expected
        assertTrue(calls.isEmpty());
    }
}
