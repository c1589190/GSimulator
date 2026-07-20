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

        ToolCall call = new ToolCall(name, jsonNodeToParams(args));
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

        return toolResultToJson(result);
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
     */
    static String schemaForTool(AgentTool tool) {
        Map<String, Object> params = tool.getParameters();
        if (params == null) {
            return WIDE_SCHEMA;
        }
        try {
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
     */
    static String toolResultToJson(ToolResult result) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", result.success());
            map.put("toolName", result.toolName());
            if (result.error() != null && !result.error().isEmpty()) {
                map.put("error", result.error());
            }
            if (!result.items().isEmpty()) {
                map.put(
                        "items",
                        result.items().stream()
                                .map(i -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("title", i.title());
                                    item.put("path", i.path());
                                    item.put("snippet", i.snippet());
                                    item.put("score", i.score());
                                    return item;
                                })
                                .toList());
                map.put("itemCount", result.items().size());
            }
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ToolResult", e);
            return "{\"success\":true,\"error\":\"Serialization failed\"}";
        }
    }
}
