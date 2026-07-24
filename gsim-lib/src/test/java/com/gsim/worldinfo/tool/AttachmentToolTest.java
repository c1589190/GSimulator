package com.gsim.worldinfo.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
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

@DisplayName("Attachment 独立文件读写")
class AttachmentToolTest {

    @TempDir
    Path worldsDir;

    private Path nodeFile;
    private Supplier<WorldInformation> wiSupplier;

    @BeforeEach
    void setUp() throws Exception {
        Path worldDir = worldsDir.resolve("testworld");
        Path nodesDir = worldDir.resolve("nodes");
        Files.createDirectories(nodesDir);

        // Create a minimal node JSON
        String nodeJson =
                """
            {
              "nodeId": "n0000",
              "parentId": "",
              "turn": 1,
              "worldTime": "元年一月",
              "status": "active",
              "createdAt": "2025-01-01T00:00:00Z",
              "checkpoints": {},
              "attachments": {}
            }
            """;
        nodeFile = nodesDir.resolve("n0000.json");
        Files.writeString(nodeFile, nodeJson);

        // Create active.json
        String activeJson = """
            {"nodeId": "n0000", "sessions": {}}
            """;
        Files.writeString(worldDir.resolve("active.json"), activeJson);

        // Create world.json
        Files.writeString(
                worldDir.resolve("world.json"), """
            {"id": "testworld", "name": "Test"}
            """);

        wiSupplier = () -> new WorldInformation("testworld", List.of(NodeLoader.load(nodeFile)));
    }

    @Test
    @DisplayName("AttachmentWriteTool 写入独立文件")
    void writeToolCreatesIndependentFile() {
        var writeTool = new AttachmentWriteTool(worldsDir, wiSupplier);
        ToolCall call = new ToolCall(
                "attachment_write",
                Map.of(
                        "worldId", "testworld",
                        "key", "test_data",
                        "data", "{\"hello\": \"world\"}"));

        ToolResult result = writeTool.execute(call);
        assertTrue(result.success(), "Should succeed: " + result.error());
        assertEquals("test_data", result.items().get(0).title());

        // Verify the independent file exists
        Path attachFile = NodeLoader.attachmentFilePath(worldsDir, "testworld", "n0000", "test_data");
        assertTrue(Files.exists(attachFile), "Independent file should exist: " + attachFile);

        // Verify node JSON has the reference
        var node = NodeLoader.load(nodeFile);
        Object ref = node.attachments().get("test_data");
        assertNotNull(ref, "Node attachments should have the key");
        assertTrue(ref instanceof Map, "Should be a reference map");
        @SuppressWarnings("unchecked")
        var refMap = (Map<String, Object>) ref;
        assertEquals("external", refMap.get("_type"));
    }

    @Test
    @DisplayName("AttachmentReadTool 读取独立文件")
    void readToolLoadsIndependentFile() {
        // Write first
        var writeTool = new AttachmentWriteTool(worldsDir, wiSupplier);
        writeTool.execute(new ToolCall(
                "attachment_write",
                Map.of(
                        "worldId", "testworld",
                        "key", "read_test",
                        "data", "\"some value\"")));

        // Read back
        var readTool = new AttachmentReadTool(worldsDir, wiSupplier);
        ToolResult result = readTool.execute(new ToolCall(
                "attachment_read",
                Map.of(
                        "worldId", "testworld",
                        "key", "read_test")));

        assertTrue(result.success(), "Should succeed: " + result.error());
        assertTrue(result.items().get(0).snippet().contains("some value"));
    }

    @Test
    @DisplayName("权限等级：AttachmentWriteTool = WRITE, AttachmentReadTool = READ")
    void permissionLevels() {
        var writeTool = new AttachmentWriteTool(worldsDir, wiSupplier);
        var readTool = new AttachmentReadTool(worldsDir, wiSupplier);
        assertEquals(Permission.WRITE, writeTool.permission());
        assertEquals(Permission.READ, readTool.permission());
    }

    @Test
    @DisplayName("缺少 worldId 时失败")
    void failsWithoutWorldId() {
        var writeTool = new AttachmentWriteTool(worldsDir, wiSupplier);
        ToolResult result = writeTool.execute(new ToolCall(
                "attachment_write",
                Map.of(
                        "key", "x",
                        "data", "{}")));
        assertFalse(result.success());
        assertTrue(result.error().contains("worldId"));
    }

    @Test
    @DisplayName("NodeLoader.loadAttachmentFile 后向兼容 inline 数据")
    void backwardCompatInlineData() throws Exception {
        // Write inline data the old way
        NodeLoader.saveAttachment(worldsDir, "testworld", "n0000", "inline_key", "inline_value");

        // Read via new method — should fall back to inline
        String value = NodeLoader.loadAttachmentFile(worldsDir, "testworld", "n0000", "inline_key", String.class);
        assertEquals("inline_value", value);
    }
}
