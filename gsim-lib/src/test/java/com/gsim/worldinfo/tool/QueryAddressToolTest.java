package com.gsim.worldinfo.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.NodeLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("通用地址查询")
class QueryAddressToolTest {

    @TempDir
    Path worldsDir;

    private ToolRegistry toolRegistry;
    private Supplier<WorldInformation> wiSupplier;

    @BeforeEach
    void setUp() throws Exception {
        Path worldDir = worldsDir.resolve("testworld");
        Path nodesDir = worldDir.resolve("nodes");
        Files.createDirectories(nodesDir);

        // n0000
        String n0000Json = """
            {
              "nodeId": "n0000",
              "parentId": "",
              "turn": 1,
              "worldTime": "元年一月",
              "status": "active",
              "createdAt": "2025-01-01T00:00:00Z",
              "checkpoints": {
                "characters": {
                  "checkpointId": "characters",
                  "label": "角色",
                  "type": "text",
                  "elements": [
                    {
                      "key": "曹操",
                      "type": "text",
                      "value": "魏武帝",
                      "tags": ["Character", "曹魏"],
                      "links": [],
                      "createdAt": "2025-01-01T00:00:00Z",
                      "updatedAt": "2025-01-01T00:00:00Z"
                    }
                  ]
                }
              },
              "attachments": {}
            }
            """;
        Files.writeString(nodesDir.resolve("n0000.json"), n0000Json);

        // active.json
        Files.writeString(worldDir.resolve("active.json"), """
            {"nodeId": "n0000", "sessions": {}}
            """);
        Files.writeString(worldDir.resolve("world.json"), """
            {"id": "testworld", "name": "Test"}
            """);

        wiSupplier = () -> new WorldInformation(
                "testworld",
                List.of(NodeLoader.load(nodesDir.resolve("n0000.json"))));

        toolRegistry = new ToolRegistry();
        // Register the tools query_address needs
        toolRegistry.register(new QueryElementTool(wiSupplier, toolRegistry));
        toolRegistry.register(new QueryByTagTool(wiSupplier));
        toolRegistry.register(new QueryAddressTool(wiSupplier, toolRegistry));
    }

    @Test
    @DisplayName("GSim ref 地址路由到 query_element")
    void routesGsimRefToQueryElement() {
        var tool = new QueryAddressTool(wiSupplier, toolRegistry);
        ToolResult result = tool.execute(new ToolCall("query_address", Map.of(
                "address", "n0000:characters:曹操")));

        assertTrue(result.success(), "Should resolve: " + result.error());
        assertTrue(result.items().get(0).snippet().contains("魏武帝"));
    }

    @Test
    @DisplayName("checkpointId:key 格式默认当前节点")
    void routesShortRefToCurrentNode() {
        var tool = new QueryAddressTool(wiSupplier, toolRegistry);
        ToolResult result = tool.execute(new ToolCall("query_address", Map.of(
                "address", "characters:曹操")));

        assertTrue(result.success(), "Should resolve short ref");
        assertTrue(result.items().get(0).snippet().contains("魏武帝"));
    }

    @Test
    @DisplayName("纯 tag 文本走 byTag 索引")
    void routesPlainTextToByTag() {
        var tool = new QueryAddressTool(wiSupplier, toolRegistry);
        ToolResult result = tool.execute(new ToolCall("query_address", Map.of(
                "address", "曹魏")));

        assertTrue(result.success(), "Should find by tag");
        assertTrue(result.items().get(0).snippet().contains("曹操")
                || result.items().get(0).snippet().contains("魏武帝"));
    }

    @Test
    @DisplayName("gsimap: 前缀需要 worldId")
    void gsimapPrefixRequiresWorldId() {
        var tool = new QueryAddressTool(wiSupplier, toolRegistry);
        ToolResult result = tool.execute(new ToolCall("query_address", Map.of(
                "address", "gsimap:region:蜀")));

        // Should fail because worldId is missing (gsimap tools not registered in this test)
        assertFalse(result.success());
        assertTrue(result.error().contains("worldId"));
    }

    @Test
    @DisplayName("不存在的 tag 返回失败")
    void nonexistentTagFails() {
        var tool = new QueryAddressTool(wiSupplier, toolRegistry);
        ToolResult result = tool.execute(new ToolCall("query_address", Map.of(
                "address", "不存在的标签")));

        assertFalse(result.success());
    }

    @Test
    @DisplayName("缺少 address 参数时报错")
    void failsWithoutAddress() {
        var tool = new QueryAddressTool(wiSupplier, toolRegistry);
        ToolResult result = tool.execute(new ToolCall("query_address", Map.of()));
        assertFalse(result.success());
        assertTrue(result.error().contains("address"));
    }
}
