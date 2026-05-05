package com.diffguard.domain.agent.tools;

import com.diffguard.domain.agent.core.AgentContext;
import com.diffguard.domain.agent.core.AgentTool;
import com.diffguard.domain.agent.core.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolRegistry")
class ToolRegistryTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("register() 和 getTool()")
    class RegisterAndGetTool {

        @Test
        @DisplayName("注册工具后可按名获取")
        void registerAndGet() {
            ToolRegistry registry = new ToolRegistry();
            AgentTool mockTool = createMockTool("test_tool", "A test tool");
            registry.register(mockTool);

            Optional<AgentTool> found = registry.getTool("test_tool");
            assertTrue(found.isPresent());
            assertEquals("test_tool", found.get().name());
        }

        @Test
        @DisplayName("获取未注册的工具返回空")
        void getUnregisteredToolReturnsEmpty() {
            ToolRegistry registry = new ToolRegistry();
            Optional<AgentTool> found = registry.getTool("nonexistent");
            assertTrue(found.isEmpty());
        }

        @Test
        @DisplayName("register 返回自身支持链式调用")
        void registerReturnsSelf() {
            ToolRegistry registry = new ToolRegistry();
            ToolRegistry result = registry.register(new GetDiffContextTool());
            assertSame(registry, result);
        }
    }

    @Nested
    @DisplayName("registerAll()")
    class RegisterAll {

        @Test
        @DisplayName("批量注册多个工具")
        void registersMultipleTools() {
            ToolRegistry registry = new ToolRegistry();
            registry.registerAll(List.of(
                    new GetDiffContextTool(),
                    new GetFileContentTool(tempDir)
            ));

            assertEquals(2, registry.size());
            assertTrue(registry.getTool("get_diff_context").isPresent());
            assertTrue(registry.getTool("get_file_content").isPresent());
        }

        @Test
        @DisplayName("重复注册覆盖同名工具")
        void duplicateNameOverwrites() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new GetDiffContextTool());
            registry.register(new GetDiffContextTool());

            assertEquals(1, registry.size());
        }
    }

    @Nested
    @DisplayName("getAllTools()")
    class GetAllTools {

        @Test
        @DisplayName("返回所有已注册工具")
        void returnsAllTools() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new GetDiffContextTool());
            registry.register(new GetFileContentTool(tempDir));

            List<AgentTool> tools = registry.getAllTools();
            assertEquals(2, tools.size());
        }

        @Test
        @DisplayName("返回的列表是不可变的")
        void returnsImmutableList() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new GetDiffContextTool());

            assertThrows(UnsupportedOperationException.class,
                    () -> registry.getAllTools().add(new GetDiffContextTool()));
        }
    }

    @Nested
    @DisplayName("getToolNames()")
    class GetToolNames {

        @Test
        @DisplayName("返回所有工具名称")
        void returnsAllNames() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new GetDiffContextTool());
            registry.register(new GetFileContentTool(tempDir));

            List<String> names = registry.getToolNames();
            assertTrue(names.contains("get_diff_context"));
            assertTrue(names.contains("get_file_content"));
        }

        @Test
        @DisplayName("保持注册顺序")
        void maintainsOrder() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new GetDiffContextTool());
            registry.register(new GetFileContentTool(tempDir));

            List<String> names = registry.getToolNames();
            assertEquals("get_diff_context", names.get(0));
            assertEquals("get_file_content", names.get(1));
        }
    }

    @Nested
    @DisplayName("size()")
    class SizeTest {

        @Test
        @DisplayName("初始大小为 0")
        void initialSizeZero() {
            ToolRegistry registry = new ToolRegistry();
            assertEquals(0, registry.size());
        }

        @Test
        @DisplayName("注册后大小增加")
        void sizeIncreasesAfterRegister() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new GetDiffContextTool());
            assertEquals(1, registry.size());
        }
    }

    @Nested
    @DisplayName("createStandardTools()")
    class CreateStandardTools {

        @Test
        @DisplayName("创建标准工具集包含 3 个工具")
        void createsThreeTools() {
            ToolRegistry registry = ToolRegistry.createStandardTools(tempDir);

            assertEquals(3, registry.size());
            assertTrue(registry.getTool("get_file_content").isPresent());
            assertTrue(registry.getTool("get_diff_context").isPresent());
            assertTrue(registry.getTool("get_method_definition").isPresent());
        }
    }

    @Nested
    @DisplayName("createSandboxedTools()")
    class CreateSandboxedTools {

        @Test
        @DisplayName("创建沙箱工具集包含 3 个工具")
        void createsThreeSandboxedTools() {
            FileAccessSandbox sandbox = new FileAccessSandbox(tempDir, Set.of("A.java"));
            ToolRegistry registry = ToolRegistry.createSandboxedTools(tempDir, sandbox);

            assertEquals(3, registry.size());
            assertTrue(registry.getTool("get_file_content").isPresent());
            assertTrue(registry.getTool("get_diff_context").isPresent());
            assertTrue(registry.getTool("get_method_definition").isPresent());
        }
    }

    @Nested
    @DisplayName("createFullToolset()")
    class CreateFullToolset {

        @Test
        @DisplayName("完整工具集包含标准工具 + call graph + related files + semantic search")
        void createsFullToolset() {
            com.diffguard.domain.codegraph.CodeGraph codeGraph = new com.diffguard.domain.codegraph.CodeGraph();
            GetCallGraphTool callGraphTool = new GetCallGraphTool(codeGraph);

            ToolRegistry registry = ToolRegistry.createFullToolset(
                    tempDir, callGraphTool, null);

            // Standard (3) + call_graph (1) + related_files (1) = 5
            assertEquals(5, registry.size());
            assertTrue(registry.getTool("get_file_content").isPresent());
            assertTrue(registry.getTool("get_call_graph").isPresent());
            assertTrue(registry.getTool("get_related_files").isPresent());
        }

        @Test
        @DisplayName("null callGraphTool 不注册 call graph 和 related files")
        void nullCallGraphToolSkipsGraphTools() {
            ToolRegistry registry = ToolRegistry.createFullToolset(
                    tempDir, null, null);

            assertEquals(3, registry.size());
            assertTrue(registry.getTool("get_call_graph").isEmpty());
            assertTrue(registry.getTool("get_related_files").isEmpty());
        }

        @Test
        @DisplayName("null semanticSearchTool 不注册 semantic search")
        void nullSemanticSearchSkipsSearchTool() {
            com.diffguard.domain.codegraph.CodeGraph codeGraph = new com.diffguard.domain.codegraph.CodeGraph();
            GetCallGraphTool callGraphTool = new GetCallGraphTool(codeGraph);

            ToolRegistry registry = ToolRegistry.createFullToolset(
                    tempDir, callGraphTool, null);

            assertTrue(registry.getTool("semantic_search").isEmpty());
        }
    }

    // Helper to create a minimal mock AgentTool
    private AgentTool createMockTool(String name, String desc) {
        return new AgentTool() {
            @Override
            public String name() { return name; }
            @Override
            public String description() { return desc; }
            @Override
            public ToolResult execute(String input, AgentContext context) {
                return ToolResult.ok("mock result");
            }
        };
    }
}
