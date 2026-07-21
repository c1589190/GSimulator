package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.ElementRef;
import com.gsim.worldinfo.WorldInformation;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_address — Universal address resolution tool.
 *
 * <p>Routes addresses by prefix:
 * <ul>
 *   <li>{@code gsimap:region:大汉} → delegates to {@code gsimap_query_by_address}</li>
 *   <li>{@code n0002:characters:曹操} or {@code characters:曹操} → delegates to internal query_element</li>
 *   <li>{@code 大汉} (plain tag, no colons) → uses {@link WorldInformation#byTag}</li>
 * </ul>
 *
 * <p>This is the primary address lookup tool for Agents — it hides the routing
 * complexity between GSim internal refs and GSimap map entity addresses.
 */
public final class QueryAddressTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;
    private final ToolRegistry toolRegistry;

    public QueryAddressTool(Supplier<WorldInformation> worldInfo, ToolRegistry toolRegistry) {
        this.worldInfo = worldInfo;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "query_address";
    }

    @Override
    public String description() {
        return """
            Resolve a universal address to its data.
            Routes: gsimap:* → map entity, nodeId:checkpointId:key → element,
            checkpointId:key → element on active node, plain text → tag lookup.
            Parameters: address (required), worldId (required for gsimap: addresses).
            """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String address = call.param("address");
        if (address == null || address.isBlank()) {
            return ToolResult.fail(name(), "address is required");
        }
        address = address.trim();

        // Route 1: gsimap:* → delegate to gsimap_query_by_address
        if (address.startsWith("gsimap:")) {
            String worldId = com.gsim.mcp.GsimRequestContext.worldId();
            if (worldId == null) {
                worldId = call.param("worldId");
            }
            if (worldId == null || worldId.isBlank()) {
                return ToolResult.fail(name(), "worldId is required for gsimap: addresses");
            }
            ToolCall gsimapCall =
                    new ToolCall("gsimap_query_by_address", Map.of("worldId", worldId, "address", address));
            return toolRegistry.call(gsimapCall);
        }

        // Route 2: contains colons → look up as elem ref (nodeId:checkpointId:key or checkpointId:key)
        if (address.contains(":")) {
            // Delegate to query_element
            ToolCall elemCall = new ToolCall("query_element", Map.of("ref", address));
            return toolRegistry.call(elemCall);
        }

        // Route 3: plain text → tag lookup
        WorldInformation wi = worldInfo.get();
        List<ElementRef> refs = wi.byTag(address);

        if (refs.isEmpty()) {
            return ToolResult.fail(name(), "No elements found for tag: " + address);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Address lookup by tag: `").append(address).append("`\n\n");

        int limit = Math.min(refs.size(), 20);
        for (int i = 0; i < limit; i++) {
            ElementRef ref = refs.get(i);
            sb.append("### ")
                    .append(ref.element().key())
                    .append(" (")
                    .append(ref.nodeId())
                    .append(":")
                    .append(ref.checkpointId())
                    .append(")\n\n");
            sb.append("- **type**: ").append(ref.element().type()).append("\n");
            if (ref.element().links() != null && !ref.element().links().isEmpty()) {
                sb.append("- **links**: ")
                        .append(String.join(", ", ref.element().links()))
                        .append("\n");
            }
            sb.append("- **updatedAt**: ").append(ref.element().updatedAt()).append("\n\n");
            String val = ref.element().value();
            if (val != null && !val.isBlank()) {
                sb.append(val).append("\n\n");
            }
            sb.append("---\n\n");
        }
        if (refs.size() > limit) {
            sb.append("(showing ")
                    .append(limit)
                    .append(" of ")
                    .append(refs.size())
                    .append(" results)\n");
        }

        return ToolResult.ok(name(), List.of(new ToolResult.Item(address, "tag:" + address, sb.toString(), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "address",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Address: gsimap:region:name, nodeId:cp:key, cp:key, or plain tag"),
                                "worldId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "GSim world ID (required for gsimap: addresses)")),
                "required", List.of("address"));
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
