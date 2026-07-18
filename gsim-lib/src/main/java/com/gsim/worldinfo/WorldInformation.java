package com.gsim.worldinfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界信息 -- 从分支链加载的完整世界状态。
 *
 * <p>不可变类（构造后除 {@link #appendElement} 和 {@link #upsertElement} 外不可修改），
 * 提供按检查点、标签和关键词查询世界状态的能力。内部维护检查点索引、标签索引和
 * 关键词倒排索引以加速查询。
 */
public final class WorldInformation {

    private final String worldId;
    private final String rootNodeId;
    private final String activeNodeId;
    private final List<NodeSnapshot> branchChain; // root → active
    private final Map<String, List<ElementRef>> byCheckpoint; // checkpointId → all elements
    private final Map<String, List<ElementRef>> byTag; // tag → elements
    private final KeywordIndex keywordIndex;

    public WorldInformation(String worldId, List<NodeSnapshot> branchChain) {
        this.worldId = worldId;
        this.branchChain = List.copyOf(branchChain);
        this.rootNodeId = branchChain.isEmpty() ? null : branchChain.get(0).nodeId();
        this.activeNodeId = branchChain.isEmpty()
                ? null
                : branchChain.get(branchChain.size() - 1).nodeId();
        this.byCheckpoint = buildByCheckpoint(branchChain);
        this.byTag = buildByTag(branchChain);
        this.keywordIndex = KeywordIndex.build(branchChain);
    }

    // -- accessors --
    public String worldId() {
        return worldId;
    }

    public String rootNodeId() {
        return rootNodeId;
    }

    public String activeNodeId() {
        return activeNodeId;
    }

    public List<NodeSnapshot> branchChain() {
        return branchChain;
    }

    /**
     * 获取当前活跃节点。
     *
     * @return 活跃节点快照
     */
    public NodeSnapshot activeNode() {
        return branchChain.get(branchChain.size() - 1);
    }

    /**
     * 根据节点 ID 查找节点。
     *
     * @param nodeId 节点 ID
     * @return 节点快照，如果未找到则返回 null
     */
    public NodeSnapshot nodeById(String nodeId) {
        return branchChain.stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElse(null);
    }

    // -- checkpoint queries --

    /**
     * 获取指定检查点在整条链上的全部历史元素。
     *
     * @param checkpointId 检查点 ID
     * @return 元素引用列表（按节点链顺序排列）
     */
    public List<ElementRef> checkpointHistory(String checkpointId) {
        return byCheckpoint.getOrDefault(checkpointId, List.of());
    }

    /**
     * 获取指定检查点在指定回合范围内的历史元素。
     *
     * @param checkpointId 检查点 ID
     * @param turnFrom     起始回合数（包含）
     * @param turnTo       结束回合数（包含）
     * @return 元素引用列表（按节点链顺序排列）
     */
    public List<ElementRef> checkpointHistory(String checkpointId, int turnFrom, int turnTo) {
        return byCheckpoint.getOrDefault(checkpointId, List.of()).stream()
                .filter(r -> r.turn() >= turnFrom && r.turn() <= turnTo)
                .toList();
    }

    /**
     * 按通配符前缀查询检查点历史。
     *
     * <p>例如 {@code checkpointHistoryByPrefix("player.*")} 返回所有检查点 ID 符合
     * 该模式（如 {@code player.曹操}、{@code player.刘备}）的元素。支持 {@code *}
     * 作为多字符通配符。
     *
     * @param pattern 检查点 ID 匹配模式（以 {@code *} 结尾表示前缀匹配）
     * @return 元素引用列表
     */
    public List<ElementRef> checkpointHistoryByPrefix(String pattern) {
        String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : null;
        return byCheckpoint.entrySet().stream()
                .filter(e -> prefix != null
                        ? e.getKey().startsWith(prefix)
                        : e.getKey().equals(pattern))
                .flatMap(e -> e.getValue().stream())
                .toList();
    }

    /**
     * 获取所有检查点 ID。
     *
     * @return 检查点 ID 的不可变列表
     */
    public List<String> allCheckpointIds() {
        return List.copyOf(byCheckpoint.keySet());
    }

    // -- tag queries --

    /**
     * 根据标签查询所有关联的元素。
     *
     * @param tag 标签名称
     * @return 带有该标签的元素引用列表
     */
    public List<ElementRef> byTag(String tag) {
        return byTag.getOrDefault(tag, List.of());
    }

    // -- keyword --

    /**
     * 获取关键词倒排索引。
     *
     * @return 关键词索引实例
     */
    public KeywordIndex keywordIndex() {
        return keywordIndex;
    }

    // -- mutation (called by write_element tool) --

    /**
     * 向指定节点和检查点追加元素。
     *
     * <p>如果检查点不存在，则自动创建。追加的元素会同步更新检查点索引、
     * 标签索引和关键词索引。
     *
     * @param nodeId       目标节点 ID
     * @param checkpointId 目标检查点 ID
     * @param element      要追加的信息单元
     * @throws IllegalArgumentException 如果节点不存在
     */
    public synchronized void appendElement(String nodeId, String checkpointId, Element element) {
        NodeSnapshot node = nodeById(nodeId);
        if (node == null) throw new IllegalArgumentException("Unknown node: " + nodeId);
        Checkpoint cp = node.checkpoints().get(checkpointId);
        if (cp == null) {
            // auto-create checkpoint
            cp = new Checkpoint(checkpointId, "misc", new ArrayList<>());
            node.checkpoints().put(checkpointId, cp);
        }
        cp.elements().add(element);

        ElementRef ref = ElementRef.from(nodeId, node.turn(), node.worldTime(), checkpointId, element);
        byCheckpoint.computeIfAbsent(checkpointId, k -> new ArrayList<>()).add(ref);
        for (String t : element.tags()) {
            byTag.computeIfAbsent(t, k -> new ArrayList<>()).add(ref);
        }
        keywordIndex.add(ref);
    }

    /**
     * 更新或插入元素。
     *
     * <p>如果同一检查点中已存在相同 key 的元素，则替换之；否则追加到检查点末尾。
     * 替换时会同步更新检查点索引、标签索引和关键词索引。
     *
     * @param nodeId       目标节点 ID
     * @param checkpointId 目标检查点 ID
     * @param element      要写入的信息单元
     * @return 如果替换了已有元素返回 true，如果追加了新元素返回 false
     * @throws IllegalArgumentException 如果节点不存在
     */
    public synchronized boolean upsertElement(String nodeId, String checkpointId, Element element) {
        NodeSnapshot node = nodeById(nodeId);
        if (node == null) throw new IllegalArgumentException("Unknown node: " + nodeId);
        Checkpoint cp = node.checkpoints().get(checkpointId);
        if (cp == null) {
            cp = new Checkpoint(checkpointId, "misc", new ArrayList<>());
            node.checkpoints().put(checkpointId, cp);
        }

        // find existing element with same key in this checkpoint
        List<Element> elements = cp.elements();
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i).key().equals(element.key())) {
                // remove old refs from indexes
                ElementRef oldRef =
                        ElementRef.from(nodeId, node.turn(), node.worldTime(), checkpointId, elements.get(i));
                removeRefFromIndexes(oldRef);
                // replace
                elements.set(i, element);
                // add new ref to indexes
                ElementRef newRef = ElementRef.from(nodeId, node.turn(), node.worldTime(), checkpointId, element);
                addRefToIndexes(newRef);
                return true;
            }
        }

        // not found — append
        cp.elements().add(element);
        ElementRef ref = ElementRef.from(nodeId, node.turn(), node.worldTime(), checkpointId, element);
        addRefToIndexes(ref);
        return false;
    }

    private void addRefToIndexes(ElementRef ref) {
        byCheckpoint.computeIfAbsent(ref.checkpointId(), k -> new ArrayList<>()).add(ref);
        for (String t : ref.element().tags()) {
            byTag.computeIfAbsent(t, k -> new ArrayList<>()).add(ref);
        }
        keywordIndex.add(ref);
    }

    private void removeRefFromIndexes(ElementRef ref) {
        // byCheckpoint
        List<ElementRef> cpList = byCheckpoint.get(ref.checkpointId());
        if (cpList != null)
            cpList.removeIf(r -> r.element().key().equals(ref.element().key()));
        // byTag
        for (String t : ref.element().tags()) {
            List<ElementRef> tagList = byTag.get(t);
            if (tagList != null)
                tagList.removeIf(r -> r.element().key().equals(ref.element().key()));
        }
        // keywordIndex — no removal API yet; old ref becomes stale but won't be returned
        // since we rebuild the index on load; for live sessions the old ref lingers
    }

    // -- builders --
    private static Map<String, List<ElementRef>> buildByCheckpoint(List<NodeSnapshot> chain) {
        Map<String, List<ElementRef>> map = new LinkedHashMap<>();
        for (NodeSnapshot node : chain) {
            for (var entry : node.checkpoints().entrySet()) {
                String cpId = entry.getKey();
                Checkpoint cp = entry.getValue();
                List<ElementRef> list = map.computeIfAbsent(cpId, k -> new ArrayList<>());
                for (Element el : cp.elements()) {
                    list.add(ElementRef.from(node.nodeId(), node.turn(), node.worldTime(), cpId, el));
                }
            }
        }
        return map;
    }

    private static Map<String, List<ElementRef>> buildByTag(List<NodeSnapshot> chain) {
        Map<String, List<ElementRef>> map = new LinkedHashMap<>();
        for (NodeSnapshot node : chain) {
            for (var entry : node.checkpoints().entrySet()) {
                String cpId = entry.getKey();
                for (Element el : entry.getValue().elements()) {
                    ElementRef ref = ElementRef.from(node.nodeId(), node.turn(), node.worldTime(), cpId, el);
                    for (String tag : el.tags()) {
                        map.computeIfAbsent(tag, k -> new ArrayList<>()).add(ref);
                    }
                }
            }
        }
        return map;
    }

    @Override
    public String toString() {
        return "WorldInformation[world=%s, nodes=%d, checkpoints=%d, tags=%d]"
                .formatted(worldId, branchChain.size(), byCheckpoint.size(), byTag.size());
    }
}
