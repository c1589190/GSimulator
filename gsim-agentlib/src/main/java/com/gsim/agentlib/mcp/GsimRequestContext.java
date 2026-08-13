package com.gsim.agentlib.mcp;

/**
 * Per-request shared context for MCP tool execution.
 *
 * <p>Holds the {@code worldId} extracted from the current MCP request, making it
 * available to any tool via {@link #worldId()} without requiring each tool to
 * extract and validate it from {@link com.gsim.agentlib.tool.ToolCall} parameters.
 *
 * <p>The adapter sets the context before tool execution and clears it afterward.
 * Tools called outside the MCP path (CLI, agent-internal) see {@code null} and
 * should fall back to {@code call.param("worldId")}.
 *
 * <h3>Usage in tools</h3>
 * <pre>{@code
 * String worldId = GsimRequestContext.worldId();
 * if (worldId == null) {
 *     worldId = call.param("worldId");
 *     if (worldId == null || worldId.isBlank()) {
 *         return ToolResult.fail(name(), "worldId is required");
 *     }
 * }
 * }</pre>
 *
 * <p>Based on {@link ThreadLocal} — safe for the synchronous tool execution model
 * where one request occupies one thread for its lifetime.
 */
public final class GsimRequestContext {

    private static final ThreadLocal<String> CURRENT_WORLD_ID = new ThreadLocal<>();

    private GsimRequestContext() {}

    /** Set the worldId for the current request (called by the MCP adapter). */
    public static void setWorldId(String worldId) {
        CURRENT_WORLD_ID.set(worldId);
    }

    /** Returns the worldId for the current request, or {@code null} if not in an MCP context. */
    public static String worldId() {
        return CURRENT_WORLD_ID.get();
    }

    /** Clears the context (called by the MCP adapter after tool execution). */
    public static void clear() {
        CURRENT_WORLD_ID.remove();
    }
}
