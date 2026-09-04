package com.gsim.app.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.agentsmanager.mcp.McpResponseConfig;
import com.gsim.agentsmanager.mcp.ToolRegistryMcpAdapter;
import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DocStagingOverflowHandler} wired into {@link ToolRegistryMcpAdapter}:
 * oversized snippets are staged to {@code docs/tmp/} and replaced with a docId notice;
 * when the handler cannot stage (null docStore / disabled / IO failure), the adapter
 * falls back to legacy truncation without throwing.
 */
@DisplayName("MCP 溢出暂存 handler 测试")
class McpOverflowStagingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 暂存提示文本（DocStaging.stagedNotice 产物）。 */
    private static final String NOTICE = "内容已暂存为文档";
    /** 截断路径的标记（truncateSnippetsInJson 产物）。 */
    private static final String TRUNC_MARKER = "truncated — use query_element or _page for full content";

    private static final Pattern DOC_ID = Pattern.compile("mcp_\\d{8}_\\d{6}_[0-9a-f]{8}");

    /** 返回 {@code itemCount} 条、每条 snippet 为 {@code snippetLength} 字符的测试工具。 */
    static class BigSnippetTool implements AgentTool {
        private final int itemCount;
        private final int snippetLength;

        BigSnippetTool(int itemCount, int snippetLength) {
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
                items.add(new ToolResult.Item("item-" + i, "path-" + i, "D".repeat(snippetLength), 1.0));
            }
            return ToolResult.ok("big_tool", items);
        }
    }

    private ToolRegistry registryWithBigTool(int itemCount, int snippetLength) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BigSnippetTool(itemCount, snippetLength));
        return registry;
    }

    /** 小上限配置：maxJsonBytes=2000 使 60KB 结果必然触发溢出路径。 */
    private static McpResponseConfig smallConfig() {
        return new McpResponseConfig(1, 5, 2000, 100, true);
    }

    @Test
    @DisplayName("超限 snippet 暂存为 docs/tmp 文档，响应含暂存提示与 docId")
    void oversizedSnippetIsStagedToDoc(@TempDir Path tmpDir) throws Exception {
        DocStore docStore = new DocStore(tmpDir.resolve("docs"));
        docStore.init();
        ToolRegistry registry = registryWithBigTool(3, 60_000); // ≈ 60KB snippet
        ToolRegistryMcpAdapter adapter = new ToolRegistryMcpAdapter(
                registry, null, smallConfig(), new DocStagingOverflowHandler(docStore, 500, "mcp_"));

        String json = adapter.execute("gsim_big_tool", MAPPER.createObjectNode());

        assertTrue(json.contains(NOTICE), "response must contain staged notice");
        Matcher m = DOC_ID.matcher(json);
        assertTrue(m.find(), "response must reference a mcp_ docId, got: " + json);
        String docId = m.group();

        Path tmpDoc = tmpDir.resolve("docs").resolve("tmp").resolve(docId + ".md");
        assertTrue(Files.exists(tmpDoc), "staged doc file must exist at " + tmpDoc);
        String stored = Files.readString(tmpDoc);
        assertTrue(stored.contains("D".repeat(1000)), "staged doc must hold the full snippet content");
        assertFalse(json.contains(TRUNC_MARKER), "no truncation marker when staging succeeded");
    }

    @Test
    @DisplayName("docStore 为 null 时返回原结果，适配器回退截断且不抛异常")
    void nullDocStoreFallsBackToTruncation(@TempDir Path tmpDir) throws Exception {
        ToolRegistry registry = registryWithBigTool(3, 60_000);
        ToolRegistryMcpAdapter adapter = new ToolRegistryMcpAdapter(
                registry, null, smallConfig(), new DocStagingOverflowHandler(null, 500, "mcp_"));

        String json = adapter.execute("gsim_big_tool", MAPPER.createObjectNode());

        assertFalse(json.contains(NOTICE), "no staging without docStore");
        assertTrue(json.contains(TRUNC_MARKER), "adapter must fall back to truncation");
    }

    @Test
    @DisplayName("handler 为 null（暂存禁用）时走截断路径")
    void nullHandlerFallsBackToTruncation(@TempDir Path tmpDir) throws Exception {
        ToolRegistry registry = registryWithBigTool(3, 60_000);
        ToolRegistryMcpAdapter adapter = new ToolRegistryMcpAdapter(registry, null, smallConfig(), null);

        String json = adapter.execute("gsim_big_tool", MAPPER.createObjectNode());

        assertFalse(json.contains(NOTICE));
        assertTrue(json.contains(TRUNC_MARKER), "legacy truncation when handler absent");
    }

    @Test
    @DisplayName("暂存写盘失败（IO 异常）时返回原结果，适配器回退截断且不抛异常")
    void stagingIoFailureFallsBackToTruncation(@TempDir Path tmpDir) throws Exception {
        // tmp 路径被普通文件占据 → DocStore.create 的 createDirectories 抛 FileAlreadyExistsException
        Path docsDir = tmpDir.resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("tmp"), "blocker");
        DocStore docStore = new DocStore(docsDir);

        ToolRegistry registry = registryWithBigTool(3, 60_000);
        ToolRegistryMcpAdapter adapter = new ToolRegistryMcpAdapter(
                registry, null, smallConfig(), new DocStagingOverflowHandler(docStore, 500, "mcp_"));

        String json = adapter.execute("gsim_big_tool", MAPPER.createObjectNode());

        assertFalse(json.contains(NOTICE), "staging must not succeed on IO failure");
        assertTrue(json.contains(TRUNC_MARKER), "adapter must fall back to truncation on IO failure");
    }

    @Test
    @DisplayName("未超限结果原样透传，不暂存不截断")
    void smallResultPassesThroughUnchanged(@TempDir Path tmpDir) throws Exception {
        DocStore docStore = new DocStore(tmpDir.resolve("docs"));
        docStore.init();
        ToolRegistry registry = registryWithBigTool(2, 500); // ≈ 500B snippet, 远小于 2000B 上限
        ToolRegistryMcpAdapter adapter = new ToolRegistryMcpAdapter(
                registry, null, smallConfig(), new DocStagingOverflowHandler(docStore, 500, "mcp_"));

        String json = adapter.execute("gsim_big_tool", MAPPER.createObjectNode());

        JsonNode parsed = MAPPER.readTree(json);
        assertTrue(parsed.get("success").asBoolean());
        assertTrue(json.contains("D".repeat(500)), "snippet must be present unchanged");
        assertFalse(json.contains(NOTICE), "no staging below threshold");
        assertFalse(json.contains(TRUNC_MARKER), "no truncation below size ceiling");
    }
}
