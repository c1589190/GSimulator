package com.gsim.worldinfo.manager;

import com.gsim.util.IdGenerator;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.Element;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.NodeLoader;
import com.gsim.worldinfo.loader.WorldInfoBuilder;
import com.gsim.worldinfo.loader.WorldIndexManager;
import com.gsim.worldinfo.loader.WorldIndexManager.WorldEntry;
import com.gsim.worldinfo.loader.WorldIndexManager.WorldMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 世界管理层 — World/Node/Checkpoint/Element 四级层级统一读写。
 *
 * <p>所有写操作验证父级存在性；写 element 要求 checkpoint 已存在。
 */
public class WorldManager {

    private static final Pattern NODE_FILE_PATTERN = Pattern.compile("n(\\d{4})\\.json$");
    private static final int DEFAULT_TRUNCATE = 200;

    private final Path worldsDir;

    public WorldManager(Path worldsDir) {
        this.worldsDir = worldsDir;
    }

    // ────────── Read ──────────

    /** 世界概览 + 节点链。 */
    public Map<String, Object> getWorld(String worldId) {
        WorldMeta meta = requireWorld(worldId);
        ActiveStateManager.ActiveState active = ActiveStateManager.load(worldsDir, worldId);
        WorldInformation wi = active != null
                ? WorldInfoBuilder.build(worldsDir, worldId, active.nodeId()) : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldId", meta.id());
        result.put("name", meta.name());
        result.put("createdAt", meta.createdAt());
        result.put("activeNodeId", active != null ? active.nodeId() : meta.currentNodeId());

        if (wi != null) {
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (NodeSnapshot node : wi.branchChain()) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("nodeId", node.nodeId());
                n.put("parentId", node.parentId());
                n.put("turn", node.turn());
                n.put("worldTime", node.worldTime());
                n.put("status", node.status());
                n.put("isActive", node.nodeId().equals(active.nodeId()));
                n.put("checkpointCount", node.checkpoints().size());
                nodes.add(n);
            }
            result.put("nodeCount", nodes.size());
            result.put("nodes", nodes);
        }
        return result;
    }

    /** 节点详情 + 检查点列表（含每个检查点的元素数量）。 */
    public Map<String, Object> getNode(String worldId, String nodeId) {
        requireWorld(worldId);
        NodeSnapshot node = NodeLoader.load(NodeLoader.nodeFile(worldsDir, worldId, nodeId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", node.nodeId());
        result.put("parentId", node.parentId());
        result.put("turn", node.turn());
        result.put("worldTime", node.worldTime());
        result.put("status", node.status());
        result.put("createdAt", node.createdAt());
        result.put("isRoot", node.isRoot());

        List<Map<String, Object>> cpList = new ArrayList<>();
        for (var entry : node.checkpoints().entrySet()) {
            Map<String, Object> cp = new LinkedHashMap<>();
            cp.put("id", entry.getKey());
            cp.put("label", entry.getValue().label());
            cp.put("type", entry.getValue().type());
            cp.put("elementCount", entry.getValue().elements().size());
            cpList.add(cp);
        }
        result.put("checkpoints", cpList);
        return result;
    }

    /** 检查点下元素列表（截断）。 */
    public Map<String, Object> getCheckpoint(String worldId, String nodeId,
                                              String checkpointId, int truncateLength) {
        requireWorld(worldId);
        NodeSnapshot node = NodeLoader.load(NodeLoader.nodeFile(worldsDir, worldId, nodeId));
        Checkpoint cp = requireCheckpoint(node, checkpointId);

        int truncate = truncateLength > 0 ? truncateLength : DEFAULT_TRUNCATE;
        List<Map<String, Object>> elements = new ArrayList<>();
        for (Element el : cp.elements()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("key", el.key());
            e.put("type", el.type());
            e.put("tags", el.tags());
            e.put("links", el.links());
            e.put("createdAt", el.createdAt());
            e.put("updatedAt", el.updatedAt());
            e.put("fullLength", el.value().length());

            if (el.value().length() > truncate) {
                e.put("value", el.value().substring(0, truncate) + "...");
                e.put("truncated", true);
            } else {
                e.put("value", el.value());
                e.put("truncated", false);
            }
            elements.add(e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("checkpointId", checkpointId);
        result.put("label", cp.label());
        result.put("type", cp.type());
        result.put("truncateLimit", truncate);
        result.put("elementCount", elements.size());
        result.put("elements", elements);
        return result;
    }

    /** 单个元素全文。 */
    public Map<String, Object> getElement(String worldId, String nodeId,
                                           String checkpointId, String key) {
        requireWorld(worldId);
        NodeSnapshot node = NodeLoader.load(NodeLoader.nodeFile(worldsDir, worldId, nodeId));
        Checkpoint cp = requireCheckpoint(node, checkpointId);

        Element found = null;
        for (Element el : cp.elements()) {
            if (el.key().equals(key)) { found = el; break; }
        }
        if (found == null) {
            throw new IllegalArgumentException(
                    "Element not found: " + key + " in " + nodeId + ":" + checkpointId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", found.key());
        result.put("type", found.type());
        result.put("value", found.value());
        result.put("tags", found.tags());
        result.put("links", found.links());
        result.put("createdAt", found.createdAt());
        result.put("updatedAt", found.updatedAt());
        result.put("nodeId", nodeId);
        result.put("checkpointId", checkpointId);
        result.put("turn", node.turn());
        result.put("worldTime", node.worldTime());
        return result;
    }

    // ────────── Write ──────────

    /** 创建世界。 */
    public Map<String, Object> createWorld(String id, String name) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (!id.matches("[a-zA-Z0-9_\\-]+"))
            throw new IllegalArgumentException("id must be alphanumeric/dash/underscore");
        if (Files.exists(worldsDir.resolve(id)))
            throw new IllegalArgumentException("World already exists: " + id);

        if (name == null || name.isBlank()) name = id;
        WorldMeta meta = WorldIndexManager.createWorld(worldsDir, id, name);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldId", meta.id());
        result.put("name", meta.name());
        result.put("createdAt", meta.createdAt());
        result.put("currentNodeId", meta.currentNodeId());
        result.put("action", "created");
        return result;
    }

    /** 删除世界。 */
    public void deleteWorld(String worldId) {
        requireWorld(worldId);
        Path worldPath = worldsDir.resolve(worldId);
        try {
            deleteRecursive(worldPath);
            List<WorldEntry> entries = WorldIndexManager.listWorlds(worldsDir);
            entries = entries.stream().filter(e -> !e.id().equals(worldId)).toList();
            Files.writeString(WorldIndexManager.indexFile(worldsDir),
                    com.gsim.util.JsonUtils.toJson(entries));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete world: " + worldId, e);
        }
    }

    /** 创建子节点。 */
    public Map<String, Object> createNode(String worldId, String parentId,
                                           String worldTime, String title) {
        requireWorld(worldId);
        if (worldTime == null || worldTime.isBlank())
            throw new IllegalArgumentException("worldTime is required");
        if (parentId == null || parentId.isBlank())
            throw new IllegalArgumentException("parentId is required");

        Path parentFile = NodeLoader.nodeFile(worldsDir, worldId, parentId);
        if (!Files.exists(parentFile))
            throw new IllegalArgumentException("Parent node not found: " + parentId);

        NodeSnapshot parent = NodeLoader.load(parentFile);
        int nextTurn = parent.turn() + 1;

        seedNodeCounterFromDisk(worldId);
        String newNodeId = IdGenerator.nodeId();

        NodeSnapshot child = new NodeSnapshot(
                newNodeId, parentId, nextTurn, worldTime,
                "active", Instant.now().toString(), new LinkedHashMap<>());

        NodeLoader.save(NodeLoader.nodeFile(worldsDir, worldId, newNodeId), child);

        ActiveStateManager.ActiveState active = ActiveStateManager.load(worldsDir, worldId);
        Map<String, String> sessions = active != null ? active.sessions() : new LinkedHashMap<>();
        ActiveStateManager.save(worldsDir, worldId,
                new ActiveStateManager.ActiveState(newNodeId, sessions));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", newNodeId);
        result.put("parentId", parentId);
        result.put("turn", nextTurn);
        result.put("worldTime", worldTime);
        if (title != null && !title.isBlank()) result.put("title", title);
        result.put("action", "created");
        return result;
    }

    /** 创建检查点。 */
    public Map<String, Object> createCheckpoint(String worldId, String nodeId,
                                                 String checkpointId, String label, String type) {
        requireWorld(worldId);
        if (checkpointId == null || checkpointId.isBlank())
            throw new IllegalArgumentException("checkpointId is required");

        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(nodeFile))
            throw new IllegalArgumentException("Node not found: " + nodeId);

        NodeSnapshot node = NodeLoader.load(nodeFile);
        if (node.checkpoints().containsKey(checkpointId))
            throw new IllegalArgumentException("Checkpoint already exists: " + checkpointId);

        if (label == null || label.isBlank()) label = checkpointId;
        if (type == null || type.isBlank()) type = "misc";

        node.checkpoints().put(checkpointId, new Checkpoint(label, type, new ArrayList<>()));
        NodeLoader.save(nodeFile, node);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("checkpointId", checkpointId);
        result.put("label", label);
        result.put("type", type);
        result.put("action", "created");
        return result;
    }

    /** 写入/更新元素。 */
    public Map<String, Object> writeElement(String worldId, String nodeId,
                                             String checkpointId, String key,
                                             String value, String type,
                                             List<String> tags, List<String> links) {
        requireWorld(worldId);
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");

        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(nodeFile))
            throw new IllegalArgumentException("Node not found: " + nodeId);

        NodeSnapshot node = NodeLoader.load(nodeFile);
        Checkpoint cp = node.checkpoint(checkpointId);
        if (cp == null)
            throw new IllegalArgumentException(
                    "Checkpoint not found: " + checkpointId
                    + ". Use POST /api/world/" + worldId + "/nodes/" + nodeId + "/" + checkpointId + " to create it first.");

        // Build WorldInformation so upsertElement/appendElement work
        ActiveStateManager.ActiveState active = ActiveStateManager.load(worldsDir, worldId);
        WorldInformation wi = active != null
                ? WorldInfoBuilder.build(worldsDir, worldId, active.nodeId()) : null;
        if (wi == null)
            throw new IllegalStateException("Cannot build world info for: " + worldId);

        String now = Instant.now().toString();
        String createdAt = now;

        // Find existing element to preserve createdAt
        for (Element el : cp.elements()) {
            if (el.key().equals(key) && el.createdAt() != null) {
                createdAt = el.createdAt();
                break;
            }
        }

        Element element = new Element(key,
                type != null ? type : "text",
                value,
                tags != null ? tags : List.of(),
                links != null ? links : List.of(),
                createdAt, now);

        boolean replaced = wi.upsertElement(nodeId, checkpointId, element);
        NodeLoader.save(nodeFile, wi.nodeById(nodeId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ref", nodeId + ":" + checkpointId + ":" + key);
        result.put("key", key);
        result.put("nodeId", nodeId);
        result.put("checkpointId", checkpointId);
        result.put("action", replaced ? "replaced" : "created");
        result.put("type", element.type());
        result.put("createdAt", createdAt);
        result.put("updatedAt", now);
        return result;
    }

    // ────────── helpers ──────────

    private WorldMeta requireWorld(String worldId) {
        WorldMeta meta = WorldIndexManager.loadWorldMeta(worldsDir, worldId);
        if (meta == null) throw new IllegalArgumentException("World not found: " + worldId);
        return meta;
    }

    private static Checkpoint requireCheckpoint(NodeSnapshot node, String checkpointId) {
        Checkpoint cp = node.checkpoint(checkpointId);
        if (cp == null)
            throw new IllegalArgumentException("Checkpoint not found: " + checkpointId + " in node " + node.nodeId());
        return cp;
    }

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
        } catch (IOException ignored) {}
        if (max >= 0) IdGenerator.seedNodeCounter(max + 1);
    }

    private void deleteRecursive(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path child : stream.toList()) deleteRecursive(child);
            }
        }
        Files.delete(dir);
    }
}
