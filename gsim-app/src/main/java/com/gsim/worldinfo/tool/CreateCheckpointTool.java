package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.NodeLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * create_checkpoint — explicitly create a new checkpoint in a node.
 *
 * <p>Unlike write_element which auto-creates checkpoints with default metadata,
 * this tool lets the LLM create a checkpoint with an explicit label and type
 * before writing elements to it.
 *
 * <p>If the checkpoint already exists, returns an error (use write_element to add
 * elements to an existing checkpoint). If the target node does not exist, returns
 * an error with guidance.
 */
public final class CreateCheckpointTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;
    private final Path worldsDir;

    public CreateCheckpointTool(Supplier<WorldInformation> worldInfo, Path worldsDir) {
        this.worldInfo = worldInfo;
        this.worldsDir = worldsDir;
    }

    @Override
    public String name() { return "create_checkpoint"; }

    @Override
    public String description() {
        return "Explicitly create a new checkpoint in a node. " +
               "Parameters: checkpointId (required, e.g. 'characters' or 'player.曹操'), " +
               "label (optional human-readable label), type (optional, default 'misc'), " +
               "nodeId (optional, defaults to current active node). " +
               "Use this when you want to create a checkpoint with specific metadata " +
               "before writing elements into it. " +
               "If the checkpoint already exists, use write_element to add elements.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String checkpointId = call.param("checkpointId");
        if (checkpointId == null || checkpointId.isBlank()) {
            return ToolResult.fail("create_checkpoint",
                "checkpointId is required (e.g. 'characters', 'player.曹操')");
        }

        WorldInformation wi = worldInfo.get();
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = wi.activeNodeId();
        }

        NodeSnapshot node = wi.nodeById(nodeId);
        if (node == null) {
            List<String> available = wi.branchChain().stream()
                .map(n -> n.nodeId() + "[t" + n.turn() + "]").toList();
            return ToolResult.fail("create_checkpoint",
                "Node '" + nodeId + "' not found. Available nodes: " + available + ". " +
                "Use node_list to see all nodes.");
        }

        if (node.checkpoints().containsKey(checkpointId)) {
            return ToolResult.fail("create_checkpoint",
                "Checkpoint '" + checkpointId + "' already exists in node " + nodeId + ". " +
                "Use write_element ref=" + nodeId + ":" + checkpointId + ":<key> to add elements.");
        }

        String label = call.param("label");
        if (label == null || label.isBlank()) label = checkpointId;
        String type = call.param("type");
        if (type == null || type.isBlank()) type = "misc";

        Checkpoint cp = new Checkpoint(label, type, new ArrayList<>());
        node.checkpoints().put(checkpointId, cp);

        // Persist
        Path nodeFile = NodeLoader.nodeFile(worldsDir, wi.worldId(), nodeId);
        NodeLoader.save(nodeFile, node);

        String summary = "Created checkpoint '" + checkpointId + "' (label=" + label +
            ", type=" + type + ") in node " + nodeId + ". " +
            "Now use write_element ref=" + nodeId + ":" + checkpointId + ":<key> to add elements.";

        return ToolResult.ok("create_checkpoint", List.of(
            new ToolResult.Item(checkpointId, nodeId + ":" + checkpointId, summary, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "checkpointId", Map.of("type", "string", "description",
                    "Checkpoint ID, e.g. 'characters', 'factions', 'player.曹操'"),
                "label", Map.of("type", "string", "description",
                    "Human-readable label (defaults to checkpointId if omitted)"),
                "type", Map.of("type", "string", "description",
                    "Checkpoint type: 'misc', 'character', 'faction', 'worldview', " +
                    "'narrative', 'event', etc. (default 'misc')"),
                "nodeId", Map.of("type", "string", "description",
                    "Target node ID. Defaults to the current active node.")
            ),
            "required", List.of("checkpointId")
        );
    }
}
