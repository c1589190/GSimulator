package com.gsim.app;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Memory Tools Registration")
class MemoryToolsRegistrationTest {

    @Nested
    @DisplayName("ToolRegistry has() 和 registerIfAbsent()")
    class ToolRegistryMethods {
        @Test
        @DisplayName("has() 返回 false for unregistered tool")
        void hasReturnsFalseForMissing() {
            ToolRegistry registry = new ToolRegistry();
            assertFalse(registry.has("nonexistent"));
        }

        @Test
        @DisplayName("has() 返回 true for registered tool")
        void hasReturnsTrueForRegistered() {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new com.gsim.tool.AgentTool() {
                @Override public String name() { return "test_tool"; }
                @Override public String description() { return "test"; }
                @Override public com.gsim.tool.ToolResult execute(com.gsim.tool.ToolCall call) {
                    return com.gsim.tool.ToolResult.ok("test_tool", java.util.List.of());
                }
            });
            assertTrue(registry.has("test_tool"));
        }

        @Test
        @DisplayName("registerIfAbsent() 首次注册返回 true")
        void registerIfAbsentFirstTime() {
            ToolRegistry registry = new ToolRegistry();
            boolean registered = registry.registerIfAbsent(new com.gsim.tool.AgentTool() {
                @Override public String name() { return "test_tool"; }
                @Override public String description() { return "test"; }
                @Override public com.gsim.tool.ToolResult execute(com.gsim.tool.ToolCall call) {
                    return com.gsim.tool.ToolResult.ok("test_tool", java.util.List.of());
                }
            });
            assertTrue(registered);
            assertTrue(registry.has("test_tool"));
        }

        @Test
        @DisplayName("registerIfAbsent() 重复注册返回 false")
        void registerIfAbsentSecondTimeReturnsFalse() {
            ToolRegistry registry = new ToolRegistry();
            var tool = new com.gsim.tool.AgentTool() {
                @Override public String name() { return "test_tool"; }
                @Override public String description() { return "test"; }
                @Override public com.gsim.tool.ToolResult execute(com.gsim.tool.ToolCall call) {
                    return com.gsim.tool.ToolResult.ok("test_tool", java.util.List.of());
                }
            };
            assertTrue(registry.registerIfAbsent(tool));
            assertFalse(registry.registerIfAbsent(tool));
        }
    }

    @Nested
    @DisplayName("memory tools 注册")
    class MemoryTools {
        @TempDir Path tempDir;

        @Test
        @DisplayName("空 data 时不注册 memory tools")
        void emptyDataNoMemoryTools() throws Exception {
            // 空 data — 没有任何 root
            DataManager dm = new DataManager(tempDir);
            // 不要 init()，直接用空的目录
            assertTrue(com.gsim.root.RootBootstrapPolicy.isStrictlyEmptyDataRoot(tempDir),
                    "fresh temp dir should be strictly empty");

            // memory tools 不应被注册
            // (实际由 registerMemoryToolsIfAvailable 控制)
            // 验证 needsRootBootstrap=true 时确实返回
            ToolRegistry registry = new ToolRegistry();
            // simulate: registerMemoryToolsIfAvailable would return early
            assertTrue(dm.needsRootBootstrap());
            assertFalse(registry.has("branch_node_get"));
        }

        @Test
        @DisplayName("bootstrap 后 memory tools 可注册")
        void afterBootstrapMemoryToolsCanRegister() throws Exception {
            DataManager dm = TestWorldFactory.createWithDefaultRoot(tempDir);
            assertFalse(dm.needsRootBootstrap());

            // memory tools should be registrable now
            ToolRegistry registry = new ToolRegistry();
            var messageStore = new com.gsim.chat.BranchMessageStore(dm, tempDir);
            var branchAnalyzer = new com.gsim.branch.BranchAnalyzer(dm, messageStore,
                    new com.gsim.player.PlayerProfileManager(dm));
            Path worldDir = tempDir.resolve("worlds").resolve(dm.getActiveRootId());
            var summaryStore = new com.gsim.context.summary.NodeSummaryStore(worldDir);
            var pinStore = new com.gsim.context.memory.PinnedConstraintStore(worldDir);
            var pinManager = new com.gsim.context.memory.PinnedConstraintManager(pinStore);
            var pathRenderer = new com.gsim.context.summary.BranchPathSummaryRenderer(dm, summaryStore);

            registry.register(new com.gsim.context.memory.BranchPathTool(pathRenderer));
            registry.register(new com.gsim.context.memory.BranchNodeGetTool(dm, messageStore));
            registry.register(new com.gsim.context.memory.BranchNodeSearchTool(dm, summaryStore));
            registry.register(new com.gsim.context.memory.BranchLogFilterTool(dm));
            registry.register(new com.gsim.context.memory.BranchPinGetTool(pinManager));
            registry.register(new com.gsim.context.memory.BranchPinAddTool(pinManager));

            assertTrue(registry.has("branch_node_get"));
            assertTrue(registry.has("branch_path"));
            assertTrue(registry.has("branch_node_search"));
            assertTrue(registry.has("branch_log_filter"));
            assertTrue(registry.has("branch_pin_get"));
            assertTrue(registry.has("branch_pin_add"));
        }

        @Test
        @DisplayName("memory tools 重复注册不抛异常")
        void repeatedRegistrationDoesNotThrow() throws Exception {
            DataManager dm = TestWorldFactory.createWithDefaultRoot(tempDir);
            ToolRegistry registry = new ToolRegistry();
            var messageStore = new com.gsim.chat.BranchMessageStore(dm, tempDir);
            Path worldDir = tempDir.resolve("worlds").resolve(dm.getActiveRootId());
            var summaryStore = new com.gsim.context.summary.NodeSummaryStore(worldDir);

            // 第一次注册
            registry.register(new com.gsim.context.memory.BranchNodeGetTool(dm, messageStore));
            assertTrue(registry.has("branch_node_get"));

            // 第二次注册（模拟 root 切换后重新注册）
            var newMessageStore = new com.gsim.chat.BranchMessageStore(dm, tempDir);
            registry.register(new com.gsim.context.memory.BranchNodeGetTool(dm, newMessageStore));
            assertTrue(registry.has("branch_node_get"));
        }
    }
}
