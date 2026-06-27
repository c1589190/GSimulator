package com.gsim.webui.handlers;

import com.gsim.cache.CacheInfo;
import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
import com.gsim.cache.CachesManager;
import com.gsim.commands.ChatCommand;
import com.gsim.session.NodeStatus;
import com.gsim.session.NodeType;
import com.gsim.session.SessionNode;
import com.gsim.session.SessionPool;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
    private final Consumer<CacheSession> activeCacheSetter;
    private final Path worldsDir;
    private final Supplier<String> worldId;
    private final CachesManager cachesManager;
    private final SessionPool sessionPool;
    private final AtomicInteger taskCounter = new AtomicInteger(0);
    private volatile String currentTaskId;

    public ChatApiHandler(ChatCommand chatCommand,
                          Supplier<WorldInformation> worldInfo,
                          Supplier<CacheSession> activeCache,
                          Consumer<CacheSession> activeCacheSetter,
                          Path worldsDir,
                          Supplier<String> worldId,
                          CachesManager cachesManager,
                          SessionPool sessionPool) {
        this.chatCommand = chatCommand;
        this.worldInfo = worldInfo;
        this.activeCache = activeCache;
        this.activeCacheSetter = activeCacheSetter;
        this.worldsDir = worldsDir;
        this.worldId = worldId;
        this.cachesManager = cachesManager;
        this.sessionPool = sessionPool;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            // ── 对话管理路由（Task 3）──
            if (path.equals("/chat/conversations")) {
                if ("GET".equalsIgnoreCase(method)) {
                    handleListConversations(exchange);
                } else if ("POST".equalsIgnoreCase(method)) {
                    handleCreateConversation(exchange);
                } else {
                    HandlerUtils.sendError(exchange, 405, "Method not allowed");
                }
                return;
            }
            if (path.startsWith("/chat/conversations/")) {
                String rest = path.substring("/chat/conversations/".length());
                if (rest.endsWith("/load") && "POST".equalsIgnoreCase(method)) {
                    String sessionId = rest.substring(0, rest.length() - "/load".length());
                    handleLoadConversation(exchange, sessionId);
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    handleDeleteConversation(exchange, rest);
                } else {
                    HandlerUtils.sendError(exchange, 405, "Method not allowed");
                }
                return;
            }

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
                case "/chat/upload" -> {
                    if ("POST".equalsIgnoreCase(method)) {
                        handleUpload(exchange);
                    } else {
                        HandlerUtils.sendError(exchange, 405, "Method not allowed");
                    }
                }
                case "/chat/context-bar" -> {
                    if ("GET".equalsIgnoreCase(method)) {
                        handleContextBar(exchange);
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
                case "/chat/status", "/api/chat/status" -> {
                    if ("GET".equalsIgnoreCase(method)) {
                        handleChatStatus(exchange);
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
        String taskId = "chat-" + taskCounter.incrementAndGet();
        this.currentTaskId = taskId;
        Thread.startVirtualThread(() -> {
            try {
                chatCommand.execute(List.of(message));
            } catch (Exception e) {
                log.error("[ChatApi] Chat execution error: {}", e.getMessage(), e);
            } finally {
                if (taskId.equals(this.currentTaskId)) {
                    this.currentTaskId = null;
                }
            }
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sessionId", worldId.get());
        resp.put("taskId", taskId);
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
                msg.put("msgId", "msg-" + messages.size());
                msg.put("id", "msg-" + messages.size());  // MessageStore 期望 id 字段
                msg.put("role", role);
                msg.put("type", "assistant".equals(role) ? "chat_assistant" :
                        "user".equals(role) ? "chat_user" : "chat_" + role);
                msg.put("content", m.getOrDefault("content", ""));
                if (m.containsKey("tool_calls")) {
                    msg.put("tool_calls", m.get("tool_calls"));
                }
                if (m.containsKey("tool_call_id")) {
                    msg.put("tool_call_id", m.get("tool_call_id"));
                }
                if (m.containsKey("name")) {
                    msg.put("name", m.get("name"));
                }
                messages.add(msg);
            }
        }

        // 叠加当前流式中的部分内容（Task 5c）
        if (session != null) {
            for (SessionNode node : sessionPool.getNodes(session.sessionId())) {
                if (node.type() == NodeType.LLM_STREAMING
                        && (node.payload().get("status") == NodeStatus.STREAMING
                            || node.payload().get("status") == NodeStatus.PENDING)) {
                    String content = sessionPool.getContent(node.nodeId());
                    if (content != null && !content.isBlank()) {
                        Map<String, Object> partial = new LinkedHashMap<>();
                        partial.put("role", "assistant");
                        partial.put("content", content);
                        partial.put("status", "streaming");
                        messages.add(partial);
                    }
                    break;
                }
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sessionId", session != null ? session.sessionId() : worldId.get());
        resp.put("messages", messages);
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    // ── POST /chat/cancel ──

    private void handleCancel(HttpExchange exchange) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (chatCommand != null) {
            chatCommand.cancel();
            resp.put("status", "cancelled");
        } else {
            resp.put("status", "no_op");
        }
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

    // ── GET /api/chat/status (Task 5b) ──

    private void handleChatStatus(HttpExchange exchange) throws IOException {
        WorldInformation wi = worldInfo.get();
        CacheSession session = activeCache.get();
        Map<String, Object> resp = new LinkedHashMap<>();

        // 基本状态
        resp.put("activeSessionId", session != null ? session.sessionId() : null);
        resp.put("worldId", wi != null ? wi.worldId() : worldId.get());
        resp.put("activeNodeId", wi != null ? wi.activeNodeId() : "n0000");

        if (wi != null) {
            resp.put("turn", wi.activeNode().turn());
            resp.put("worldTime", wi.activeNode().worldTime());
        } else {
            resp.put("turn", 0);
            resp.put("worldTime", "");
        }

        // 检查流式状态
        boolean isStreaming = false;
        String streamingNodeId = null;
        String streamingContent = null;
        if (session != null) {
            for (SessionNode node : sessionPool.getNodes(session.sessionId())) {
                if (node.type() == NodeType.LLM_STREAMING) {
                    var status = node.payload().get("status");
                    if (status == NodeStatus.STREAMING || status == NodeStatus.PENDING) {
                        isStreaming = true;
                        streamingNodeId = node.nodeId();
                        streamingContent = sessionPool.getContent(node.nodeId());
                        break;
                    }
                }
            }
        }
        resp.put("isStreaming", isStreaming);
        resp.put("streamingNodeId", streamingNodeId);
        resp.put("streamingContent",
                streamingContent != null && !streamingContent.isBlank() ? streamingContent : null);

        HandlerUtils.sendJson(exchange, 200, resp);
    }

    // ── POST /chat/upload (Task 4b) ──

    private void handleUpload(HttpExchange exchange) throws IOException {
        String filename = HandlerUtils.getQueryParam(
                exchange.getRequestURI().getQuery(), "filename");
        if (filename == null || filename.isBlank()) {
            HandlerUtils.sendJson(exchange, 400, Map.of("error", "filename required"));
            return;
        }
        // 安全检查：防止路径穿越
        filename = Path.of(filename).getFileName().toString();
        byte[] body = exchange.getRequestBody().readAllBytes();
        Path uploadDir = worldsDir.resolve(worldId.get()).resolve("uploads");
        java.nio.file.Files.createDirectories(uploadDir);
        java.nio.file.Files.write(uploadDir.resolve(filename), body);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("filename", filename);
        resp.put("size", body.length);
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    // ── GET /chat/context-bar (Task 4c) ──

    private void handleContextBar(HttpExchange exchange) throws IOException {
        WorldInformation wi = worldInfo.get();
        String html;
        if (wi != null) {
            var active = wi.activeNode();
            html = "<span class=\"text-green-400\">📍</span> "
                + wi.worldId() + " / " + wi.activeNodeId()
                + " <span class=\"text-gray-500\">| Turn " + active.turn()
                + " | " + active.worldTime() + "</span>";
        } else {
            html = "🌱 分支: —";
        }
        HandlerUtils.sendHtml(exchange, 200, html);
    }

    // ── Conversation management (Task 3) ──

    private void handleListConversations(HttpExchange exchange) throws IOException {
        String wid = worldId.get();
        // 只列出主 Agent (orchestrator) 的 cache，子 Agent cache 不在此展示
        List<CacheInfo> caches = cachesManager.listCaches(wid, "orchestrator");
        CacheSession active = activeCache.get();
        String activeSid = active != null ? active.sessionId() : null;

        List<Map<String, Object>> list = new ArrayList<>();
        for (CacheInfo c : caches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", c.sessionId());
            item.put("agentName", c.agentName());
            item.put("agentType", c.agentType());
            item.put("nodeId", c.nodeId());
            item.put("createdAt", c.createdAt());
            item.put("messageCount", c.messageCount());
            item.put("isActive", c.sessionId().equals(activeSid));
            list.add(item);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conversations", list);
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    private void handleCreateConversation(HttpExchange exchange) throws IOException {
        String rawBody = new String(exchange.getRequestBody().readAllBytes());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = rawBody.isBlank() ? Map.of() : JsonUtils.fromJson(rawBody, Map.class);
        String agentName = body != null ? body.getOrDefault("agentName", "Orchestrator").toString() : "Orchestrator";
        String wid = worldId.get();
        String nid = worldInfo.get() != null ? worldInfo.get().activeNodeId() : "n0000";
        CacheSession session = cachesManager.createCache(wid, agentName, nid);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("sessionId", session.sessionId());
        resp.put("agentName", session.agentName());
        HandlerUtils.sendJson(exchange, 201, resp);
    }

    private void handleLoadConversation(HttpExchange exchange, String sessionId) throws IOException {
        String wid = worldId.get();
        CacheSession session = cachesManager.loadCache(wid, sessionId);
        if (session == null) {
            HandlerUtils.sendJson(exchange, 404, Map.of("error", "Conversation not found: " + sessionId));
            return;
        }
        activeCacheSetter.accept(session);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("sessionId", session.sessionId());
        resp.put("messages", session.messages().stream()
                .filter(m -> !"system".equals(m.get("role")))
                .toList());
        resp.put("nodeId", session.nodeId());
        resp.put("agentName", session.agentName());
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    private void handleDeleteConversation(HttpExchange exchange, String sessionId) throws IOException {
        String wid = worldId.get();
        boolean deleted = cachesManager.deleteCache(wid, sessionId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", deleted);
        if (!deleted) resp.put("error", "Conversation not found");
        HandlerUtils.sendJson(exchange, 200, resp);
    }
}
