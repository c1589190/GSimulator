package com.gsim.worldinfo.tool;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.worldinfo.ElementRef;
import com.gsim.worldinfo.WorldInformation;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_by_tag — Query all elements matching a given tag across the node chain.
 *
 * <p>Uses {@link WorldInformation#byTag} which maintains a tag→element index
 * built from all checkpoints on the current branch. Useful for finding entities
 * by category tags like "Nation", "Character", "gsimap:region:大汉", etc.
 */
public final class QueryByTagTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public QueryByTagTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() {
        return "query_by_tag";
    }

    @Override
    public String description() {
        return """
            Query all elements matching a given tag.
            Searches across all nodes on the current branch via the tag index.
            Parameters: tag (required), checkpointId (optional filter),
            limit (optional, default 20), offset (optional, default 0).
            """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String tag = call.param("tag");
        if (tag == null || tag.isBlank()) {
            return ToolResult.fail(name(), "tag is required");
        }

        String checkpointFilter = call.param("checkpointId");
        int limit = Math.min(parseInt(call.param("limit"), 20), 100);
        int offset = Math.max(parseInt(call.param("offset"), 0), 0);

        WorldInformation wi = worldInfo.get();
        List<ElementRef> refs = wi.byTag(tag.trim());

        // Apply checkpoint filter
        if (checkpointFilter != null && !checkpointFilter.isBlank()) {
            refs = refs.stream()
                    .filter(r -> r.checkpointId().equals(checkpointFilter.trim()))
                    .toList();
        }

        int total = refs.size();

        // Apply pagination
        List<ElementRef> page = refs.stream().skip(offset).limit(limit).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("## query_by_tag: `").append(tag).append("`\n\n");
        sb.append("total: ")
                .append(total)
                .append(", offset: ")
                .append(offset)
                .append(", limit: ")
                .append(limit)
                .append("\n\n");

        boolean detail = "true".equalsIgnoreCase(call.param("detail"));
        for (ElementRef ref : page) {
            sb.append("### ")
                    .append(ref.element().key())
                    .append(" (")
                    .append(ref.nodeId())
                    .append(":")
                    .append(ref.checkpointId())
                    .append(")\n\n");
            sb.append("- **type**: ").append(ref.element().type()).append("\n");
            if (ref.element().tags() != null && !ref.element().tags().isEmpty()) {
                sb.append("- **tags**: ")
                        .append(String.join(", ", ref.element().tags()))
                        .append("\n");
            }
            if (ref.element().links() != null && !ref.element().links().isEmpty()) {
                sb.append("- **links**: ")
                        .append(String.join(", ", ref.element().links()))
                        .append("\n");
            }
            sb.append("- **updatedAt**: ").append(ref.element().updatedAt()).append("\n\n");
            String val = ref.element().value();
            if (val != null && !val.isBlank()) {
                if (!detail && val.length() > 200) {
                    sb.append(val, 0, 200).append("... (truncated, use detail=true for full content)\n\n");
                } else {
                    sb.append(val).append("\n\n");
                }
            }
            sb.append("---\n\n");
        }

        if (page.isEmpty()) {
            sb.append("(no results)\n");
        }

        return ToolResult.ok(name(), List.of(new ToolResult.Item(tag, "tag:" + tag, sb.toString(), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "tag", Map.of("type", "string", "description", "Tag to search for"),
                                "checkpointId", Map.of("type", "string", "description", "Optional checkpoint filter"),
                                "limit", Map.of("type", "integer", "description", "Max results (default 20, max 100)"),
                                "offset", Map.of("type", "integer", "description", "Pagination offset (default 0)"),
                                "detail",
                                        Map.of(
                                                "type",
                                                "boolean",
                                                "description",
                                                "Set to true for full element values (default: truncated to 200 chars)")),
                "required", List.of("tag"));
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }

    private static int parseInt(String val, int defaultValue) {
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
