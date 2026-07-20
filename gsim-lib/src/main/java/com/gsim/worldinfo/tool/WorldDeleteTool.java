package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.loader.WorldIndexManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * gsim_delete_world -- Delete a GSim world.
 *
 * <p>Recursively removes the world directory and updates _index.json.
 * Cannot be undone.
 */
public final class WorldDeleteTool implements AgentTool {

    private final Path worldsDir;

    public WorldDeleteTool(Path worldsDir) {
        this.worldsDir = worldsDir;
    }

    @Override
    public String name() {
        return "gsim_delete_world";
    }

    @Override
    public String description() {
        return """
            Delete a GSim world. Recursively removes the world directory
            and updates _index.json. Cannot be undone.
            Parameters: worldId (required) -- the world to delete.
            """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("worldId", Map.of("type", "string", "description", "World ID to delete")),
                "required", List.of("worldId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = call.param("worldId", "").trim();
        if (worldId.isEmpty()) {
            return ToolResult.fail(name(), "worldId is required");
        }

        Path worldDir = worldsDir.resolve(worldId);
        if (!Files.isDirectory(worldDir) || !Files.exists(worldDir.resolve("world.json"))) {
            return ToolResult.fail(name(), "World not found: " + worldId);
        }

        try {
            deleteRecursive(worldDir);

            List<WorldIndexManager.WorldEntry> entries = WorldIndexManager.listWorlds(worldsDir);
            entries = entries.stream().filter(e -> !e.id().equals(worldId)).toList();
            Files.writeString(WorldIndexManager.indexFile(worldsDir), com.gsim.util.JsonUtils.toJson(entries));

            return ToolResult.ok(
                    name(), List.of(new ToolResult.Item(worldId, worldId, "World deleted: " + worldId, 1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "Failed to delete world: " + e.getMessage());
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path child : stream.toList()) {
                    deleteRecursive(child);
                }
            }
        }
        Files.delete(dir);
    }
}
