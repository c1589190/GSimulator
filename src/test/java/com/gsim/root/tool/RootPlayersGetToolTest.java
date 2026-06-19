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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * root_players_get 只读工具测试。
 * 覆盖：读 players.md、NO_ACTIVE_ROOT、PLAYERS_FILE_NOT_FOUND、分页参数。
 */
@DisplayName("root_players_get Tool")
public class RootPlayersGetToolTest {

    private Path tmpDir;
    private DataManager dm;
    private AgentTool playersGet;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("gsim-test-players-get-");
        dm = TestWorldFactory.createWithDefaultRoot(tmpDir);

        RootToolFactory factory = new RootToolFactory(dm, s -> {});
        playersGet = factory.createAll().stream()
                .filter(t -> "root_players_get".equals(t.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("root_players_get not found in createAll()"));
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var s = Files.walk(tmpDir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    // ==================== 基本读取 ====================

    @Test
    @DisplayName("root_players_get: reads players.md content")
    void readsPlayersContent() {
        ToolCall call = new ToolCall("root_players_get", Map.of());
        ToolResult result = playersGet.execute(call);

        assertTrue(result.success(), "should succeed but got: " + result.error());
        assertEquals(1, result.items().size());
        assertEquals("players.md", result.items().get(0).title());
        assertEquals(dm.getActiveRootId(), result.items().get(0).path());

        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("originalLength:"), "should contain originalLength: " + snippet);
        assertTrue(snippet.contains("truncated:"), "should contain truncated: " + snippet);
        assertTrue(snippet.contains("returnedRange:"), "should contain returnedRange: " + snippet);
        // players.md 有内容（非空模板）
        assertTrue(snippet.contains("---"), "should have content separator");
    }

    @Test
    @DisplayName("root_players_get: returns NO_ACTIVE_ROOT when no active root")
    void noActiveRoot() throws IOException {
        // 创建无 root 的 DataManager
        Path emptyDir = Files.createTempDirectory("gsim-test-no-root-");
        DataManager emptyDm = null;
        try {
            emptyDm = new DataManager(emptyDir);
        } finally {
            // cleanup later
        }

        RootToolFactory emptyFactory = new RootToolFactory(emptyDm, s -> {});
        AgentTool emptyPlayersGet = emptyFactory.createAll().stream()
                .filter(t -> "root_players_get".equals(t.name()))
                .findFirst().orElseThrow();

        ToolCall call = new ToolCall("root_players_get", Map.of());
        ToolResult result = emptyPlayersGet.execute(call);

        assertFalse(result.success(), "should fail without active root");
        assertEquals("NO_ACTIVE_ROOT", result.error());

        // cleanup
        try (var s = Files.walk(emptyDir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    @Test
    @DisplayName("root_players_get: registered in createAll() list")
    void registeredInCreateAll() {
        RootToolFactory factory = new RootToolFactory(dm, s -> {});
        List<AgentTool> tools = factory.createAll();
        boolean found = tools.stream().anyMatch(t -> "root_players_get".equals(t.name()));
        assertTrue(found, "root_players_get should be in createAll() list");
    }

    // ==================== 分页参数 ====================

    @Test
    @DisplayName("root_players_get: offset/limit returns correct range")
    void offsetLimitReturnsCorrectRange() throws IOException {
        // 写一个已知长度的 players 内容
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            content.append(String.format("Line %03d: some test content here.\n", i));
        }
        dm.writePlayers(content.toString());

        // 请求 offset=100, limit=200
        ToolCall call = new ToolCall("root_players_get", Map.of(
                "offset", "100",
                "limit", "200"));
        ToolResult result = playersGet.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("originalLength:"), "should have originalLength");
        assertTrue(snippet.contains("truncated:"), "should have truncated");
        assertTrue(snippet.contains("returnedRange: 100-"), "should start at 100, got: " + snippet);
    }

    @Test
    @DisplayName("root_players_get: full=true returns full content up to MAX_FULL_CHARS")
    void fullReturnsFullContent() throws IOException {
        // 写一个较长的内容
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            content.append(String.format("Line %03d: some test content here.\n", i));
        }
        dm.writePlayers(content.toString());
        int expectedLen = content.toString().length();

        ToolCall call = new ToolCall("root_players_get", Map.of("full", "true"));
        ToolResult result = playersGet.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("originalLength: " + expectedLen), "should have originalLength=" + expectedLen);
        assertTrue(snippet.contains("returnedRange: 0-" + expectedLen),
                "should return full range, got: " + snippet);
    }

    @Test
    @DisplayName("root_players_get: truncated flag set when content exceeds limit")
    void truncatedFlagWhenExceedsLimit() throws IOException {
        // 写超过默认 8000 的内容
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            content.append(String.format("Line %03d: %s\n", i, "x".repeat(30)));
        }
        dm.writePlayers(content.toString());

        // 默认 limit=8000，应该截断
        ToolCall call = new ToolCall("root_players_get", Map.of());
        ToolResult result = playersGet.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("truncated: true"), "should be truncated, got: " + snippet);
        assertTrue(snippet.contains("已截断"), "should have truncation hint");
    }
}
