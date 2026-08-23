package com.gsim.agent.tools.worldinfo;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.util.IdGenerator;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.NodeLoader;
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
 * node_create -- 创建新的子节点（下一回合）并自动切换到该节点。
 *
 * <p>此工具是推进时间线的核心手段。新节点从当前活跃节点派生，回合号自动加 1，
 * 节点 ID 由 {@link com.gsim.core.util.IdGenerator} 自动生成（格式 nXXXX）。
 * 创建后自动更新 active.json 将新节点设为活跃节点。
 *
 * <p>新节点初始没有检查点和元素，需要使用
 * {@link CreateCheckpointTool} 或 {@link WriteElementTool} 填充内容。
 *
 * <p>必填参数 worldTime 描述世界内时间（如"泰拉纪年1096年冬"），
 * 可选参数 title 作为节点标签，note 作为附加备注。
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
    public String name() {
        return "node_create";
    }

    @Override
    public String description() {
        return "Create a new child node (next turn) on the current active node. "
                + "This advances the timeline. The new node starts with no checkpoints — "
                + "use write_element or create_checkpoint to populate it. "
                + "Returns new node ID. "
                + "Parameters: worldId (required), parentNodeId (required), "
                + "worldTime (required, e.g. '泰拉纪年1096年冬'), "
                + "title (optional node name), note (optional remark).";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldTime = call.param("worldTime");
        if (worldTime == null || worldTime.isBlank()) {
            return ToolResult.fail("node_create", "worldTime is required (e.g. '泰拉纪年1096年冬')");
        }

        WorldInformation wi = worldInfo.get();
        String worldId = call.param("worldId");
        if (worldId == null || worldId.isBlank()) {
            return ToolResult.fail("node_create", "[WORLD_ID_REQUIRED] worldId is required");
        }
        String parentId = call.param("parentNodeId");
        if (parentId == null || parentId.isBlank()) {
            return ToolResult.fail("node_create", "[NODE_ID_REQUIRED] parentNodeId is required");
        }
        // 从磁盘加载父节点（而非依赖内存 WorldInformation，后者可能属于错误的 world）
        Path parentFile = NodeLoader.nodeFile(worldsDir, worldId, parentId);
        if (!Files.exists(parentFile)) {
            return ToolResult.fail(
                    "node_create",
                    "[PARENT_NOT_FOUND] parent node not found: " + parentId + " (file: " + parentFile + ")");
        }
        NodeSnapshot parentNode;
        try {
            parentNode = NodeLoader.load(parentFile);
        } catch (RuntimeException e) {
            return ToolResult.fail(
                    "node_create",
                    "[PARENT_LOAD_FAILED] failed to load parent node " + parentId + ": " + e.getMessage());
        }
        int nextTurn = parentNode.turn() + 1;

        // Seed counter from existing nodes before generating new ID
        seedNodeCounterFromDisk(worldId);

        String newNodeId = IdGenerator.nodeId();
        String title = call.param("title");
        String note = call.param("note");
        String label = (title != null && !title.isBlank()) ? title : ("Turn " + nextTurn);

        NodeSnapshot child = new NodeSnapshot(
                newNodeId,
                parentId,
                nextTurn,
                worldTime,
                "active",
                Instant.now().toString(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>());

        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, newNodeId);
        NodeLoader.save(nodeFile, child);

        // 将新节点加入内存 WorldInformation，使其立即可被 nodeById / branchChain 查询
        wi.ensureNode(child);

        if (onNodeChanged != null) onNodeChanged.run();

        String summary = "Created node " + newNodeId + " (turn " + nextTurn + ", parent=" + parentId + ", worldTime="
                + worldTime + ")";
        if (note != null && !note.isBlank()) summary += " note=" + note;

        return ToolResult.ok("node_create", List.of(new ToolResult.Item(newNodeId, newNodeId, summary, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldTime",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "In-world time for the new turn, e.g. '泰拉纪年1096年冬'"),
                                "parentNodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Parent node ID the new node is created from (e.g. 'n0000'). Required."),
                                "title",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Optional short title/label for the node"),
                                "note",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Optional remark or context for this node")),
                "required", List.of("worldTime", "parentNodeId"));
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

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
