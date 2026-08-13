package com.gsim.agent.tools.worldinfo;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.worldinfo.Checkpoint;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.ElementRef;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_element -- 通过统一引用格式精确查询元素。
 *
 * <p>引用格式：{@code nodeId:checkpointId:key}（如 {@code n0002:characters:曹操}）。
 * 如果省略 nodeId（使用 {@code checkpointId:key} 格式），默认查询当前活跃节点。
 *
 * <p>这是 {@link WriteElementTool} 的对应读取工具。links 字段中的引用会先尝试
 * 内部解析（GSim ref 格式），失败时自动通过 {@code query_address} 路由到其他
 * 模块（如 gsimap）。
 */
public final class QueryElementTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;
    private final ToolRegistry toolRegistry;

    public QueryElementTool(Supplier<WorldInformation> worldInfo, ToolRegistry toolRegistry) {
        this.worldInfo = worldInfo;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "query_element";
    }

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
            return ToolResult.fail(
                    "query_element", "ref is required (format: nodeId:checkpointId:key or checkpointId:key)");
        }

        WorldInformation wi = worldInfo.get();

        // Parse ref: nodeId:checkpointId:key  or  checkpointId:key
        String[] parts = ref.split(":", 3);
        String nodeId, checkpointId, key;

        if (parts.length == 2) {
            // checkpointId:key — default to current active node
            nodeId = call.param("nodeId");
            if (nodeId == null || nodeId.isBlank())
                return ToolResult.fail(name(), "[NODE_ID_REQUIRED] nodeId is required");
            checkpointId = parts[0].trim();
            key = parts[1].trim();
        } else if (parts.length == 3) {
            nodeId = parts[0].trim();
            checkpointId = parts[1].trim();
            key = parts[2].trim();
        } else {
            return ToolResult.fail(
                    "query_element",
                    "Invalid ref format: '" + ref + "'. Expected nodeId:checkpointId:key or checkpointId:key");
        }

        if (checkpointId.isEmpty() || key.isEmpty()) {
            return ToolResult.fail("query_element", "Invalid ref: checkpointId and key must not be empty");
        }

        // Find the node
        NodeSnapshot node = wi.nodeById(nodeId);
        if (node == null) {
            List<String> available = wi.branchChain().stream()
                    .map(n -> n.nodeId() + "[t" + n.turn() + "]")
                    .toList();
            return ToolResult.fail(
                    "query_element",
                    "Node '" + nodeId + "' not found in current branch chain. " + "Available nodes: "
                            + available + ". " + "Use node_list to see all nodes. "
                            + "Use node_create to create a new child node.");
        }

        // Find the checkpoint
        Checkpoint cp = node.checkpoints().get(checkpointId);
        if (cp == null) {
            List<String> existing = new ArrayList<>(node.checkpoints().keySet());
            return ToolResult.fail(
                    "query_element",
                    "Checkpoint '" + checkpointId + "' not found in node " + nodeId + ". "
                            + (existing.isEmpty()
                                    ? "This node has no checkpoints yet. "
                                    : "Existing checkpoints: " + existing + ". ")
                            + "You can create it explicitly with create_checkpoint checkpointId="
                            + checkpointId + ", or just use write_element ref="
                            + nodeId + ":" + checkpointId + ":<key> "
                            + "— write_element auto-creates checkpoints that don't exist.");
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
            List<String> existingKeys = cp.elements().stream().map(Element::key).toList();
            return ToolResult.fail(
                    "query_element",
                    "Element '" + key + "' not found in " + nodeId + ":" + checkpointId + ". "
                            + (existingKeys.isEmpty()
                                    ? "This checkpoint is empty. "
                                    : "Existing keys: " + existingKeys + ". ")
                            + "Use write_element ref="
                            + nodeId + ":" + checkpointId + ":" + key + " value=\"...\" to create it.");
        }

        // Collect forward links: if this element has links, resolve them too
        List<ToolResult.Item> items = new ArrayList<>();
        String unifiedId = nodeId + ":" + checkpointId + ":" + key;

        // Main result
        items.add(new ToolResult.Item(key, unifiedId, found.value(), 1.0));

        // Tags
        if (!found.tags().isEmpty()) {
            items.add(new ToolResult.Item(key + " [tags]", unifiedId, String.join(", ", found.tags()), 0.5));
        }

        // Links — resolve each link target and include a preview
        if (!found.links().isEmpty()) {
            StringBuilder linkPreview = new StringBuilder();
            for (String link : found.links()) {
                linkPreview.append("- ").append(link);
                // Try internal resolution first, then fall back to query_address router
                try {
                    ElementRef resolved = resolveRef(wi, link, nodeId);
                    if (resolved != null) {
                        String snippet = resolved.element().value();
                        if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "...";
                        linkPreview.append("  → ").append(snippet.replace("\n", " "));
                    } else {
                        // Fallback: route through query_address for cross-module addresses
                        String fallback = resolveViaAddressRouter(link);
                        if (fallback != null) {
                            linkPreview.append("  → ").append(fallback);
                        } else {
                            linkPreview.append("  → (unresolved)");
                        }
                    }
                } catch (Exception ignored) {
                    linkPreview.append("  → (parse error)");
                }
                linkPreview.append("\n");
            }
            items.add(new ToolResult.Item(
                    key + " [links]", unifiedId, linkPreview.toString().strip(), 0.5));
        }

        return ToolResult.ok("query_element", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "ref",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "Element reference in nodeId:checkpointId:key format. "
                                                + "Example: 'n0002:characters:曹操'. "
                                                + "Omit nodeId to default to current node: 'characters:曹操'")),
                "required", List.of("ref"));
    }

    /**
     * Fallback: route an unresolvable link through the query_address router.
     * This handles cross-module addresses (gsimap:*, etc.) that GSim internal
     * resolution cannot parse.
     *
     * @return a brief preview string, or null if routing also failed
     */
    private String resolveViaAddressRouter(String link) {
        if (toolRegistry == null) return null;
        try {
            ToolResult result = toolRegistry.call(new ToolCall("query_address", Map.of("address", link)));
            if (result.success() && !result.items().isEmpty()) {
                String snippet = result.items().get(0).snippet();
                if (snippet != null) {
                    // Take first line as preview
                    String firstLine = snippet.split("\n")[0];
                    if (firstLine.length() > 80) firstLine = firstLine.substring(0, 80) + "...";
                    return firstLine;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Best-effort resolve a link reference to an ElementRef.
     * The link may be in any of these forms:
     * <ul>
     *   <li>{@code nodeId:checkpointId:key} — fully qualified</li>
     *   <li>{@code checkpointId:key} — same node (caller provides defaultNodeId)</li>
     *   <li>{@code key} — search across all nodes (slow, fallback)</li>
     * </ul>
     * If internal resolution fails, caller should fall back to {@link #resolveViaAddressRouter}.
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
                            return ElementRef.from(n.nodeId(), n.turn(), n.worldTime(), entry.getKey(), el);
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

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
