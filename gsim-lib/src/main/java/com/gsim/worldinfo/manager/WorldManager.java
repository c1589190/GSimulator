package com.gsim.worldinfo.manager;

import com.gsim.doc.DocStore;
import com.gsim.doc.Document;
import com.gsim.util.IdGenerator;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.Element;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.NodeLoader;
import com.gsim.worldinfo.loader.WorldIndexManager;
import com.gsim.worldinfo.loader.WorldIndexManager.WorldMeta;
import com.gsim.worldinfo.loader.WorldInfoBuilder;
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
    private DocStore docStore;

    /**
     * 创建 WorldManager 实例。
     *
     * @param worldsDir worlds 根目录路径
     */
    public WorldManager(Path worldsDir) {
        this.worldsDir = worldsDir;
    }

    /** 引用解析：route_to_doc → 注入 renderedContent；其他 @doc:/@cache: → 注入 renderedSummary。 */
    private void injectRenderedContent(Map<String, Object> elementMap, Element el) {
        String val = el.value();
        if (val == null) return;

        if ("route_to_doc".equals(el.type()) && val.startsWith("@doc:")) {
            String docId = val.substring(5).trim();
            if (!docId.isEmpty()) {
                Document doc = getDocStore().get(docId);
                if (doc != null) {
                    elementMap.put("renderedContent", doc.content());
                    elementMap.put("renderedTitle", doc.title());
                }
            }
            return;
        }

        // 非 route_to_doc 但 value 以 @doc: 或 @cache: 开头 → 注入摘要
        if (val.startsWith("@doc:")) {
            String docId = val.substring(5).trim().split("[\\s,;。\\n]", 2)[0];
            if (!docId.isEmpty()) {
                Document doc = getDocStore().get(docId);
                if (doc != null) {
                    elementMap.put("renderedTitle", doc.title());
                    elementMap.put("renderedSummary", doc.summary());
                }
            }
        } else if (val.startsWith("@cache:")) {
            String cacheId = val.substring(7).trim().split("[\\s,;。\\n]", 2)[0];
            if (!cacheId.isEmpty()) {
                var cm = getCacheManager();
                String cached = cm.get(cacheId);
                if (cached != null && !cached.isBlank()) {
                    String s = cached.length() > 100 ? cached.substring(0, 97) + "..." : cached;
                    elementMap.put("renderedSummary", s);
                }
            }
        }
    }

    // ────────── Read ──────────

    /**
     * 获取世界概览信息，包含世界元数据和节点链摘要。
     *
     * @param worldId 世界 ID
     * @return 包含 worldId、name、createdAt、activeNodeId、nodeCount 和节点列表的 Map
     * @throws IllegalArgumentException 世界不存在时抛出
     */
    public Map<String, Object> getWorld(String worldId) {
        WorldMeta meta = requireWorld(worldId);
        ActiveStateManager.ActiveState active = ActiveStateManager.load(worldsDir, worldId);
        WorldInformation wi = active != null ? WorldInfoBuilder.build(worldsDir, worldId, "n0000") : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldId", meta.id());
        result.put("name", meta.name());
        result.put("createdAt", meta.createdAt());
        result.put("activeNodeId", active != null ? "n0000" : meta.currentNodeId());

        if (wi != null) {
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (NodeSnapshot node : wi.branchChain()) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("nodeId", node.nodeId());
                n.put("parentId", node.parentId());
                n.put("turn", node.turn());
                n.put("worldTime", node.worldTime());
                n.put("status", node.status());
                n.put("isActive", node.nodeId().equals("n0000"));
                n.put("checkpointCount", node.checkpoints().size());
                nodes.add(n);
            }
            result.put("nodeCount", nodes.size());
            result.put("nodes", nodes);
        }
        return result;
    }

    /**
     * 获取节点详情，包括节点元数据和检查点列表。
     * 每个检查点条目包含 label、type 和元素数量。
     *
     * @param worldId 世界 ID
     * @param nodeId  节点 ID
     * @return 包含节点元数据（nodeId、parentId、turn、worldTime、status 等）和检查点列表的 Map
     * @throws IllegalArgumentException 世界或节点不存在时抛出
     */
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

    /**
     * 获取检查点下的元素列表，支持截断长文本值。
     *
     * <p>每个元素条目包含 key、type、tags、links、时间戳、fullLength。
     * 当元素值长度超过 truncateLength 时，只返回截断后的前缀并标记 truncated=true。
     * {@code @doc:} 和 {@code @cache:} 引用会自动注入 renderedContent/renderedSummary。
     *
     * @param worldId        世界 ID
     * @param nodeId         节点 ID
     * @param checkpointId   检查点 ID
     * @param truncateLength 截断长度；小于等于 0 时使用默认值 200
     * @return 包含 nodeId、checkpointId、label、type、元素列表等的 Map
     * @throws IllegalArgumentException 世界或节点或检查点不存在时抛出
     */
    public Map<String, Object> getCheckpoint(String worldId, String nodeId, String checkpointId, int truncateLength) {
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
            injectRenderedContent(e, el);
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

    /**
     * 获取单个元素的完整内容，包括 value 全文、tags、links 和时间戳。
     *
     * <p>{@code @doc:} 和 {@code @cache:} 引用会自动注入 renderedContent/renderedSummary。
     *
     * @param worldId      世界 ID
     * @param nodeId       节点 ID
     * @param checkpointId 检查点 ID
     * @param key          元素键名
     * @return 包含元素完整信息的 Map（value、type、tags、links、createdAt、updatedAt、nodeId、checkpointId、turn、worldTime）
     * @throws IllegalArgumentException 世界或节点或检查点不存在，或元素键名不存在时抛出
     */
    public Map<String, Object> getElement(String worldId, String nodeId, String checkpointId, String key) {
        requireWorld(worldId);
        NodeSnapshot node = NodeLoader.load(NodeLoader.nodeFile(worldsDir, worldId, nodeId));
        Checkpoint cp = requireCheckpoint(node, checkpointId);

        Element found = null;
        for (Element el : cp.elements()) {
            if (el.key().equals(key)) {
                found = el;
                break;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Element not found: " + key + " in " + nodeId + ":" + checkpointId);
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
        injectRenderedContent(result, found);
        return result;
    }

    // ────────── Write ──────────

    /**
     * 创建新世界。生成根节点 n0000（含 worldview 和 narrative 检查点）、world.json、active.json。
     *
     * @param id   世界 ID（仅字母、数字、连字符、下划线）
     * @param name 显示名称（若为 null 或空白则默认等于 id）
     * @return 包含 worldId、name、createdAt、currentNodeId 的 Map
     * @throws IllegalArgumentException ID 非法、已存在或名称为空时抛出
     */
    public Map<String, Object> createWorld(String id, String name) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (!id.matches("[a-zA-Z0-9_\\-]+"))
            throw new IllegalArgumentException("id must be alphanumeric/dash/underscore");
        if (Files.exists(worldsDir.resolve(id))) throw new IllegalArgumentException("World already exists: " + id);

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

    /**
     * 删除指定世界及其所有数据文件。
     *
     * <p>递归删除 world 目录下的所有文件，并从 _index.json 中移除条目。
     *
     * @param worldId 世界 ID
     * @throws IllegalArgumentException 世界不存在时抛出
     * @throws RuntimeException         文件操作失败时抛出
     */
    public void deleteWorld(String worldId) {
        requireWorld(worldId);
        Path worldPath = worldsDir.resolve(worldId);
        try {
            deleteRecursive(worldPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete world: " + worldId, e);
        }
    }

    /**
     * 创建子节点并自动切换为活跃节点。
     *
     * <p>子节点会继承父节点的轮次号加 1，节点 ID 由 {@link com.gsim.util.IdGenerator} 自动生成。
     * 创建后主动更新 active.json 将新节点设为活跃节点。
     *
     * @param worldId  世界 ID
     * @param parentId 父节点 ID
     * @param worldTime 世界内时间描述（如"泰拉纪年1096年冬"）
     * @param title    可选的节点标题
     * @return 包含 nodeId、parentId、turn、worldTime、title 的 Map
     * @throws IllegalArgumentException 世界不存在、父节点不存在或 worldTime 为空时抛出
     */
    public Map<String, Object> createNode(String worldId, String parentId, String worldTime, String title) {
        requireWorld(worldId);
        if (worldTime == null || worldTime.isBlank()) throw new IllegalArgumentException("worldTime is required");
        if (parentId == null || parentId.isBlank()) throw new IllegalArgumentException("parentId is required");

        Path parentFile = NodeLoader.nodeFile(worldsDir, worldId, parentId);
        if (!Files.exists(parentFile)) throw new IllegalArgumentException("Parent node not found: " + parentId);

        NodeSnapshot parent = NodeLoader.load(parentFile);
        int nextTurn = parent.turn() + 1;

        seedNodeCounterFromDisk(worldId);
        String newNodeId = IdGenerator.nodeId();

        NodeSnapshot child = new NodeSnapshot(
                newNodeId,
                parentId,
                nextTurn,
                worldTime,
                "active",
                Instant.now().toString(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>());

        NodeLoader.save(NodeLoader.nodeFile(worldsDir, worldId, newNodeId), child);

        ActiveStateManager.ActiveState active = ActiveStateManager.load(worldsDir, worldId);
        Map<String, String> sessions = active != null ? active.sessions() : new LinkedHashMap<>();
        ActiveStateManager.save(worldsDir, worldId, new ActiveStateManager.ActiveState(sessions));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", newNodeId);
        result.put("parentId", parentId);
        result.put("turn", nextTurn);
        result.put("worldTime", worldTime);
        if (title != null && !title.isBlank()) result.put("title", title);
        result.put("action", "created");
        return result;
    }

    /**
     * 在指定节点中创建新检查点。
     *
     * <p>检查点提供额外的元数据（label、type），比 {@link #writeElement} 的自动创建更可控。
     * 如果检查点已存在，抛出异常。
     *
     * @param worldId      世界 ID
     * @param nodeId       节点 ID
     * @param checkpointId 检查点 ID（如 "characters"、"player.曹操"）
     * @param label        可读标签（若为 null 或空白则默认等于 checkpointId）
     * @param type         检查点类型（如 "character"、"faction"、"worldview"；若为 null 或空白则默认 "misc"）
     * @return 包含 nodeId、checkpointId、label、type 的 Map
     * @throws IllegalArgumentException 世界不存在、节点不存在或检查点已存在时抛出
     */
    public Map<String, Object> createCheckpoint(
            String worldId, String nodeId, String checkpointId, String label, String type) {
        requireWorld(worldId);
        if (checkpointId == null || checkpointId.isBlank())
            throw new IllegalArgumentException("checkpointId is required");

        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(nodeFile)) throw new IllegalArgumentException("Node not found: " + nodeId);

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

    /**
     * 写入或更新指定节点检查点中的元素。
     *
     * <p>如果检查点不存在，会自动创建（type 默认 "misc"）。在 replace 模式下，
     * 如果 key 已存在则更新值并保留原始 createdAt；否则创建新元素。
     *
     * <p>当 autoDoc 为 true 且 value 不以 {@code @doc:} 或 {@code @cache:} 开头时，
     * 会自动创建 Doc 文档并将 value 改写为 {@code @doc:} 引用，type 设为 "route_to_doc"。
     * 长文本（超过 200 字符）会自动缓存并返回 cacheRef。
     *
     * @param worldId      世界 ID
     * @param nodeId       节点 ID
     * @param checkpointId 检查点 ID
     * @param key          元素键名
     * @param value        元素值
     * @param type         元素类型（如 "text"、"action"、"effect"，默认 "text"）
     * @param tags         标签列表
     * @param links        交叉引用列表，格式 {@code nodeId:checkpointId:key}
     * @param autoDoc      是否自动创建 Doc 并转换为引用
     * @return 包含 ref、key、nodeId、checkpointId、action、type、时间戳的 Map；autoDoc 时包含 docRef，长文本时包含 cacheRef
     * @throws IllegalArgumentException 世界不存在、节点不存在或 key/value 为空时抛出
     */
    public Map<String, Object> writeElement(
            String worldId,
            String nodeId,
            String checkpointId,
            String key,
            String value,
            String type,
            List<String> tags,
            List<String> links,
            boolean autoDoc) {
        requireWorld(worldId);
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");

        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(nodeFile)) throw new IllegalArgumentException("Node not found: " + nodeId);

        NodeSnapshot node = NodeLoader.load(nodeFile);
        Checkpoint cp = node.checkpoint(checkpointId);

        // 自动创建缺失的 checkpoint
        if (cp == null) {
            NodeSnapshot newNode = NodeLoader.load(nodeFile); // re-read to avoid stale
            newNode.checkpoints()
                    .put(checkpointId, new Checkpoint(checkpointId, type != null ? type : "misc", new ArrayList<>()));
            NodeLoader.save(nodeFile, newNode);
            cp = newNode.checkpoint(checkpointId);
            System.err.println(
                    "[AUTO-CP] node=" + nodeId + " checkpoint=" + checkpointId + " → auto-created before writeElement");
        }

        // autoDoc：value 非 @doc: 开头 → 自动创建 Doc 并改写
        String docRef = null;
        if (autoDoc && !value.startsWith("@doc:") && !value.startsWith("@cache:")) {
            var ds = getDocStore();
            String docId = sanitizeDocId(key);
            try {
                if (ds.get(docId) == null) {
                    ds.create(docId, com.gsim.doc.DocType.OTHER, key, value, java.util.List.of("auto"));
                } else {
                    ds.updateContent(docId, value);
                }
                docRef = "@doc:" + docId;
                value = docRef;
                type = "route_to_doc";
            } catch (java.io.IOException ignored) {
            }
        }

        // Build WorldInformation so upsertElement/appendElement work
        ActiveStateManager.ActiveState active = ActiveStateManager.load(worldsDir, worldId);
        WorldInformation wi = active != null ? WorldInfoBuilder.build(worldsDir, worldId, "n0000") : null;
        if (wi == null) throw new IllegalStateException("Cannot build world info for: " + worldId);

        String now = Instant.now().toString();
        String createdAt = now;

        for (Element el : cp.elements()) {
            if (el.key().equals(key) && el.createdAt() != null) {
                createdAt = el.createdAt();
                break;
            }
        }

        Element element = new Element(
                key,
                type != null ? type : "text",
                value,
                tags != null ? tags : List.of(),
                links != null ? links : List.of(),
                createdAt,
                now);

        boolean replaced = wi.upsertElement(nodeId, checkpointId, element);
        NodeLoader.save(nodeFile, wi.nodeById(nodeId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ref", "@world:" + nodeId + ":" + checkpointId + ":" + key);
        result.put("key", key);
        result.put("nodeId", nodeId);
        result.put("checkpointId", checkpointId);
        result.put("action", replaced ? "replaced" : "created");
        result.put("type", element.type());
        result.put("createdAt", createdAt);
        result.put("updatedAt", now);

        // autoDoc 时返回 docRef
        if (docRef != null) result.put("docRef", docRef);

        // 长文本自动缓存
        if (value != null && value.length() > 200) {
            var cm = getCacheManager();
            try {
                String cacheId = cm.put("write", value);
                result.put("cacheRef", "@cache:" + cacheId);
            } catch (java.io.IOException ignored) {
            }
        }

        return result;
    }

    // ── 懒初始化辅助 ──

    private DocStore getDocStore() {
        if (docStore == null) {
            Path docsDir = worldsDir.resolveSibling("docs");
            docStore = new DocStore(docsDir);
            try {
                docStore.init();
            } catch (java.io.IOException ignored) {
            }
        }
        return docStore;
    }

    private com.gsim.doc.DocCacheManager getCacheManager() {
        Path cacheDir = worldsDir.resolveSibling("docs").resolve(".cache");
        var cm = new com.gsim.doc.DocCacheManager(cacheDir);
        try {
            cm.init();
        } catch (java.io.IOException ignored) {
        }
        return cm;
    }

    private static String sanitizeDocId(String key) {
        return key.replaceAll("[^a-zA-Z0-9_\\-]", "_");
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
        } catch (IOException ignored) {
        }
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
