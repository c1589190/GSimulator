package com.gsim.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import com.gsim.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter that exposes a {@link ToolRegistry} as an {@link McpToolRegistry}.
 *
 * <p>This is the bridge between the AgentTool system (internal Agent ToolLoop)
 * and the MCP protocol (external JSON-RPC 2.0 clients). Every {@link AgentTool}
 * registered in the {@link ToolRegistry} is automatically discoverable via
 * MCP {@code tools/list} and callable via MCP {@code tools/call}.
 *
 * <p>The adapter is read-only from the MCP perspective — it only reads from
 * ToolRegistry. Tool registration still happens through
 * {@link ToolRegistry#register(AgentTool)}.
 *
 * <h3>Conversion details</h3>
 * <ul>
 *   <li>{@code AgentTool.name()} → {@code ToolDef.name}</li>
 *   <li>{@code AgentTool.description()} → {@code ToolDef.description}</li>
 *   <li>{@code AgentTool.getParameters()} → serialized as JSON Schema string → {@code ToolDef.schema}</li>
 *   <li>{@code JsonNode} args → flattened to {@code Map<String, String>} → {@code ToolCall}</li>
 *   <li>{@code ToolResult} → serialized as JSON string for MCP response</li>
 * </ul>
 */
public final class ToolRegistryMcpAdapter implements McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryMcpAdapter.class);
    private static final ObjectMapper MAPPER = JsonUtils.MAPPER;

    /** Default wide schema used when an AgentTool returns null from getParameters(). */
    private static final String WIDE_SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":true}";

    /** Reserved parameter names consumed by the adapter (not passed to tools). */
    private static final String PARAM_PAGE = "_page";

    private static final String PARAM_PAGE_SIZE = "_pageSize";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** Hard ceiling for the serialized JSON response before aggressive truncation. */
    private static final int MAX_JSON_BYTES = 50_000;

    private final ToolRegistry registry;

    /**
     * Creates an MCP adapter wrapping the given ToolRegistry.
     *
     * @param registry the tool registry to expose via MCP (must not be null)
     */
    public ToolRegistryMcpAdapter(ToolRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("ToolRegistry must not be null");
        }
        this.registry = registry;
    }

    // ── McpToolRegistry ─────────────────────────────────────

    @Override
    public List<ToolDef> all() {
        return registry.all().values().stream()
                .map(tool -> new ToolDef(mcpName(tool.name()), descriptionOrEmpty(tool), schemaForTool(tool)))
                .toList();
    }

    @Override
    public String execute(String name, JsonNode args) throws Exception {
        // Strip gsim_ prefix for lookup in ToolRegistry (which uses short names)
        String registryName = toRegistryName(name);
        AgentTool tool = registry.get(registryName);
        if (tool == null) {
            // Fallback: also try with the original name
            tool = registry.get(name);
        }
        if (tool == null) {
            throw new UnknownToolException(name);
        }

        // Build tool params, stripping reserved pagination keys so tools never see them
        Map<String, String> toolParams = jsonNodeToParams(args);
        toolParams.remove(PARAM_PAGE);
        toolParams.remove(PARAM_PAGE_SIZE);

        ToolCall call = new ToolCall(name, toolParams);
        ToolResult result;

        try {
            result = tool.execute(call);
        } catch (Exception e) {
            log.error("Tool '{}' execution threw exception: {}", name, e.getMessage(), e);
            throw new IllegalArgumentException("Tool '" + name + "' execution failed: " + e.getMessage());
        }

        if (!result.success()) {
            throw new IllegalArgumentException("Tool '" + name + "' failed: " + result.error());
        }

        // Pass original args (with pagination params) to the serialization layer
        return toolResultToJson(result, args);
    }

    // ── Name mapping ───────────────────────────────────────

    /** Standard prefix for core GSim tools exposed via MCP. */
    private static final String GSIM_PREFIX = "gsim_";

    /**
     * Returns the MCP-facing name for a tool.
     * Tools that already have a namespace prefix (gsim_, gsimap_) keep their name.
     * All others get the gsim_ prefix added.
     */
    static String mcpName(String registryName) {
        if (registryName.startsWith("gsim_") || registryName.startsWith("gsimap_")) {
            return registryName;
        }
        return GSIM_PREFIX + registryName;
    }

    /**
     * Converts an MCP tool name back to the registry key.
     * Strips gsim_ prefix if present (unless the original name already has it).
     */
    private static String toRegistryName(String mcpName) {
        // If it starts with gsimap_, keep as-is (those are exact matches)
        if (mcpName.startsWith("gsimap_")) {
            return mcpName;
        }
        // If it starts with gsim_, strip the prefix to get the registry name
        if (mcpName.startsWith(GSIM_PREFIX)) {
            return mcpName.substring(GSIM_PREFIX.length());
        }
        return mcpName;
    }

    // ── Conversion helpers ──────────────────────────────────

    /** Returns the tool description, or empty string if null. */
    private static String descriptionOrEmpty(AgentTool tool) {
        String desc = tool.description();
        return desc != null ? desc : "";
    }

    /**
     * Converts an AgentTool's getParameters() to a JSON Schema string.
     * Returns a wide/open schema when getParameters() returns null.
     * Injects {@code _page} / {@code _pageSize} pagination parameters
     * into every tool's schema so MCP hosts can discover them.
     */
    @SuppressWarnings("unchecked")
    static String schemaForTool(AgentTool tool) {
        Map<String, Object> params = tool.getParameters();
        if (params == null) {
            return WIDE_SCHEMA;
        }
        try {
            // Inject reserved pagination params into properties
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");
            if (properties != null && !properties.containsKey(PARAM_PAGE)) {
                // Must create mutable copies to avoid corrupting the tool's original schema
                properties = new LinkedHashMap<>(properties);
                params = new LinkedHashMap<>(params);
                params.put("properties", properties);
                properties.put(
                        PARAM_PAGE,
                        Map.of(
                                "type", "integer",
                                "description", "Page number for paginated results (1-based, default: 1)"));
                properties.put(
                        PARAM_PAGE_SIZE,
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "For multi-item results: items per page (default 20, max 100). "
                                        + "For single-item results: characters per 200-char unit (default 20 = 4000 chars)."));
            }
            return MAPPER.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize schema for tool '{}', using fallback", tool.name(), e);
            return "{\"type\":\"object\"}";
        }
    }

    /**
     * Flattens a JsonNode argument tree into a Map of String to String.
     *
     * <p>Primitive values (numbers, booleans) are converted via {@code asText()}.
     * Arrays and objects are serialized via {@code toString()} (lossy, but
     * compatible with the ToolCall contract which expects String values).
     */
    static Map<String, String> jsonNodeToParams(JsonNode args) {
        Map<String, String> params = new LinkedHashMap<>();
        if (args == null || !args.isObject()) {
            return params;
        }
        var fields = args.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isValueNode()) {
                // string, number, boolean → text representation
                params.put(key, value.asText());
            } else {
                // array, object → JSON string
                params.put(key, value.toString());
            }
        }
        return params;
    }

    /**
     * Serializes a ToolResult into a JSON string suitable for MCP response.
     *
     * <p>Applies universal pagination and size protection:
     * <ol>
     *   <li>Slices {@code items} by {@code _page}/{@code _pageSize} from args</li>
     *   <li>Adds pagination metadata ({@code _totalItems}, {@code _hasMore})</li>
     *   <li>If serialized JSON exceeds 50 KB, truncates individual snippet lengths</li>
     * </ol>
     *
     * @param result the tool execution result
     * @param args   original MCP request arguments (may contain pagination params)
     * @return JSON string ready for MCP response
     */
    static String toolResultToJson(ToolResult result, JsonNode args) {
        try {
            // ── 1. Extract pagination params ──────────────────
            int page = extractInt(args, PARAM_PAGE, 1);
            int pageSize = Math.clamp(extractInt(args, PARAM_PAGE_SIZE, DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);

            List<ToolResult.Item> allItems = result.items();
            int totalItems = allItems.size();
            boolean hasMore;

            // ── 2. Slice ─────────────────────────────────────
            // Two modes:
            //   Multi-item (≥1 items): slice by item count (pageSize = items per page)
            //   Single-item (1 huge snippet): slice by characters (pageSize = chars per K)
            List<ToolResult.Item> pageItems;
            Map<String, Object> map;

            if (totalItems == 1 && allItems.get(0).snippet() != null) {
                // ── Single-item mode: character-level pagination ──
                String fullSnippet = allItems.get(0).snippet();
                int charPageSize = pageSize * 200; // pageSize=20 → 4000 chars
                int totalChars = fullSnippet.length();
                int charFrom = (page - 1) * charPageSize;
                int charTo = Math.min(charFrom + charPageSize, totalChars);
                hasMore = charTo < totalChars;

                String chunk;
                if (charFrom < totalChars) {
                    chunk = fullSnippet.substring(charFrom, charTo);
                    if (hasMore) {
                        chunk += "\n\n(page " + page + " of " + ((totalChars + charPageSize - 1) / charPageSize)
                                + ", " + charFrom + "-" + charTo + " of " + totalChars + " chars"
                                + " — use _page=" + (page + 1) + " for next page)";
                    }
                } else {
                    chunk = "(page " + page + " exceeds content length of " + totalChars + " chars)";
                }

                var item = allItems.get(0);
                pageItems = List.of(new ToolResult.Item(item.title(), item.path(), chunk, item.score()));

                map = buildResponseMap(result, pageItems);
                if (totalChars > charPageSize || page > 1) {
                    map.put("_totalItems", totalItems);
                    map.put("_totalChars", totalChars);
                    map.put("_page", page);
                    map.put("_pageSize", pageSize);
                    map.put("_charsPerPage", charPageSize);
                    map.put("_hasMore", hasMore);
                }
            } else {
                // ── Multi-item mode: item-level pagination ──
                int fromIndex = (page - 1) * pageSize;
                int toIndex = Math.min(fromIndex + pageSize, totalItems);
                hasMore = toIndex < totalItems;

                pageItems = (fromIndex < totalItems) ? allItems.subList(fromIndex, toIndex) : List.of();

                map = buildResponseMap(result, pageItems);
                if (totalItems > pageSize || page > 1) {
                    map.put("_totalItems", totalItems);
                    map.put("_page", page);
                    map.put("_pageSize", pageSize);
                    map.put("_hasMore", hasMore);
                }
            }

            // ── 3. Hard size ceiling (last-resort safety net) ──
            String json = MAPPER.writeValueAsString(map);
            if (json.length() > MAX_JSON_BYTES) {
                json = truncateSnippetsInJson(map);
            }
            return json;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ToolResult", e);
            return "{\"success\":true,\"error\":\"Serialization failed\"}";
        }
    }

    /** Build the common response map from a result and its (already-sliced) page items. */
    private static Map<String, Object> buildResponseMap(ToolResult result, List<ToolResult.Item> pageItems) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", result.success());
        map.put("toolName", result.toolName());
        if (result.error() != null && !result.error().isEmpty()) {
            map.put("error", result.error());
        }
        if (!pageItems.isEmpty()) {
            map.put(
                    "items",
                    pageItems.stream().map(ToolRegistryMcpAdapter::itemToMap).toList());
        }
        map.put("itemCount", pageItems.size());
        return map;
    }

    /** Convert a single Item to a mutable map for serialization. */
    private static Map<String, Object> itemToMap(ToolResult.Item i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", i.title());
        m.put("path", i.path());
        m.put("snippet", i.snippet());
        m.put("score", i.score());
        return m;
    }

    /**
     * Last-resort safety net: truncate every item's snippet to 300 chars and re-serialize.
     * Only reached when character-level pagination is not enough (e.g. a single huge
     * snippet with hundreds of thousands of chars). After truncation, re-checks size;
     * if still exceeds {@link #MAX_JSON_BYTES}, replaces all snippets with a placeholder.
     */
    @SuppressWarnings("unchecked")
    private static String truncateSnippetsInJson(Map<String, Object> map) throws JsonProcessingException {
        final int maxChars = 300;
        List<Map<String, Object>> items = (List<Map<String, Object>>) map.get("items");
        if (items != null) {
            for (Map<String, Object> item : items) {
                String snippet = (String) item.get("snippet");
                if (snippet != null && snippet.length() > maxChars) {
                    item.put(
                            "snippet",
                            snippet.substring(0, maxChars)
                                    + "\n... (truncated — use query_element or _page for full content)");
                }
            }
            // Aggressive fallback: still too large → drop snippet content entirely
            String json = MAPPER.writeValueAsString(map);
            if (json.length() > MAX_JSON_BYTES) {
                for (Map<String, Object> item : items) {
                    item.put("snippet", "(truncated — use query_element or _page for full content)");
                }
                return MAPPER.writeValueAsString(map);
            }
        }
        return MAPPER.writeValueAsString(map);
    }

    /** Extract an integer parameter from JsonNode args, returning defaultValue if absent or invalid. */
    private static int extractInt(JsonNode args, String key, int defaultValue) {
        if (args == null || !args.has(key)) return defaultValue;
        JsonNode node = args.get(key);
        if (node.isInt()) return node.asInt();
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
}
