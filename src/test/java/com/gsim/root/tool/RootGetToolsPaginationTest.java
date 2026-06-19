package com.gsim.root.tool;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * root_*_get 工具分页能力统一测试。
 * 覆盖所有只读 root get 工具的 offset/limit/full 参数和
 * truncated/originalLength/returnedRange 返回值。
 */
@DisplayName("Root Get Tools Pagination")
public class RootGetToolsPaginationTest {

    private Path tmpDir;
    private DataManager dm;
    private RootToolFactory factory;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("gsim-test-root-get-pagination-");
        dm = TestWorldFactory.createWithDefaultRoot(tmpDir);
        factory = new RootToolFactory(dm, s -> {});
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var s = Files.walk(tmpDir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    private AgentTool getTool(String name) {
        return factory.createAll().stream()
                .filter(t -> name.equals(t.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not found in createAll()"));
    }

    // ==================== All root get tools are registered ====================

    @Test
    @DisplayName("all root get tools are registered in createAll()")
    void allGetToolsRegistered() {
        var tools = factory.createAll();
        assertTrue(tools.stream().anyMatch(t -> "root_world_get".equals(t.name())));
        assertTrue(tools.stream().anyMatch(t -> "root_entities_get".equals(t.name())));
        assertTrue(tools.stream().anyMatch(t -> "root_rules_get".equals(t.name())));
        assertTrue(tools.stream().anyMatch(t -> "root_initial_info_get".equals(t.name())));
        assertTrue(tools.stream().anyMatch(t -> "root_players_get".equals(t.name())));
    }

    // ==================== root_world_get 分页 ====================

    @Test
    @DisplayName("root_world_get: returns truncated/originalLength/returnedRange")
    void rootWorldGetReturnsPaginationFields() {
        AgentTool tool = getTool("root_world_get");
        ToolCall call = new ToolCall("root_world_get", Map.of());
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        assertTrue(snippet.contains("originalLength:"), "should have originalLength");
        assertTrue(snippet.contains("truncated:"), "should have truncated");
        assertTrue(snippet.contains("returnedRange:"), "should have returnedRange");
        assertTrue(snippet.contains("---"), "should have content after separator");
    }

    @Test
    @DisplayName("root_world_get: offset/limit slicing")
    void rootWorldGetOffsetLimit() throws IOException {
        // 写一个已知长度的 world.md 用于测试切片
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            content.append(String.format("Line %03d: world content line here.\n", i));
        }
        dm.updateWorldFile(content.toString());

        AgentTool tool = getTool("root_world_get");

        // 请求 offset=100, limit=200
        ToolCall call = new ToolCall("root_world_get", Map.of(
                "offset", "100", "limit", "200"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("returnedRange: 100-"),
                "should have returnedRange starting at 100, got: " + snippet);
        // 验证截断的内容确实从第100个字符开始
        String contentAfterSep = snippet.substring(snippet.indexOf("---\n") + 4);
        assertTrue(contentAfterSep.startsWith(content.toString().substring(100, Math.min(300, content.length()))),
                "content should start at offset 100");
    }

    @Test
    @DisplayName("root_world_get: full=true returns full content up to MAX_FULL_CHARS")
    void rootWorldGetFull() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            content.append(String.format("Line %03d: test.\n", i));
        }
        dm.updateWorldFile(content.toString());
        int expectedLen = content.toString().length();

        AgentTool tool = getTool("root_world_get");
        ToolCall call = new ToolCall("root_world_get", Map.of("full", "true"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("originalLength: " + expectedLen),
                "should have originalLength=" + expectedLen + ", got: " + snippet);
        assertTrue(snippet.contains("returnedRange: 0-" + expectedLen),
                "should return 0-" + expectedLen + ", got: " + snippet);
        assertTrue(snippet.contains("truncated: false"),
                "should not be truncated for short content");
    }

    @Test
    @DisplayName("root_world_get: truncated=true for content exceeding limit")
    void rootWorldGetTruncated() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            content.append(String.format("Line %05d: %s\n", i, "x".repeat(40)));
        }
        dm.updateWorldFile(content.toString());

        AgentTool tool = getTool("root_world_get");
        ToolCall call = new ToolCall("root_world_get", Map.of());
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("truncated: true"),
                "should be truncated for large content, got: " + snippet);
        assertTrue(snippet.contains("已截断"),
                "should have truncation hint in Chinese");
    }

    @Test
    @DisplayName("root_world_get: offset at or beyond file length returns empty")
    void rootWorldGetOffsetBeyondLength() throws IOException {
        String content = "Short content.";
        dm.updateWorldFile(content);
        int len = content.length();

        AgentTool tool = getTool("root_world_get");
        ToolCall call = new ToolCall("root_world_get", Map.of("offset", String.valueOf(len + 100)));
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "should succeed even with offset beyond length");
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("originalLength: " + len), "should report original length: " + snippet);
    }

    // ==================== root_entities_get 分页 ====================

    @Test
    @DisplayName("root_entities_get: returns pagination fields")
    void rootEntitiesGetReturnsPaginationFields() {
        AgentTool tool = getTool("root_entities_get");
        ToolCall call = new ToolCall("root_entities_get", Map.of());
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("originalLength:"), "should have originalLength");
        assertTrue(snippet.contains("truncated:"), "should have truncated");
        assertTrue(snippet.contains("returnedRange:"), "should have returnedRange");
    }

    // ==================== root_rules_get 分页 ====================

    @Test
    @DisplayName("root_rules_get: returns pagination fields")
    void rootRulesGetReturnsPaginationFields() {
        AgentTool tool = getTool("root_rules_get");
        ToolCall call = new ToolCall("root_rules_get", Map.of());
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("originalLength:"), "should have originalLength");
        assertTrue(snippet.contains("truncated:"), "should have truncated");
        assertTrue(snippet.contains("returnedRange:"), "should have returnedRange");
    }

    // ==================== root_initial_info_get 分页 ====================

    @Test
    @DisplayName("root_initial_info_get: returns pagination fields")
    void rootInitialInfoGetReturnsPaginationFields() {
        AgentTool tool = getTool("root_initial_info_get");
        ToolCall call = new ToolCall("root_initial_info_get", Map.of());
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("originalLength:"), "should have originalLength");
        assertTrue(snippet.contains("truncated:"), "should have truncated");
        assertTrue(snippet.contains("returnedRange:"), "should have returnedRange");
    }

    // ==================== root_players_get 分页 ====================

    @Test
    @DisplayName("root_players_get: returns pagination fields")
    void rootPlayersGetReturnsPaginationFields() {
        AgentTool tool = getTool("root_players_get");
        ToolCall call = new ToolCall("root_players_get", Map.of());
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("originalLength:"), "should have originalLength");
        assertTrue(snippet.contains("truncated:"), "should have truncated");
        assertTrue(snippet.contains("returnedRange:"), "should have returnedRange");
    }

    // ==================== Common behavior ====================

    @Test
    @DisplayName("all get tools return NO_ACTIVE_ROOT when no active root")
    void allGetToolsNoActiveRoot() throws IOException {
        Path emptyDir = Files.createTempDirectory("gsim-test-no-root-pag-");
        DataManager emptyDm = new DataManager(emptyDir);
        RootToolFactory emptyFactory = new RootToolFactory(emptyDm, s -> {});

        for (String name : new String[]{"root_world_get", "root_entities_get",
                "root_rules_get", "root_initial_info_get", "root_players_get"}) {
            AgentTool tool = emptyFactory.createAll().stream()
                    .filter(t -> name.equals(t.name()))
                    .findFirst().orElseThrow();
            ToolResult result = tool.execute(new ToolCall(name, Map.of()));
            assertFalse(result.success(), name + " should fail without active root");
            assertEquals("NO_ACTIVE_ROOT", result.error(),
                    name + " should say NO_ACTIVE_ROOT but got: " + result.error());
        }

        // cleanup
        try (var s = Files.walk(emptyDir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }
}
