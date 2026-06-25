package com.gsim.worldinfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Full world state loaded from the branch chain.
 * Immutable after construction except for {@link #appendElement}.
 */
public final class WorldInformation {

    private final String worldId;
    private final String rootNodeId;
    private final String activeNodeId;
    private final List<NodeSnapshot> branchChain;          // root → active
    private final Map<String, List<ElementRef>> byCheckpoint; // checkpointId → all elements
    private final Map<String, List<ElementRef>> byTag;        // tag → elements
    private final Object keywordIndex;                        // FIXME KeywordIndex in Task 4

    public WorldInformation(String worldId, List<NodeSnapshot> branchChain) {
        this.worldId = worldId;
        this.branchChain = List.copyOf(branchChain);
        this.rootNodeId = branchChain.isEmpty() ? null : branchChain.get(0).nodeId();
        this.activeNodeId = branchChain.isEmpty() ? null : branchChain.get(branchChain.size() - 1).nodeId();
        this.byCheckpoint = buildByCheckpoint(branchChain);
        this.byTag = buildByTag(branchChain);
        this.keywordIndex = null; // FIXME KeywordIndex.build(branchChain) in Task 4
    }

    // -- accessors --
    public String worldId() { return worldId; }
    public String rootNodeId() { return rootNodeId; }
    public String activeNodeId() { return activeNodeId; }
    public List<NodeSnapshot> branchChain() { return branchChain; }
    public NodeSnapshot activeNode() { return branchChain.get(branchChain.size() - 1); }
    public NodeSnapshot nodeById(String nodeId) {
        return branchChain.stream().filter(n -> n.nodeId().equals(nodeId)).findFirst().orElse(null);
    }

    // -- checkpoint queries --
    public List<ElementRef> checkpointHistory(String checkpointId) {
        return byCheckpoint.getOrDefault(checkpointId, List.of());
    }

    public List<ElementRef> checkpointHistory(String checkpointId, int turnFrom, int turnTo) {
        return byCheckpoint.getOrDefault(checkpointId, List.of()).stream()
            .filter(r -> r.turn() >= turnFrom && r.turn() <= turnTo)
            .toList();
    }

    public List<String> allCheckpointIds() {
        return List.copyOf(byCheckpoint.keySet());
    }

    // -- tag queries --
    public List<ElementRef> byTag(String tag) {
        return byTag.getOrDefault(tag, List.of());
    }

    // -- keyword --
    public Object keywordIndex() { return keywordIndex; } // FIXME return KeywordIndex in Task 4

    // -- mutation (called by write_element tool) --
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
        // FIXME keywordIndex.add(ref) in Task 4 when KeywordIndex exists
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
        return "WorldInformation[world=%s, nodes=%d, checkpoints=%d, tags=%d]".formatted(
            worldId, branchChain.size(), byCheckpoint.size(), byTag.size());
    }
}
