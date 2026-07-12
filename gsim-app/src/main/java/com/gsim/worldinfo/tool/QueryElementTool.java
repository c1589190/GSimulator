package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.Element;
import com.gsim.worldinfo.ElementRef;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_element — precise element lookup by unified ID.
 *
 * <p>Ref format: {@code nodeId:checkpointId:key} (e.g. {@code n0002:characters:曹操}).
 * If nodeId is omitted ({@code checkpointId:key}), defaults to the current active node.
 *
 * <p>This is the complement of {@link WriteElementTool}: you write with a key,
 * all queries return elements in {@code nodeId:checkpointId:key} format,
 * and this tool resolves any such reference back to the element content.
 */
public final class QueryElementTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public QueryElementTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() { return "query_element"; }

    @Override
    public String description() {
        return """
            Resolve a precise element reference to its full content.
            Ref format: nodeId:checkpointId:key (e.g. 'n0002:characters:曹操').
            If nodeId is omitted (e.g. 'characters:曹操'), defaults to the current active node.
            This is the only tool that can follow links written via write_element.
            """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String ref = call.param("ref");
        if (ref == null || ref.isBlank()) {
            return ToolResult.fail("query_element", "ref is required (format: nodeId:checkpointId:key or checkpointId:key)");
        }

        WorldInformation wi = worldInfo.get();

        // Parse ref: nodeId:checkpointId:key  or  checkpointId:key
        String[] parts = ref.split(":", 3);
        String nodeId, checkpointId, key;

        if (parts.length == 2) {
            // checkpointId:key — default to current active node
            nodeId = wi.activeNodeId();
            checkpointId = parts[0].trim();
            key = parts[1].trim();
        } else if (parts.length == 3) {
            nodeId = parts[0].trim();
            checkpointId = parts[1].trim();
            key = parts[2].trim();
        } else {
            return ToolResult.fail("query_element",
                "Invalid ref format: '" + ref + "'. Expected nodeId:checkpointId:key or checkpointId:key");
        }

        if (checkpointId.isEmpty() || key.isEmpty()) {
            return ToolResult.fail("query_element",
                "Invalid ref: checkpointId and key must not be empty");
        }

        // Find the node
        NodeSnapshot node = wi.nodeById(nodeId);
        if (node == null) {
            List<String> available = wi.branchChain().stream()
                .map(n -> n.nodeId() + "[t" + n.turn() + "]").toList();
            return ToolResult.fail("query_element",
                "Node '" + nodeId + "' not found in current branch chain. " +
                "Available nodes: " + available + ". " +
                "Use node_list to see all nodes. " +
                "Use node_create to create a new child node, or node_switch to switch to an existing one.");
        }

        // Find the checkpoint
        Checkpoint cp = node.checkpoints().get(checkpointId);
        if (cp == null) {
            List<String> existing = new ArrayList<>(node.checkpoints().keySet());
            return ToolResult.fail("query_element",
                "Checkpoint '" + checkpointId + "' not found in node " + nodeId + ". " +
                (existing.isEmpty() ? "This node has no checkpoints yet. "
                    : "Existing checkpoints: " + existing + ". ") +
                "You can create it explicitly with create_checkpoint checkpointId=" + checkpointId +
                ", or just use write_element ref=" + nodeId + ":" + checkpointId + ":<key> " +
                "— write_element auto-creates checkpoints that don't exist.");
        }

        // Find the element by key
        Element found = null;
        for (Element el : cp.elements()) {
            if (el.key().equals(key)) {
                found = el;
                break;
            }
        }

        if (found == null) {
            List<String> existingKeys = cp.elements().stream()
                .map(Element::key).toList();
            return ToolResult.fail("query_element",
                "Element '" + key + "' not found in " + nodeId + ":" + checkpointId + ". " +
                (existingKeys.isEmpty() ? "This checkpoint is empty. "
                    : "Existing keys: " + existingKeys + ". ") +
                "Use write_element ref=" + nodeId + ":" + checkpointId + ":" + key +
                " value=\"...\" to create it.");
        }

        // Collect forward links: if this element has links, resolve them too
        List<ToolResult.Item> items = new ArrayList<>();
        String unifiedId = nodeId + ":" + checkpointId + ":" + key;

        // Main result
        items.add(new ToolResult.Item(key, unifiedId, found.value(), 1.0));

        // Tags
        if (!found.tags().isEmpty()) {
            items.add(new ToolResult.Item(key + " [tags]", unifiedId,
                String.join(", ", found.tags()), 0.5));
        }

        // Links — resolve each link target and include a preview
        if (!found.links().isEmpty()) {
            StringBuilder linkPreview = new StringBuilder();
            for (String link : found.links()) {
                linkPreview.append("- ").append(link);
                // Try to resolve the link target (best-effort, may be to another node)
                try {
                    ElementRef resolved = resolveRef(wi, link, nodeId);
                    if (resolved != null) {
                        String snippet = resolved.element().value();
                        if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "...";
                        linkPreview.append("  → ").append(snippet.replace("\n", " "));
                    } else {
                        linkPreview.append("  → (unresolved)");
                    }
                } catch (Exception ignored) {
                    linkPreview.append("  → (parse error)");
                }
                linkPreview.append("\n");
            }
            items.add(new ToolResult.Item(key + " [links]", unifiedId,
                linkPreview.toString().strip(), 0.5));
        }

        return ToolResult.ok("query_element", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "ref", Map.of("type", "string", "description",
                    "Element reference in nodeId:checkpointId:key format. " +
                    "Example: 'n0002:characters:曹操'. " +
                    "Omit nodeId to default to current node: 'characters:曹操'")
            ),
            "required", List.of("ref")
        );
    }

    /**
     * Best-effort resolve a link reference to an ElementRef.
     * The link may be in any of these forms:
     * <ul>
     *   <li>{@code nodeId:checkpointId:key} — fully qualified</li>
     *   <li>{@code checkpointId:key} — same node (caller provides defaultNodeId)</li>
     *   <li>{@code key} — search across all nodes (slow, fallback)</li>
     * </ul>
     */
    static ElementRef resolveRef(WorldInformation wi, String link, String defaultNodeId) {
        String[] parts = link.split(":", 3);
        String nodeId, checkpointId, key;

        if (parts.length == 3) {
            nodeId = parts[0].trim();
            checkpointId = parts[1].trim();
            key = parts[2].trim();
        } else if (parts.length == 2) {
            nodeId = defaultNodeId;
            checkpointId = parts[0].trim();
            key = parts[1].trim();
        } else {
            // Single key — search all nodes
            for (NodeSnapshot n : wi.branchChain()) {
                for (var entry : n.checkpoints().entrySet()) {
                    for (Element el : entry.getValue().elements()) {
                        if (el.key().equals(link.trim())) {
                            return ElementRef.from(n.nodeId(), n.turn(), n.worldTime(),
                                entry.getKey(), el);
                        }
                    }
                }
            }
            return null;
        }

        NodeSnapshot node = wi.nodeById(nodeId);
        if (node == null) return null;
        Checkpoint cp = node.checkpoints().get(checkpointId);
        if (cp == null) return null;
        for (Element el : cp.elements()) {
            if (el.key().equals(key)) {
                return ElementRef.from(nodeId, node.turn(), node.worldTime(), checkpointId, el);
            }
        }
        return null;
    }
}
