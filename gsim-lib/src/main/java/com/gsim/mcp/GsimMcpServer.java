package com.gsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * 使用指定的 worlds 目录创建 MCP 服务器。
     *
     * @param worldsDir 世界观数据目录路径
     */
    public GsimMcpServer(Path worldsDir) {
        this(worldsDir, null);
    }

    /**
     * 使用指定的 worlds 目录和导入目录创建 MCP 服务器。
     *
     * @param worldsDir 世界观数据目录路径
     * @param importDir 导入文档目录路径
     */
    public GsimMcpServer(Path worldsDir, Path importDir) {
        this(worldsDir, importDir, null);
    }

    /**
     * 创建 MCP 服务器，指定 worlds 目录、导入目录和 HTTP 基础 URL。
     *
     * @param worldsDir   世界观数据目录路径
     * @param importDir   导入文档目录路径（可为 null）
     * @param httpBaseUrl gsim-app HTTP API 的基础 URL（可为 null，默认 http://127.0.0.1:8710）
     */
    public GsimMcpServer(Path worldsDir, Path importDir, String httpBaseUrl) {
        this.registry = new GsimMcpToolRegistry(worldsDir, importDir, httpBaseUrl);
    }

    /**
     * 使用 ApplicationContext 创建 MCP 服务器。
     * Agent/LLM 工具直接调用内部 Java API，不依赖 HTTP 服务器。
     *
     * @param ctx 应用上下文
     */
    public GsimMcpServer(com.gsim.app.ApplicationContext ctx) {
        this.registry = new GsimMcpToolRegistry(ctx);
    }

    /**
     * 获取工具注册表。
     * <p>返回的注册表可用于与其他工具注册表合并，以扩展 MCP 工具集。
     *
     * @return GSim MCP 工具注册表实例
     */
    public GsimMcpToolRegistry getRegistry() {
        return registry;
    }

    @Override
    public void run() {
        start();
    }

    /**
     * 启动 MCP 服务器。
     * <p>阻塞式运行，从 {@code stdin} 读取 JSON-RPC 2.0 请求，
     * 向 {@code stdout} 写入响应。支持 initialize、tools/list、tools/call
     * 和 notifications/initialized 协议方法。
     */
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
                            ? req.get("id").toString()
                            : null;

                    switch (method) {
                        case "initialize" -> out.println(jsonRpc(id, handleInitialize(req)));
                        case "notifications/initialized" -> {
                            /* no-op */
                        }
                        case "tools/list" -> out.println(jsonRpc(id, handleToolsList()));
                        case "tools/call" -> out.println(jsonRpc(id, handleToolCall(req)));
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

    /**
     * 独立入口点，用于从命令行启动 MCP 服务器。
     *
     * <p>用法: {@code java com.gsim.mcp.GsimMcpServer &lt;worldsDir&gt; [importDir] [httpBaseUrl]}
     *
     * @param args 命令行参数：worldsDir（必需）、importDir（可选）、httpBaseUrl（可选）
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: gsim-mcp <worldsDir> [importDir] [httpBaseUrl]");
            System.exit(1);
        }
        Path worldsDir = Path.of(args[0]);
        Path importDir = args.length >= 2 ? Path.of(args[1]) : null;
        String httpBaseUrl = args.length >= 3 ? args[2] : null;
        GsimMcpServer server = new GsimMcpServer(worldsDir, importDir, httpBaseUrl);
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
            if (id != null)
                response.set("id", MAPPER.getNodeFactory().numberNode(Long.parseLong(id.replace("\"", ""))));
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
            if (id != null)
                response.set("id", MAPPER.getNodeFactory().numberNode(Long.parseLong(id.replace("\"", ""))));
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
