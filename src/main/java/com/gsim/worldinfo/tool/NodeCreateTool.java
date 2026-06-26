package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.util.IdGenerator;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.NodeLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * node_create — create a new child node (next turn) and switch to it.
 */
public final class NodeCreateTool implements AgentTool {

    private static final Pattern NODE_FILE_PATTERN = Pattern.compile("n(\\d{4})\\.json$");

    private final Supplier<WorldInformation> worldInfo;
    private final Path worldsDir;
    private final Runnable onNodeChanged;

    public NodeCreateTool(Supplier<WorldInformation> worldInfo, Path worldsDir, Runnable onNodeChanged) {
        this.worldInfo = worldInfo;
        this.worldsDir = worldsDir;
        this.onNodeChanged = onNodeChanged;
    }

    @Override
    public String name() { return "node_create"; }

    @Override
    public String description() {
        return "Create a new child node (next turn) on the current active node. " +
               "This advances the timeline. The new node starts with no checkpoints — " +
               "use write_element or create_checkpoint to populate it. " +
               "Automatically switches to the new node. " +
               "Parameters: worldTime (required, e.g. '泰拉纪年1096年冬'), " +
               "title (optional node name), note (optional remark).";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldTime = call.param("worldTime");
        if (worldTime == null || worldTime.isBlank()) {
            return ToolResult.fail("node_create", "worldTime is required (e.g. '泰拉纪年1096年冬')");
        }

        WorldInformation wi = worldInfo.get();
        String parentId = wi.activeNodeId();
        int nextTurn = wi.activeNode().turn() + 1;
        String worldId = wi.worldId();

        // Seed counter from existing nodes before generating new ID
        seedNodeCounterFromDisk(worldId);

        String newNodeId = IdGenerator.nodeId();
        String title = call.param("title");
        String note = call.param("note");
        String label = (title != null && !title.isBlank()) ? title : ("Turn " + nextTurn);

        NodeSnapshot child = new NodeSnapshot(
            newNodeId, parentId, nextTurn, worldTime,
            "active", Instant.now().toString(),
            new LinkedHashMap<>());

        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, newNodeId);
        NodeLoader.save(nodeFile, child);

        // Update active state
        ActiveStateManager.ActiveState currentState = ActiveStateManager.load(worldsDir, worldId);
        Map<String, String> sessions = currentState != null
            ? currentState.sessions() : new LinkedHashMap<>();
        ActiveStateManager.save(worldsDir, worldId,
            new ActiveStateManager.ActiveState(newNodeId, sessions));

        if (onNodeChanged != null) onNodeChanged.run();

        String summary = "Created node " + newNodeId + " (turn " + nextTurn +
            ", parent=" + parentId + ", worldTime=" + worldTime + ")";
        if (note != null && !note.isBlank()) summary += " note=" + note;

        return ToolResult.ok("node_create", List.of(
            new ToolResult.Item(newNodeId, newNodeId, summary, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "worldTime", Map.of("type", "string", "description",
                    "In-world time for the new turn, e.g. '泰拉纪年1096年冬'"),
                "title", Map.of("type", "string", "description",
                    "Optional short title/label for the node"),
                "note", Map.of("type", "string", "description",
                    "Optional remark or context for this node")
            ),
            "required", List.of("worldTime")
        );
    }

    /** Scan nodes/ directory, find max nXXXX, set counter to max+1. */
    private void seedNodeCounterFromDisk(String worldId) {
        Path nodesDir = NodeLoader.nodesDir(worldsDir, worldId);
        if (!Files.isDirectory(nodesDir)) return;

        int max = -1;
        try (Stream<Path> files = Files.list(nodesDir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Matcher m = NODE_FILE_PATTERN.matcher(p.getFileName().toString());
                if (m.find()) {
                    int num = Integer.parseInt(m.group(1));
                    if (num > max) max = num;
                }
            }
        } catch (IOException e) {
            System.err.println("[NodeCreateTool] Failed to scan node directory: " + e.getMessage());
        }
        if (max >= 0) {
            IdGenerator.seedNodeCounter(max + 1);
        }
    }
}
