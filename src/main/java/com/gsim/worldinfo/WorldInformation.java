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
    private final KeywordIndex keywordIndex;

    public WorldInformation(String worldId, List<NodeSnapshot> branchChain) {
        this.worldId = worldId;
        this.branchChain = List.copyOf(branchChain);
        this.rootNodeId = branchChain.isEmpty() ? null : branchChain.get(0).nodeId();
        this.activeNodeId = branchChain.isEmpty() ? null : branchChain.get(branchChain.size() - 1).nodeId();
        this.byCheckpoint = buildByCheckpoint(branchChain);
        this.byTag = buildByTag(branchChain);
        this.keywordIndex = KeywordIndex.build(branchChain);
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

    /**
     * Wildcard / prefix query: {@code checkpointHistoryByPrefix("player.*")} returns
     * all elements from checkpointIds that match the pattern (e.g. {@code player.曹操},
     * {@code player.刘备}). Supports {@code *} as a multi-character wildcard.
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

    public List<String> allCheckpointIds() {
        return List.copyOf(byCheckpoint.keySet());
    }

    // -- tag queries --
    public List<ElementRef> byTag(String tag) {
        return byTag.getOrDefault(tag, List.of());
    }

    // -- keyword --
    public KeywordIndex keywordIndex() { return keywordIndex; }

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
        keywordIndex.add(ref);
    }

    /**
     * Upsert: if an element with the same key already exists in the same checkpoint,
     * replace it; otherwise append.
     * @return true if replaced, false if appended
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
                ElementRef oldRef = ElementRef.from(nodeId, node.turn(), node.worldTime(), checkpointId, elements.get(i));
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
        if (cpList != null) cpList.removeIf(r -> r.element().key().equals(ref.element().key()));
        // byTag
        for (String t : ref.element().tags()) {
            List<ElementRef> tagList = byTag.get(t);
            if (tagList != null) tagList.removeIf(r -> r.element().key().equals(ref.element().key()));
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
        return "WorldInformation[world=%s, nodes=%d, checkpoints=%d, tags=%d]".formatted(
            worldId, branchChain.size(), byCheckpoint.size(), byTag.size());
    }
}
