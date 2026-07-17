package com.gsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;

/**
 * MCP (Model Context Protocol) JSON-RPC 2.0 server over stdio for GSimulator.
 *
 * <p>Protocol: line-delimited JSON-RPC 2.0 (one JSON object per line).
 * Supports initialize, tools/list, tools/call, and notifications/initialized.
 *
 * <p>Usage:
 * <pre>
 *   GsimMcpServer server = new GsimMcpServer(worldsDir);
 *   server.start();  // blocking, reads from stdin
 * </pre>
 */
public class GsimMcpServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(GsimMcpServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GsimMcpToolRegistry registry;
    private volatile boolean running = true;

    public GsimMcpServer(Path worldsDir) {
        this(worldsDir, null);
    }

    public GsimMcpServer(Path worldsDir, Path importDir) {
        this.registry = new GsimMcpToolRegistry(worldsDir, importDir);
    }

    /** Returns the tool registry (for merging with other registries). */
    public GsimMcpToolRegistry getRegistry() { return registry; }

    @Override
    public void run() {
        start();
    }

    /** Start the MCP server — blocking, reads from stdin, writes to stdout. */
    public void start() {
        log.info("GSim MCP server starting on stdio...");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out), true)) {

            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode req = MAPPER.readTree(line);
                    String method = req.has("method") ? req.get("method").asText() : "";
                    String id = req.has("id") && !req.get("id").isNull()
                        ? req.get("id").toString() : null;

                    switch (method) {
                        case "initialize"        -> out.println(jsonRpc(id, handleInitialize(req)));
                        case "notifications/initialized" -> { /* no-op */ }
                        case "tools/list"        -> out.println(jsonRpc(id, handleToolsList()));
                        case "tools/call"        -> out.println(jsonRpc(id, handleToolCall(req)));
                        default -> out.println(jsonRpcError(id, -32601, "Method not found: " + method));
                    }
                } catch (Exception e) {
                    log.error("MCP error", e);
                    out.println(jsonRpcError(null, -32700, "Parse error: " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            log.error("MCP I/O error", e);
        }
        log.info("GSim MCP server stopped");
    }

    /** Standalone entry point: java ... com.gsim.mcp.GsimMcpServer <worldsDir> [importDir] */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: gsim-mcp <worldsDir> [importDir]");
            System.exit(1);
        }
        Path worldsDir = Path.of(args[0]);
        Path importDir = args.length >= 2 ? Path.of(args[1]) : null;
        GsimMcpServer server = new GsimMcpServer(worldsDir, importDir);
        server.start();
    }

    // ── Protocol handlers ───────────────────────────────────

    private JsonNode handleInitialize(JsonNode req) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");

        ObjectNode capabilities = MAPPER.createObjectNode();
        capabilities.set("tools", MAPPER.createObjectNode());
        result.set("capabilities", capabilities);

        ObjectNode serverInfo = MAPPER.createObjectNode();
        serverInfo.put("name", "GSimulator-MCP");
        serverInfo.put("version", "0.1.0");
        result.set("serverInfo", serverInfo);

        return result;
    }

    private JsonNode handleToolsList() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = MAPPER.createArrayNode();

        for (var tool : registry.all()) {
            try {
                ObjectNode t = MAPPER.createObjectNode();
                t.put("name", tool.name());
                t.put("description", tool.description());
                t.set("inputSchema", MAPPER.readTree(tool.schema()));
                tools.add(t);
            } catch (Exception e) {
                log.warn("Failed to serialize tool schema for {}", tool.name(), e);
            }
        }

        result.set("tools", tools);
        return result;
    }

    private JsonNode handleToolCall(JsonNode req) throws Exception {
        JsonNode params = req.get("params");
        if (params == null) throw new IllegalArgumentException("Missing params in tools/call");

        String toolName = params.get("name").asText();
        JsonNode args = params.has("arguments") ? params.get("arguments") : MAPPER.createObjectNode();

        String rawResult = registry.execute(toolName, args);

        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode textPart = MAPPER.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", rawResult);
        content.add(textPart);
        result.set("content", content);
        return result;
    }

    // ── JSON-RPC protocol helpers ───────────────────────────

    private String jsonRpc(String id, JsonNode result) {
        try {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) response.set("id", MAPPER.getNodeFactory().numberNode(
                Long.parseLong(id.replace("\"", ""))));
            else response.putNull("id");
            response.set("result", result);
            return MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            return jsonRpcError(null, -32603, "Internal error");
        }
    }

    private String jsonRpcError(String id, int code, String message) {
        try {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) response.set("id", MAPPER.getNodeFactory().numberNode(
                Long.parseLong(id.replace("\"", ""))));
            else response.putNull("id");
            ObjectNode err = MAPPER.createObjectNode();
            err.put("code", code);
            err.put("message", message);
            response.set("error", err);
            return MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
    }
}
