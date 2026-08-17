package com.gsim.agentlib.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for configurable response limits ({@link McpResponseConfig}) and the pluggable
 * {@link ToolResultOverflowHandler} in {@link ToolRegistryMcpAdapter}.
 *
 * <p>All overflow scenarios drive a fake {@link AgentTool} that returns oversized
 * {@link ToolResult}s through the adapter's full execute → serialize pipeline.
 */
@DisplayName("MCP 响应溢出处理与可配限值测试")
class McpAdapterOverflowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Marker appended by truncateSnippetsInJson after a 300-char snippet. */
    private static final String TRUNC_MARKER = "truncated — use query_element or _page for full content";

    /** A tool that returns {@code itemCount} items, each with a snippet of {@code snippetLength} chars. */
    static class BigResultTool implements AgentTool {
        private final int itemCount;
        private final int snippetLength;

        BigResultTool(int itemCount, int snippetLength) {
            this.itemCount = itemCount;
            this.snippetLength = snippetLength;
        }

        @Override
        public String name() {
            return "big_tool";
        }

        @Override
        public String description() {
            return "Returns oversized tool results";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            List<ToolResult.Item> items = new ArrayList<>(itemCount);
            for (int i = 0; i < itemCount; i++) {
                items.add(new ToolResult.Item("item-" + i, "path-" + i, "S".repeat(snippetLength), 1.0));
            }
            return ToolResult.ok("big_tool", items);
        }
    }

    /** Rewrites every snippet to a short staged notice (mimics Todo 11 doc-staging handler). */
    static class StagingLikeHandler implements ToolResultOverflowHandler {
        final AtomicBoolean invoked = new AtomicBoolean(false);

        @Override
        public ToolResult handle(ToolResult result, String toolName) {
            invoked.set(true);
            List<ToolResult.Item> staged = result.items().stream()
                    .map(it -> new ToolResult.Item(it.title(), it.path(), "STAGED:" + it.title(), it.score()))
                    .toList();
            return ToolResult.ok(result.toolName(), staged);
        }
    }

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    private ToolRegistryMcpAdapter adapterFor(McpResponseConfig config, ToolResultOverflowHandler handler) {
        return new ToolRegistryMcpAdapter(registry, null, config, handler);
    }

    @Test
    @DisplayName("(1) ≤50KB 结果原样透传，不做截断")
    void smallResultPassesThroughUnchanged() throws Exception {
        registry.register(new BigResultTool(10, 2000)); // ≈ 20KB serialized
        ToolRegistryMcpAdapter adapter = adapterFor(McpResponseConfig.defaults(), null);

        String json = adapter.execute("gsim_big_tool", MAPPER.createObjectNode());

        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("S".repeat(2000)), "full snippet must be present unchanged");
        assertFalse(json.contains(TRUNC_MARKER), "no truncation marker for small result");
    }

    @Test
    @DisplayName("(2) >50KB + handler → 响应包含 handler 改写内容 (STAGED:<id>)")
    void oversizedResultWithHandlerUsesHandlerRewrite() throws Exception {
        registry.register(new BigResultTool(20, 3000)); // ≈ 61KB after 20-item slice
        StagingLikeHandler handler = new StagingLikeHandler();
        ToolRegistryMcpAdapter adapter = adapterFor(McpResponseConfig.defaults(), handler);

        String json = adapter.execute("gsim_big_tool", MAPPER.createObjectNode());

        assertTrue(handler.invoked.get(), "handler must be invoked on overflow");
        assertTrue(json.contains("\"snippet\":\"STAGED:item-0\""), "rewritten snippet must appear");
        assertTrue(json.contains("\"snippet\":\"STAGED:item-19\""), "all items rewritten");
        assertFalse(json.contains(TRUNC_MARKER), "no truncation marker when handler shrank the result");
    }

    @Test
    @DisplayName("(3) >50KB 无 handler → snippet 截断到 300 字符并带截断标记")
    void oversizedResultWithoutHandlerTruncatesTo300() throws Exception {
        registry.register(new BigResultTool(20, 3000));
        ToolRegistryMcpAdapter adapter = adapterFor(McpResponseConfig.defaults(), null);

        String json = adapter.execute("gsim_big_tool", MAPPER.createObjectNode());

        assertTrue(json.contains(TRUNC_MARKER), "truncation marker must be present");
        assertTrue(json.contains("\"snippet\":\"" + "S".repeat(300)), "snippet cut to 300 chars");
        assertFalse(json.contains("S".repeat(301)), "no snippet run longer than 300 chars");
    }

    @Test
    @DisplayName("(4) maxPageSize=5 时 _pageSize=99 被钳制到 5")
    void pageSizeClampedToMaxPageSize() throws Exception {
        registry.register(new BigResultTool(10, 50));
        ToolRegistryMcpAdapter adapter = adapterFor(new McpResponseConfig(1, 5, 50000, 300, true), null);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("_pageSize", 99);

        String json = adapter.execute("gsim_big_tool", args);
        JsonNode parsed = MAPPER.readTree(json);

        assertEquals(5, parsed.get("itemCount").asInt(), "only maxPageSize items per page");
        assertEquals(5, parsed.get("_pageSize").asInt(), "_pageSize clamped to maxPageSize");
        assertEquals(10, parsed.get("_totalItems").asInt(), "total items metadata intact");
    }

    @Test
    @DisplayName("(5) stagingEnabled=false + handler 存在 → 走截断路径，handler 被忽略")
    void stagingDisabledIgnoresHandler() throws Exception {
        registry.register(new BigResultTool(20, 3000));
        StagingLikeHandler handler = new StagingLikeHandler();
        ToolRegistryMcpAdapter adapter =
                adapterFor(new McpResponseConfig(20, 100, 50000, 300, false), handler);

        String json = adapter.execute("gsim_big_tool", MAPPER.createObjectNode());

        assertFalse(handler.invoked.get(), "handler must NOT be invoked when staging disabled");
        assertFalse(json.contains("STAGED:"), "no handler rewrite content");
        assertTrue(json.contains(TRUNC_MARKER), "fallback truncation path used");
    }

    @Test
    @DisplayName("(6) handler 返回 null → 回退截断，不抛 NPE")
    void nullHandlerResultFallsBackToTruncation() throws Exception {
        registry.register(new BigResultTool(20, 3000));
        ToolRegistryMcpAdapter adapter = adapterFor(
                McpResponseConfig.defaults(),
                (result, toolName) -> null);

        String json = adapter.execute("gsim_big_tool", MAPPER.createObjectNode());

        assertTrue(json.contains(TRUNC_MARKER), "fallback truncation applied when handler returns null");
        assertTrue(json.contains("\"snippet\":\"" + "S".repeat(300)), "snippet cut to 300 chars");
        assertFalse(json.contains("S".repeat(301)));
    }
}
