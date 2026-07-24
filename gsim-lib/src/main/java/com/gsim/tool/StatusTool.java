package com.gsim.tool;

import com.gsim.tool.AgentTool.Permission;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * gsim_get_status -- Get MCP server status: version, directories, world count, tool count.
 *
 * <p>Provides a summary of the GSimulator-MCP server state including
 * data directory locations and counts of worlds and tools.
 */
public final class StatusTool implements AgentTool {

    private final Path worldsDir;
    private final Supplier<ToolRegistry> toolRegistrySupplier;

    /**
     * @param worldsDir            worlds directory path
     * @param toolRegistrySupplier supplier that provides the current ToolRegistry at query time
     */
    public StatusTool(Path worldsDir, Supplier<ToolRegistry> toolRegistrySupplier) {
        this.worldsDir = worldsDir;
        this.toolRegistrySupplier = toolRegistrySupplier;
    }

    /** Backward-compatible constructor. */
    public StatusTool(Path worldsDir, int toolCount) {
        this.worldsDir = worldsDir;
        this.toolRegistrySupplier = () -> null;
    }

    @Override
    public String name() {
        return "get_status";
    }

    @Override
    public String description() {
        return "Get MCP server status: version, directories, world count, tool count.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        int worldCount = 0;
        try {
            java.io.File[] dirs = worldsDir.toFile().listFiles(java.io.File::isDirectory);
            worldCount = dirs != null ? dirs.length : 0;
        } catch (Exception ignored) {
        }

        // Dynamic tool count — queries the registry at execution time
        int dynamicToolCount = 0;
        ToolRegistry reg = toolRegistrySupplier.get();
        if (reg != null) {
            dynamicToolCount = reg.all().size();
        }

        String dataDir = worldsDir.getParent() != null
                ? worldsDir.getParent().resolve("data").toString()
                : "data";

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("server", "GSimulator-MCP");
        status.put("version", "0.1.0");
        status.put("worldsDir", worldsDir.toString());
        status.put("dataDir", dataDir);
        status.put("worldCount", worldCount);
        status.put("toolCount", dynamicToolCount);

        StringBuilder sb = new StringBuilder();
        sb.append("GSimulator-MCP v0.1.0\n");
        sb.append("Worlds directory: ").append(worldsDir).append("\n");
        sb.append("Data directory: ").append(dataDir).append("\n");
        sb.append("World count: ").append(worldCount).append("\n");
        sb.append("Tool count: ").append(dynamicToolCount).append("\n");

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "Server Status", "status", sb.toString().trim(), 1.0)));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
