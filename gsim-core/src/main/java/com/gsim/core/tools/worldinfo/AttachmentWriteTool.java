package com.gsim.core.tools.worldinfo;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.NodeLoader;
import com.gsim.docslib.util.JsonUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * gsim_attachment_write — Write an attachment as an independent file bound to a node.
 *
 * <p>Writes data to {@code nXXXX_{key}.json} via {@link NodeLoader#saveAttachmentFile},
 * with a light reference stored in the node JSON's attachments map.
 * This is the primary mechanism for external modules (like GSimap) to persist
 * module-specific data alongside GSim nodes without polluting the WorldInfo element system.
 */
public final class AttachmentWriteTool implements AgentTool {

    private final Path worldsDir;
    private final Supplier<WorldInformation> worldInfo;

    public AttachmentWriteTool(Path worldsDir, Supplier<WorldInformation> worldInfo) {
        this.worldsDir = worldsDir;
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() {
        return "attachment_write";
    }

    @Override
    public String description() {
        return """
            Write an attachment file bound to a node.
            Data is stored as nXXXX_{key}.json alongside the node JSON.
            Attachments are independent from the WorldInfo element system —
            they are intended for external module data (e.g. GSimap maps).
            Parameters: worldId (required), key (required), data (required, JSON string),
            nodeId (optional, defaults to active node).
            """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = com.gsim.agentsmanager.mcp.GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
        }
        if (worldId == null || worldId.isBlank()) {
            return ToolResult.fail(name(), "worldId is required");
        }

        String key = call.param("key");
        if (key == null || key.isBlank()) {
            return ToolResult.fail(name(), "key is required");
        }

        String dataStr = call.param("data");
        if (dataStr == null || dataStr.isBlank()) {
            return ToolResult.fail(name(), "data is required (JSON string)");
        }

        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            WorldInformation wi = worldInfo.get();
            nodeId = call.param("nodeId");
            if (nodeId == null || nodeId.isBlank())
                return ToolResult.fail(name(), "[NODE_ID_REQUIRED] nodeId is required");
        }

        // Parse data as JSON to validate and normalize
        Object data;
        try {
            data = JsonUtils.MAPPER.readTree(dataStr);
        } catch (Exception e) {
            return ToolResult.fail(name(), "data is not valid JSON: " + e.getMessage());
        }

        NodeLoader.saveAttachmentFile(worldsDir, worldId, nodeId, key, data);

        String ref = nodeId + ":" + key;
        return ToolResult.ok(name(), List.of(new ToolResult.Item(key, ref, "Attachment written: " + ref, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId", Map.of("type", "string", "description", "GSim world ID"),
                                "key",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Attachment key (e.g. 'map', 'contour')"),
                                "data", Map.of("type", "string", "description", "JSON data string to store"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Node ID (optional, defaults to active node)")),
                "required", List.of("worldId", "key", "data"));
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
