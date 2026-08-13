package com.gsim.worldinfo.tool;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.NodeLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * gsim_attachment_read — Read an attachment from a node's independent file.
 *
 * <p>Reads data from {@code nXXXX_{key}.json} via {@link NodeLoader#loadAttachmentFile}.
 * Supports backward-compatible fallback to inline data for attachments created
 * before the independent file mechanism was introduced.
 */
public final class AttachmentReadTool implements AgentTool {

    private final Path worldsDir;
    private final Supplier<WorldInformation> worldInfo;

    public AttachmentReadTool(Path worldsDir, Supplier<WorldInformation> worldInfo) {
        this.worldsDir = worldsDir;
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() {
        return "attachment_read";
    }

    @Override
    public String description() {
        return """
            Read an attachment file bound to a node.
            Reads from nXXXX_{key}.json, with backward-compatible fallback
            to inline data for older attachments.
            Parameters: worldId (required), key (required),
            nodeId (optional, defaults to active node).
            """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = com.gsim.agentlib.mcp.GsimRequestContext.worldId();
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

        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            WorldInformation wi = worldInfo.get();
            nodeId = call.param("nodeId");
            if (nodeId == null || nodeId.isBlank())
                return ToolResult.fail(name(), "[NODE_ID_REQUIRED] nodeId is required");
        }

        // Load as generic Object — we don't know the shape
        Object data = NodeLoader.loadAttachmentFile(worldsDir, worldId, nodeId, key, Object.class);
        if (data == null) {
            return ToolResult.fail(name(), "Attachment not found: " + nodeId + ":" + key);
        }

        String snippet = JsonUtils.toJson(data);
        String ref = nodeId + ":" + key;
        return ToolResult.ok(name(), List.of(new ToolResult.Item(key, ref, snippet, 1.0)));
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
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Node ID (optional, defaults to active node)")),
                "required", List.of("worldId", "key"));
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
