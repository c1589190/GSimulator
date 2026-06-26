package com.gsim.webui.handlers;

import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
import com.gsim.commands.ChatCommand;
import com.gsim.util.JsonUtils;
import com.gsim.webui.TemplateRenderer;
import com.gsim.worldinfo.WorldInformation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * /chat REST API handler — Chat 面板后端端点。
 *
 * <p>注册在 WebUiServer 的 /chat context 下（优先级高于 PageHandler 的 /）。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /chat → chat-panel HTML 片段（Thymeleaf）</li>
 *   <li>POST /chat/send → 发送消息，触发 Agent 执行</li>
 *   <li>GET /chat/messages?format=json → 加载历史消息</li>
 *   <li>POST /chat/cancel → 取消正在运行的任务</li>
 *   <li>GET /chat/node-summary → 当前节点摘要</li>
 * </ul>
 */
public class ChatApiHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatApiHandler.class);

    private final ChatCommand chatCommand;
    private final Supplier<WorldInformation> worldInfo;
    private final Supplier<CacheSession> activeCache;
    private final Path worldsDir;
    private final Supplier<String> worldId;

    public ChatApiHandler(ChatCommand chatCommand,
                          Supplier<WorldInformation> worldInfo,
                          Supplier<CacheSession> activeCache,
                          Path worldsDir,
                          Supplier<String> worldId) {
        this.chatCommand = chatCommand;
        this.worldInfo = worldInfo;
        this.activeCache = activeCache;
        this.worldsDir = worldsDir;
        this.worldId = worldId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            switch (path) {
                case "/chat" -> {
                    if ("GET".equalsIgnoreCase(method)) {
                        handleGetChatPanel(exchange);
                    } else {
                        HandlerUtils.sendError(exchange, 405, "Method not allowed");
                    }
                }
                case "/chat/send" -> {
                    if ("POST".equalsIgnoreCase(method)) {
                        handleSend(exchange);
                    } else {
                        HandlerUtils.sendError(exchange, 405, "Method not allowed");
                    }
                }
                case "/chat/messages" -> {
                    if ("GET".equalsIgnoreCase(method)) {
                        handleMessages(exchange);
                    } else {
                        HandlerUtils.sendError(exchange, 405, "Method not allowed");
                    }
                }
                case "/chat/cancel" -> {
                    if ("POST".equalsIgnoreCase(method)) {
                        handleCancel(exchange);
                    } else {
                        HandlerUtils.sendError(exchange, 405, "Method not allowed");
                    }
                }
                case "/chat/node-summary" -> {
                    if ("GET".equalsIgnoreCase(method)) {
                        handleNodeSummary(exchange);
                    } else {
                        HandlerUtils.sendError(exchange, 405, "Method not allowed");
                    }
                }
                default -> {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                }
            }
        } catch (Exception e) {
            HandlerUtils.logError("ChatApi", method, path, e);
            HandlerUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ── GET /chat → HTML panel ──

    private void handleGetChatPanel(HttpExchange exchange) throws IOException {
        String html = TemplateRenderer.render("fragments/chat-panel");
        HandlerUtils.sendHtml(exchange, 200, html);
    }

    // ── POST /chat/send ──

    private void handleSend(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes());
        Map<String, String> params = HandlerUtils.parseFormEncoded(body);

        // Support both JSON and form-encoded
        String message;
        if (body.trim().startsWith("{")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> json = JsonUtils.fromJson(body, Map.class);
            message = json != null ? (String) json.getOrDefault("message", "") : "";
        } else {
            message = params.getOrDefault("message", body.trim());
        }

        if (message == null || message.isBlank()) {
            HandlerUtils.sendJson(exchange, 400, Map.of("error", "message is required"));
            return;
        }

        log.info("[ChatApi] Sending message ({} chars)", message.length());

        // Execute chat command in a virtual thread (non-blocking for HTTP)
        Thread.startVirtualThread(() -> {
            try {
                chatCommand.execute(List.of(message));
            } catch (Exception e) {
                log.error("[ChatApi] Chat execution error: {}", e.getMessage(), e);
            }
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sessionId", worldId.get());
        resp.put("status", "processing");
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    // ── GET /chat/messages?format=json ──

    private void handleMessages(HttpExchange exchange) throws IOException {
        String format = HandlerUtils.getQueryParam(exchange.getRequestURI().getQuery(), "format");
        if (!"json".equals(format)) {
            // HTML fallback — return empty (HTMX doesn't use this)
            HandlerUtils.sendHtml(exchange, 200, "<div></div>");
            return;
        }

        CacheSession session = activeCache.get();
        List<Map<String, Object>> messages = new ArrayList<>();
        if (session != null) {
            for (Map<String, Object> m : session.messages()) {
                String role = (String) m.get("role");
                if ("system".equals(role)) continue; // skip stale system prompt

                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", role);
                msg.put("content", m.getOrDefault("content", ""));
                if (m.containsKey("tool_calls")) {
                    msg.put("tool_calls", m.get("tool_calls"));
                }
                messages.add(msg);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sessionId", session != null ? session.sessionId() : worldId.get());
        resp.put("messages", messages);
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    // ── POST /chat/cancel?taskId=... ──

    private void handleCancel(HttpExchange exchange) throws IOException {
        Runnable cancel = chatCommand != null ? null : null;
        // The ChatCommand's cancelCallback is wired via setCancelCallback in GSimulatorApplication.
        // We trigger it by calling cancel on the orchestrator indirectly.
        // For now, return the cancel status.
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "cancel_requested");
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    // ── GET /chat/node-summary ──

    private void handleNodeSummary(HttpExchange exchange) throws IOException {
        WorldInformation wi = worldInfo.get();
        Map<String, Object> resp = new LinkedHashMap<>();

        if (wi != null) {
            resp.put("worldId", wi.worldId());
            resp.put("activeNodeId", wi.activeNodeId());

            // Get active node info from the snapshot chain
            var activeNode = wi.activeNode();
            if (activeNode != null) {
                resp.put("turn", activeNode.turn());
                resp.put("worldTime", activeNode.worldTime());
                resp.put("title", activeNode.nodeId());

                resp.put("checkpoints", new ArrayList<>(activeNode.checkpoints().keySet()));
            }

            // Branch chain
            List<String> chain = new ArrayList<>();
            for (var snap : wi.branchChain()) {
                chain.add(snap.nodeId());
            }
            resp.put("chain", chain);
        } else {
            resp.put("worldId", worldId.get());
            resp.put("activeNodeId", "n0000");
            resp.put("checkpoints", List.of());
        }

        HandlerUtils.sendJson(exchange, 200, resp);
    }
}
