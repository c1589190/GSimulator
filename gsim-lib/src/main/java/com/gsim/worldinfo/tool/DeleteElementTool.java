package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.NodeLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * gsim_delete_checkpoint_element -- Delete an element from a GSim checkpoint by key.
 *
 * <p>Reads the node JSON, finds the element by key within the specified checkpoint,
 * removes it, and saves the node back. The operating world is determined via
 * ActiveStateManager when no explicit worldId is given.
 */
public final class DeleteElementTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;
    private final Path worldsDir;

    public DeleteElementTool(Supplier<WorldInformation> worldInfo, Path worldsDir) {
        this.worldInfo = worldInfo;
        this.worldsDir = worldsDir;
    }

    @Override
    public String name() {
        return "gsim_delete_checkpoint_element";
    }

    @Override
    public String description() {
        return """
            Delete an element from a GSim checkpoint by key.
            Parameters:
              worldId (optional) -- world ID (defaults to active world)
              nodeId (optional) -- node ID (defaults to "n0000")
              checkpoint (required) -- checkpoint name
              key (required) -- element key to delete
            """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "World ID (optional, defaults to active world)"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Node ID (optional, defaults to n0000)"),
                                "checkpoint", Map.of("type", "string", "description", "Checkpoint name"),
                                "key", Map.of("type", "string", "description", "Element key to delete")),
                "required", List.of("checkpoint", "key"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        // Resolve worldId
        WorldInformation wi = worldInfo.get();
        String worldId = call.param("worldId");
        if (worldId == null || worldId.isBlank()) {
            worldId = wi != null ? wi.worldId() : resolveActiveWorldId();
        }
        if (worldId == null || worldId.isBlank()) {
            return ToolResult.fail(name(), "worldId is required and no active world is set");
        }

        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = (wi != null && wi.activeNodeId() != null) ? wi.activeNodeId() : "n0000";
        }

        String checkpointName = call.param("checkpoint");
        String key = call.param("key");
        if (checkpointName == null || checkpointName.isBlank()) {
            return ToolResult.fail(name(), "checkpoint is required");
        }
        if (key == null || key.isBlank()) {
            return ToolResult.fail(name(), "key is required");
        }

        try {
            Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, nodeId);
            if (!Files.exists(nodeFile)) {
                return ToolResult.fail(name(), "Node not found: " + worldId + "/" + nodeId);
            }

            // Read the node and manipulate checkpoints directly via JSON
            var mapper = com.gsim.util.JsonUtils.MAPPER;
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(nodeFile.toFile());
            var cps = (com.fasterxml.jackson.databind.node.ObjectNode) node.get("checkpoints");
            if (cps == null || !cps.has(checkpointName)) {
                return ToolResult.fail(name(), "Checkpoint not found: " + checkpointName);
            }

            var elements = (com.fasterxml.jackson.databind.node.ArrayNode)
                    cps.get(checkpointName).get("elements");
            if (elements == null) {
                return ToolResult.fail(name(), "No elements in checkpoint: " + checkpointName);
            }

            int idx = -1;
            for (int i = 0; i < elements.size(); i++) {
                if (elements.get(i).has("key")
                        && elements.get(i).get("key").asText().equals(key)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return ToolResult.fail(name(), "Element not found: " + key);
            }

            elements.remove(idx);

            // Write back
            mapper.writerWithDefaultPrettyPrinter().writeValue(nodeFile.toFile(), node);

            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            key,
                            worldId + "/" + nodeId + "/" + checkpointName + "/" + key,
                            "Element deleted: " + key,
                            1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "Failed to delete element: " + e.getMessage());
        }
    }

    /**
     * Fallback: try to read the active world ID from the currently known file structure.
     */
    private String resolveActiveWorldId() {
        try {
            Path activeFile = worldsDir.resolve("active.json");
            if (Files.exists(activeFile)) {
                var mapper = com.gsim.util.JsonUtils.MAPPER;
                var active = mapper.readTree(activeFile.toFile());
                if (active.has("worldId") && !active.get("worldId").isNull()) {
                    return active.get("worldId").asText();
                }
            }
            // Try reading active.json from each world directory
            if (Files.isDirectory(worldsDir)) {
                try (var dirs = Files.list(worldsDir)) {
                    for (Path dir : (Iterable<Path>) dirs::iterator) {
                        if (!Files.isDirectory(dir)) continue;
                        Path active = dir.resolve("active.json");
                        if (Files.exists(active)) {
                            var mapper = com.gsim.util.JsonUtils.MAPPER;
                            var an = mapper.readTree(active.toFile());
                            if (an.has("nodeId") && !an.get("nodeId").isNull()) {
                                return dir.getFileName().toString();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
