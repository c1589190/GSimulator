package com.gsim.worldinfo.tool;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.NodeLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * create_checkpoint -- 在节点中显式创建新检查点。
 *
 * <p>与 {@link WriteElementTool} 自动创建检查点不同，此工具允许 LLM 在写入元素之前
 * 先创建一个带有自定义 label 和 type 的检查点，提供更精确的元数据控制。
 *
 * <p>如果检查点已存在，返回错误（提示使用 write_element 向已有检查点添加元素）。
 * 如果目标节点不存在，返回错误并附带可用节点列表引导。
 */
public final class CreateCheckpointTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(CreateCheckpointTool.class);

    private final Supplier<WorldInformation> worldInfo;
    private final Path worldsDir;

    public CreateCheckpointTool(Supplier<WorldInformation> worldInfo, Path worldsDir) {
        this.worldInfo = worldInfo;
        this.worldsDir = worldsDir;
    }

    @Override
    public String name() {
        return "create_checkpoint";
    }

    @Override
    public String description() {
        return "Explicitly create a new checkpoint in a node. "
                + "Parameters: checkpointId (required, e.g. 'characters' or 'player.曹操'), "
                + "label (optional human-readable label), type (optional, default 'misc'), "
                + "nodeId (optional, defaults to current active node). "
                + "Use this when you want to create a checkpoint with specific metadata "
                + "before writing elements into it. "
                + "If the checkpoint already exists, use write_element to add elements.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String checkpointId = call.param("checkpointId");
        if (checkpointId == null || checkpointId.isBlank()) {
            return ToolResult.fail("create_checkpoint", "checkpointId is required (e.g. 'characters', 'player.曹操')");
        }

        WorldInformation wi = worldInfo.get();
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = call.param("nodeId");
            if (nodeId == null || nodeId.isBlank())
                return ToolResult.fail(name(), "[NODE_ID_REQUIRED] nodeId is required");
        }

        // Lazily load node from disk if not in the in-memory chain
        lazyLoadNode(wi, nodeId);

        NodeSnapshot node = wi.nodeById(nodeId);
        if (node == null) {
            List<String> available = wi.branchChain().stream()
                    .map(n -> n.nodeId() + "[t" + n.turn() + "]")
                    .toList();
            return ToolResult.fail(
                    "create_checkpoint",
                    "Node '" + nodeId + "' not found. Available nodes: " + available + ". "
                            + "Use node_list to see all nodes.");
        }

        if (node.checkpoints().containsKey(checkpointId)) {
            return ToolResult.fail(
                    "create_checkpoint",
                    "Checkpoint '" + checkpointId + "' already exists in node " + nodeId + ". "
                            + "Use write_element ref=" + nodeId + ":" + checkpointId + ":<key> to add elements.");
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

        String summary = "Created checkpoint '" + checkpointId + "' (label=" + label + ", type="
                + type + ") in node " + nodeId + ". " + "Now use write_element ref="
                + nodeId + ":" + checkpointId + ":<key> to add elements.";

        return ToolResult.ok(
                "create_checkpoint",
                List.of(new ToolResult.Item(checkpointId, nodeId + ":" + checkpointId, summary, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "checkpointId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Checkpoint ID, e.g. 'characters', 'factions', 'player.曹操'"),
                                "label",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Human-readable label (defaults to checkpointId if omitted)"),
                                "type",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Checkpoint type: 'misc', 'character', 'faction', 'worldview', "
                                                        + "'narrative', 'event', etc. (default 'misc')"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Target node ID. Defaults to the current active node.")),
                "required", List.of("checkpointId"));
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }

    private void lazyLoadNode(WorldInformation wi, String nodeId) {
        if (wi.nodeById(nodeId) != null) return;
        Path nodeFile = NodeLoader.nodeFile(worldsDir, wi.worldId(), nodeId);
        if (!Files.exists(nodeFile)) return; // let caller handle the error
        try {
            NodeSnapshot node = NodeLoader.load(nodeFile);
            wi.ensureNode(node);
            log.info("Lazy-loaded node {} from disk into WorldInformation", nodeId);
        } catch (RuntimeException e) {
            log.warn("Failed to lazy-load node {} from disk: {}", nodeId, e.getMessage());
        }
    }
}
