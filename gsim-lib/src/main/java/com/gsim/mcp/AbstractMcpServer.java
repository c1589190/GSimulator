package com.gsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP (Model Context Protocol) JSON-RPC 2.0 over stdio 的可复用抽象基类。
 *
 * <p>处理全部 JSON-RPC 2.0 协议细节（行分隔 JSON、方法分发、错误格式化），
 * 子类只需通过模板方法提供服务器标识和工具注册表。
 *
 * <h3>支持的 MCP 方法</h3>
 * <ul>
 *   <li>{@code initialize} — 返回 protocolVersion、capabilities、serverInfo</li>
 *   <li>{@code notifications/initialized} — 无操作（通知确认）</li>
 *   <li>{@code tools/list} — 返回所有已注册工具的列表及 JSON Schema</li>
 *   <li>{@code tools/call} — 按名称调用工具并返回结果</li>
 * </ul>
 *
 * <h3>子类需实现的模板方法</h3>
 * <ul>
 *   <li>{@link #getServerName()} — 服务器名称</li>
 *   <li>{@link #getServerVersion()} — 语义版本号</li>
 *   <li>{@link #getAllTools()} — 所有工具定义的快照</li>
 *   <li>{@link #executeTool(String, JsonNode)} — 按名称执行工具</li>
 * </ul>
 *
 * <h3>日志</h3>
 * <p>通过 SLF4J/Log4j2 记录完整的 JSON-RPC 请求/响应、工具调用详情和错误信息。
 * 日志前缀：{@code [MCP-REQ]}、{@code [MCP-RES]}、{@code [MCP-TOOL]}、
 * {@code [MCP-ERR]}、{@code [MCP-LIFECYCLE]}。
 *
 * <h3>外部项目使用示例</h3>
 * <pre>{@code
 * McpToolRegistry myTools = new MyToolRegistry();
 * AbstractMcpServer server = new AbstractMcpServer(myTools) {
 *     @Override protected String getServerName() { return "MyApp"; }
 *     @Override protected String getServerVersion() { return "1.0"; }
 * };
 * server.start();  // 阻塞，从 stdin 读取 JSON-RPC 请求
 * }</pre>
 */
public abstract class AbstractMcpServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AbstractMcpServer.class);

    /** 共享的 Jackson ObjectMapper（子类可直接使用）。 */
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /** 服务器运行标志，设为 false 可停止主循环。 */
    protected volatile boolean running = true;

    private final List<McpToolRegistry> registries;

    // ── 构造函数 ─────────────────────────────────────────────

    /**
     * 无参构造器，供完全覆盖所有模板方法的子类使用。
     *
     * <p>使用此构造器的子类必须覆盖 {@link #getAllTools()} 和 {@link #executeTool(String, JsonNode)}。
     * 不需要覆盖 {@link #getServerName()} 和 {@link #getServerVersion()}——它们在所有情况下均由子类实现。
     */
    protected AbstractMcpServer() {
        this.registries = List.of();
    }

    /**
     * 使用单个工具注册表创建 MCP 服务器。
     *
     * @param registry 工具注册表（不可为 null）
     */
    protected AbstractMcpServer(McpToolRegistry registry) {
        this.registries = List.of(registry);
    }

    /**
     * 使用多个工具注册表创建 MCP 服务器。
     * 工具按列表顺序合并；工具调用按列表顺序查找第一个能处理的注册表。
     *
     * @param registries 工具注册表列表（不可为 null，不可为空）
     */
    protected AbstractMcpServer(List<McpToolRegistry> registries) {
        if (registries == null || registries.isEmpty()) {
            throw new IllegalArgumentException("At least one McpToolRegistry is required");
        }
        this.registries = List.copyOf(registries);
    }

    // ── 子类模板方法 ─────────────────────────────────────────

    /**
     * 返回服务器名称，用于 MCP initialize 响应的 {@code serverInfo.name}。
     *
     * @return 人类可读的服务器名称
     */
    protected abstract String getServerName();

    /**
     * 返回语义版本字符串，用于 MCP initialize 响应的 {@code serverInfo.version}。
     *
     * @return 版本号（如 {@code "0.1.0"}）
     */
    protected abstract String getServerVersion();

    /**
     * 返回所有工具定义的快照，用于 MCP {@code tools/list} 响应。
     *
     * <p>每次调用此方法都应返回最新状态（允许运行时动态注册工具）。
     *
     * @return 工具定义列表
     */
    protected List<ToolDef> getAllTools() {
        return registries.stream().flatMap(r -> r.all().stream()).toList();
    }

    /**
     * 按名称执行工具。
     *
     * <p>默认实现按顺序遍历所有注册表，将调用委托给第一个能处理该名称的注册表。
     * 子类可覆盖此方法以实现自定义路由（如前缀匹配）。
     *
     * @param name 工具名称
     * @param args 工具参数的 JSON 树
     * @return JSON 编码的结果字符串
     * @throws IllegalArgumentException 如果所有注册表都不认识该工具
     * @throws Exception                如果工具执行失败
     */
    protected String executeTool(String name, JsonNode args) throws Exception {
        for (McpToolRegistry r : registries) {
            try {
                return r.execute(name, args);
            } catch (IllegalArgumentException e) {
                // 该注册表不认识这个工具，继续尝试下一个
            }
        }
        throw new IllegalArgumentException("Unknown tool: " + name);
    }

    // ── 生命周期 ─────────────────────────────────────────────

    /**
     * 启动 MCP 服务器主循环。
     *
     * <p>阻塞在 {@code stdin} 上，读取行分隔的 JSON-RPC 2.0 请求，
     * 将 JSON-RPC 2.0 响应写入 {@code stdout}。
     * 当调用 {@link #stop()} 或 stdin 到达 EOF 时退出。
     *
     * <p>记录完整的请求/响应/错误日志到 Log4j2。
     */
    public void start() {
        log.info(
                "[MCP-LIFECYCLE] {} v{} starting on stdio, {} registries, {} tools total",
                getServerName(),
                getServerVersion(),
                registries.size(),
                getAllTools().size());

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out), true)) {

            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;

                String method = "";
                String id = null;
                try {
                    JsonNode req = MAPPER.readTree(line);
                    method = req.has("method") ? req.get("method").asText() : "";
                    id = req.has("id") && !req.get("id").isNull()
                            ? req.get("id").toString()
                            : null;

                    log.info("[MCP-REQ] method={}, id={}", method, id);
                    log.debug("[MCP-REQ] method={}, id={}, raw={}", method, id, line);

                    String response;
                    switch (method) {
                        case "initialize" -> {
                            JsonNode result = handleInitialize(req);
                            response = jsonRpc(id, result);
                            log.debug("[MCP-RES] method=initialize, id={}, size={}bytes", id, response.length());
                        }
                        case "notifications/initialized" -> {
                            log.debug("[MCP-REQ] notification acknowledged: initialized");
                            continue; // 通知无响应
                        }
                        case "tools/list" -> {
                            JsonNode result = handleToolsList();
                            int toolCount =
                                    result.has("tools") ? result.get("tools").size() : 0;
                            log.info("[MCP-RES] method=tools/list, id={}, tools={}", id, toolCount);
                            response = jsonRpc(id, result);
                            log.debug("[MCP-RES] method=tools/list, id={}, size={}bytes", id, response.length());
                        }
                        case "tools/call" -> {
                            String toolName = extractToolName(req);
                            JsonNode args = extractToolArgs(req);
                            log.info("[MCP-TOOL] name={}, args={}", toolName, args);

                            try {
                                JsonNode result = handleToolCall(req);
                                response = jsonRpc(id, result);

                                // 提取工具执行结果摘要用于日志
                                String resultText = extractResultText(result);
                                int maxLogLen = Math.min(resultText.length(), 500);
                                log.info(
                                        "[MCP-TOOL-RESULT] name={}, resultSize={}bytes, result={}",
                                        toolName,
                                        resultText.length(),
                                        resultText.substring(0, maxLogLen));
                                log.debug("[MCP-RES] method=tools/call, id={}, size={}bytes", id, response.length());
                            } catch (IllegalArgumentException e) {
                                log.warn(
                                        "[MCP-TOOL] name={}, error=unknown_tool, message={}", toolName, e.getMessage());
                                response = jsonRpcError(id, -32602, "Unknown tool: " + toolName);
                            } catch (Exception e) {
                                log.error(
                                        "[MCP-TOOL] name={}, error=execution_failed, message={}",
                                        toolName,
                                        e.getMessage(),
                                        e);
                                response = jsonRpcError(id, -32000, "Tool error: " + e.getMessage());
                            }
                        }
                        default -> {
                            log.warn("[MCP-REQ] unknown method '{}', id={}", method, id);
                            response = jsonRpcError(id, -32601, "Method not found: " + method);
                        }
                    }

                    out.println(response);
                    log.debug("[MCP-RES] sent {} bytes to stdout", response.length());

                } catch (IOException e) {
                    log.error("[MCP-ERR] parse_error, id={}, message={}", id, e.getMessage(), e);
                    out.println(jsonRpcError(id, -32700, "Parse error: " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            log.error("[MCP-ERR] I/O error in main loop", e);
        }

        log.info("[MCP-LIFECYCLE] {} stopped", getServerName());
    }

    /**
     * 发出停止信号。
     *
     * <p>设置 {@code running = false} 标志，使主循环在下一次迭代时退出。
     * 注意：不关闭 {@link System#in}，因为调用方（如测试框架）可能依赖 stdin 进行进程间通信。
     */
    public void stop() {
        log.info("[MCP-LIFECYCLE] {} stop requested", getServerName());
        running = false;
    }

    @Override
    public void run() {
        start();
    }

    // ── JSON-RPC 方法处理（可被子类覆盖） ──────────────────────

    /**
     * 处理 MCP {@code initialize} 请求。
     *
     * <p>返回 protocolVersion、capabilities 和 serverInfo。
     * 子类可覆盖以添加额外的能力声明（如 prompts、resources）。
     *
     * @param req 原始 JSON-RPC 请求
     * @return initialize 结果节点
     */
    protected JsonNode handleInitialize(JsonNode req) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");

        ObjectNode capabilities = MAPPER.createObjectNode();
        capabilities.set("tools", MAPPER.createObjectNode());
        result.set("capabilities", capabilities);

        ObjectNode serverInfo = MAPPER.createObjectNode();
        serverInfo.put("name", getServerName());
        serverInfo.put("version", getServerVersion());
        result.set("serverInfo", serverInfo);

        return result;
    }

    /**
     * 处理 MCP {@code tools/list} 请求。
     *
     * <p>遍历 {@link #getAllTools()} 并构建包含 name、description 和 inputSchema 的 JSON 数组。
     *
     * @return tools/list 结果节点
     */
    protected JsonNode handleToolsList() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = MAPPER.createArrayNode();

        for (ToolDef tool : getAllTools()) {
            try {
                ObjectNode t = MAPPER.createObjectNode();
                t.put("name", tool.name());
                t.put("description", tool.description());
                t.set("inputSchema", MAPPER.readTree(tool.schema()));
                tools.add(t);
            } catch (Exception e) {
                log.warn("[MCP-TOOL] Failed to serialize schema for tool '{}'", tool.name(), e);
            }
        }

        result.set("tools", tools);
        return result;
    }

    /**
     * 处理 MCP {@code tools/call} 请求。
     *
     * <p>从 params 提取工具名称和参数，调用 {@link #executeTool(String, JsonNode)}，
     * 并将结果包装为 MCP content 格式。
     *
     * @param req 原始 JSON-RPC 请求
     * @return tools/call 结果节点
     * @throws Exception 如果参数缺失或工具执行失败
     */
    protected JsonNode handleToolCall(JsonNode req) throws Exception {
        JsonNode params = req.get("params");
        if (params == null) {
            throw new IllegalArgumentException("Missing params in tools/call");
        }

        String toolName = params.get("name").asText();
        JsonNode args = params.has("arguments") ? params.get("arguments") : MAPPER.createObjectNode();

        String rawResult = executeTool(toolName, args);

        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode textPart = MAPPER.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", rawResult);
        content.add(textPart);
        result.set("content", content);
        return result;
    }

    // ── JSON-RPC 2.0 序列化辅助方法 ───────────────────────────

    /**
     * 构建 JSON-RPC 2.0 成功响应。
     *
     * <p>使用 {@code MAPPER.readTree(id)} 处理 ID 回显，
     * 正确支持字符串和数字两种 ID 类型（JSON-RPC 2.0 规范兼容）。
     *
     * @param id     JSON-RPC 请求 ID（可为 null）
     * @param result 结果 JSON 节点
     * @return JSON-RPC 2.0 响应字符串
     */
    protected static String jsonRpc(String id, JsonNode result) {
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
            log.error("[MCP-ERR] Failed to serialize JSON-RPC success response", e);
            return jsonRpcError(null, -32603, "Internal error");
        }
    }

    /**
     * 构建 JSON-RPC 2.0 错误响应。
     *
     * @param id      JSON-RPC 请求 ID（可为 null）
     * @param code    错误码（标准 JSON-RPC 错误码或自定义码）
     * @param message 人类可读的错误消息
     * @return JSON-RPC 2.0 错误响应字符串
     */
    protected static String jsonRpcError(String id, int code, String message) {
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
        } catch (Exception e) {
            log.error("[MCP-ERR] Failed to serialize JSON-RPC error response", e);
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
    }

    // ── 内部辅助方法 ─────────────────────────────────────────

    /** 从 tools/call 请求中提取工具名称。 */
    private static String extractToolName(JsonNode req) {
        JsonNode params = req.get("params");
        if (params != null && params.has("name")) {
            return params.get("name").asText();
        }
        return "<unknown>";
    }

    /** 从 tools/call 请求中提取工具参数。 */
    private static JsonNode extractToolArgs(JsonNode req) {
        JsonNode params = req.get("params");
        if (params != null && params.has("arguments")) {
            return params.get("arguments");
        }
        return MAPPER.createObjectNode();
    }

    /** 从 tools/call 结果中提取文本内容。 */
    private static String extractResultText(JsonNode result) {
        if (result.has("content")) {
            JsonNode content = result.get("content");
            if (content.isArray() && !content.isEmpty()) {
                JsonNode first = content.get(0);
                if (first.has("text")) {
                    return first.get("text").asText();
                }
            }
        }
        return result.toString();
    }
}
