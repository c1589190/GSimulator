package com.gsim.agentlib.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.agentlib.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streamable HTTP MCP (Model Context Protocol) server.
 *
 * <p>Independent from the old HTTP API — runs on its own port (default 8720).
 * Provides two endpoints:
 * <ul>
 *   <li>{@code GET /health} — health check (status, version, tool count)</li>
 *   <li>{@code POST /mcp} — JSON-RPC 2.0 endpoint (initialize, tools/list, tools/call)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   McpHttpServer server = new McpHttpServer(toolRegistry, 8720);
 *   server.start();
 *   // ... server runs in background ...
 *   server.stop();
 * }</pre>
 *
 * <p>External MCP clients connect via {@code http://127.0.0.1:8720/mcp}.
 */
public final class McpHttpServer {

    private static final Logger log = LoggerFactory.getLogger(McpHttpServer.class);
    private static final ObjectMapper MAPPER = JsonUtils.MAPPER;

    // ── JSON-RPC 2.0 error codes ──
    private static final int ERR_PARSE_ERROR = -32700;
    private static final int ERR_INVALID_REQUEST = -32600;
    private static final int ERR_METHOD_NOT_FOUND = -32601;
    private static final int ERR_INVALID_PARAMS = -32602;
    private static final int ERR_INTERNAL = -32603;
    private static final int ERR_TOOL_EXECUTION = -32000;

    private final ToolRegistryMcpAdapter adapter;
    private final int port;
    private HttpServer server;
    private int actualPort;

    /**
     * @param toolRegistry the tool registry to expose via MCP
     * @param port         HTTP listen port (default 8720)
     */
    public McpHttpServer(ToolRegistry toolRegistry, int port) {
        this.adapter = new ToolRegistryMcpAdapter(toolRegistry);
        this.port = port;
    }

    // ── Lifecycle ──────────────────────────────────────────────

    /** Start the HTTP server in background. */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", this::handleHealth);
        server.createContext("/mcp", this::handleMcp);
        server.setExecutor(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mcp-http-", 1).factory()));
        server.start();
        this.actualPort = server.getAddress().getPort();
        log.info("[MCP-HTTP] McpHttpServer started on http://127.0.0.1:{} (mcp=/mcp, health=/health)", actualPort);
    }

    /** Stop the HTTP server. */
    public void stop() {
        if (server != null) {
            server.stop(1);
            log.info("[MCP-HTTP] McpHttpServer stopped");
        }
    }

    public int getPort() {
        return actualPort > 0 ? actualPort : port;
    }

    // ── GET /health ────────────────────────────────────────────

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, errorMap("Method not allowed"));
            return;
        }
        var body = MAPPER.createObjectNode();
        body.put("status", "UP");
        body.put("version", "0.1.0");
        body.put("tools", adapter.all().size());
        sendJson(exchange, 200, body);
    }

    // ── POST /mcp ──────────────────────────────────────────────

    private void handleMcp(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, errorMap("Use POST with JSON-RPC 2.0 body"));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String method = "";
        String id = null;

        try {
            JsonNode req = MAPPER.readTree(body);
            method = req.has("method") ? req.get("method").asText() : "";
            id = req.has("id") && !req.get("id").isNull() ? req.get("id").toString() : null;

            log.info("[MCP-HTTP-REQ] method={}, id={}", method, id);
            log.debug("[MCP-HTTP-REQ] raw={}", body);

            String response;
            switch (method) {
                case "initialize" -> {
                    JsonNode result = buildInitializeResult();
                    response = jsonRpc(id, result);
                }
                case "notifications/initialized" -> {
                    log.debug("[MCP-HTTP] notification: initialized");
                    // MCP notifications have no response — return 204 No Content
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                case "tools/list" -> {
                    JsonNode result = buildToolsList();
                    int toolCount = result.has("tools") ? result.get("tools").size() : 0;
                    log.info("[MCP-HTTP-RES] tools/list, tools={}", toolCount);
                    response = jsonRpc(id, result);
                }
                case "tools/call" -> {
                    String toolName = extractToolName(req);
                    JsonNode args = extractToolArgs(req);
                    log.info("[MCP-HTTP-TOOL] name={}", toolName);
                    try {
                        long startNs = System.nanoTime();
                        String rawResult = adapter.execute(toolName, args);
                        long durationMs = (System.nanoTime() - startNs) / 1_000_000;

                        ObjectNode result = MAPPER.createObjectNode();
                        ArrayNode content = MAPPER.createArrayNode();
                        ObjectNode textPart = MAPPER.createObjectNode();
                        textPart.put("type", "text");
                        textPart.put("text", rawResult);
                        content.add(textPart);
                        result.set("content", content);

                        log.info(
                                "[MCP-HTTP-TOOL-RESULT] name={}, status=success, size={}bytes, duration={}ms",
                                toolName,
                                rawResult.length(),
                                durationMs);
                        response = jsonRpc(id, result);
                    } catch (UnknownToolException e) {
                        log.warn("[MCP-HTTP-TOOL-RESULT] name={}, error=unknown_tool", toolName);
                        response = jsonRpcError(id, ERR_INVALID_PARAMS, "Unknown tool: " + e.getMessage());
                    } catch (IllegalArgumentException e) {
                        log.warn(
                                "[MCP-HTTP-TOOL-RESULT] name={}, error=invalid_args, message={}",
                                toolName,
                                e.getMessage());
                        response = jsonRpcError(id, ERR_INVALID_PARAMS, "Invalid arguments: " + e.getMessage());
                    } catch (Exception e) {
                        log.error("[MCP-HTTP-TOOL-RESULT] name={}, error=execution_error", toolName, e);
                        response = jsonRpcError(
                                id, ERR_TOOL_EXECUTION, "Tool '" + toolName + "' failed: " + e.getMessage());
                    }
                }
                default -> {
                    log.warn("[MCP-HTTP] unknown method '{}'", method);
                    response = jsonRpcError(id, ERR_METHOD_NOT_FOUND, "Method not found: " + method);
                }
            }

            sendJson(exchange, 200, MAPPER.readTree(response));

        } catch (IOException e) {
            log.error("[MCP-HTTP] parse error: {}", e.getMessage());
            sendJson(
                    exchange,
                    400,
                    MAPPER.readTree(jsonRpcError(id, ERR_PARSE_ERROR, "Parse error: " + e.getMessage())));
        }
    }

    // ── MCP method handlers ────────────────────────────────────

    private JsonNode buildInitializeResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        ObjectNode capabilities = MAPPER.createObjectNode();
        capabilities.set("tools", MAPPER.createObjectNode());
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = MAPPER.createObjectNode();
        serverInfo.put("name", "GSimulator-MCP-HTTP");
        serverInfo.put("version", "0.1.0");
        result.set("serverInfo", serverInfo);
        return result;
    }

    private JsonNode buildToolsList() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = MAPPER.createArrayNode();
        for (ToolDef tool : adapter.all()) {
            try {
                ObjectNode t = MAPPER.createObjectNode();
                t.put("name", tool.name());
                t.put("description", tool.description());
                t.set("inputSchema", MAPPER.readTree(tool.schema()));
                tools.add(t);
            } catch (Exception e) {
                log.warn("[MCP-HTTP] Failed to serialize schema for tool '{}'", tool.name(), e);
            }
        }
        result.set("tools", tools);
        return result;
    }

    // ── JSON-RPC serialization ─────────────────────────────────

    private static String jsonRpc(String id, JsonNode result) {
        try {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) {
                response.set("id", MAPPER.readTree(id));
            } else {
                response.putNull("id");
            }
            response.set("result", result);
            return MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            log.error("[MCP-HTTP] Failed to serialize success response", e);
            return jsonRpcError(null, ERR_INTERNAL, "Internal error");
        }
    }

    private static String jsonRpcError(String id, int code, String message) {
        try {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) {
                response.set("id", MAPPER.readTree(id));
            } else {
                response.putNull("id");
            }
            ObjectNode err = MAPPER.createObjectNode();
            err.put("code", code);
            err.put("message", message);
            response.set("error", err);
            return MAPPER.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
    }

    // ── Helpers ────────────────────────────────────────────────

    private static String extractToolName(JsonNode req) {
        JsonNode params = req.get("params");
        if (params != null && params.has("name")) {
            return params.get("name").asText();
        }
        return "<unknown>";
    }

    private static JsonNode extractToolArgs(JsonNode req) {
        JsonNode params = req.get("params");
        if (params != null && params.has("arguments")) {
            JsonNode argsNode = params.get("arguments");
            // Handle double-wrapping
            if (argsNode.isObject()
                    && argsNode.has("arguments")
                    && argsNode.get("arguments").isTextual()) {
                try {
                    return MAPPER.readTree(argsNode.get("arguments").asText());
                } catch (Exception e) {
                    return argsNode;
                }
            }
            if (argsNode.isTextual()) {
                try {
                    return MAPPER.readTree(argsNode.asText());
                } catch (Exception e) {
                    return MAPPER.createObjectNode();
                }
            }
            return argsNode;
        }
        return MAPPER.createObjectNode();
    }

    private static void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static JsonNode errorMap(String message) {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message);
        return err;
    }
}
