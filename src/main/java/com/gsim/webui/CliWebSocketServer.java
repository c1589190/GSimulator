package com.gsim.webui;

import com.gsim.agent.CompositeAgentProgressSink;
import com.gsim.app.ApplicationContext;
import com.gsim.commands.ChatCommand;
import com.gsim.commands.NodeCommand;
import com.gsim.commands.WorldCommand;
import com.gsim.session.CliNodeRenderer;
import com.gsim.session.NodeStatus;
import com.gsim.session.NodeType;
import com.gsim.session.SessionNode;
import com.gsim.session.SessionNodeJsonRenderer;
import com.gsim.session.SessionNodeListener;
import com.gsim.session.SessionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 双协议 WebSocket 服务器 — CLI 文本 + Chat JSON。
 *
 * <p>协议通过连接路径区分：
 * <ul>
 *   <li>{@code ws://host:8712/cli} — CLI REPL 文本协议（现有，不变）</li>
 *   <li>{@code ws://host:8712/chat?sessionId=default} — Chat JSON 协议（新增）</li>
 * </ul>
 *
 * <p>默认路径（无 path）走 CLI 协议以保持向后兼容。
 */
public class CliWebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(CliWebSocketServer.class);
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ApplicationContext ctx;
    private final int port;
    private final CompositeAgentProgressSink compositeSink;
    private final SessionPool sessionPool;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    // New command routing
    private WorldCommand worldCommand;
    private NodeCommand nodeCommand;
    private ChatCommand chatCommand;

    public CliWebSocketServer(ApplicationContext ctx, int port,
                               CompositeAgentProgressSink compositeSink) {
        this.ctx = ctx;
        this.port = port;
        this.compositeSink = compositeSink;
        this.sessionPool = ctx.getSessionPool();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        log.info("WebSocket server started on ws://0.0.0.0:{} (CLI + Chat)", port);

        executor.submit(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) log.error("WS accept error: {}", e.getMessage());
                }
            }
        });
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        executor.shutdownNow();
    }

    public int port() { return port; }

    /** Inject new command instances for routing. */
    public void setCommands(WorldCommand wc, NodeCommand nc, ChatCommand cc) {
        this.worldCommand = wc;
        this.nodeCommand = nc;
        this.chatCommand = cc;
    }

    // ══════════════════════════════════════════
    // Connection dispatcher
    // ══════════════════════════════════════════

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(0);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // WebSocket 握手 — 同时解析路径
            HandshakeResult hs = doHandshake(in, out);
            if (hs == null) {
                client.close();
                return;
            }

            String path = hs.path();
            String sessionId = hs.queryParam("sessionId", "default");

            // 按路径分协议
            if ("/chat".equals(path)) {
                handleChatClient(client, in, out, sessionId);
            } else {
                // /cli 或默认 → CLI REPL
                handleCliClient(client, in, out);
            }
        } catch (Exception e) {
            log.debug("WS client disconnected: {}", e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    // ══════════════════════════════════════════
    // CLI protocol (existing, unchanged)
    // ══════════════════════════════════════════

    private void handleCliClient(Socket client, InputStream in, OutputStream out) throws IOException {
        // CLI 渲染器 — WebSocket 不需要 ANSI
        CliNodeRenderer renderer = new CliNodeRenderer(false);

        // 订阅 SessionPool，实时推送节点事件到 WebSocket
        SessionNodeListener poolListener = new SessionNodeListener() {
            @Override
            public void onNodePushed(SessionNode node) {
                String line = renderer.renderPushed(node);
                if (line != null && !line.isEmpty()) {
                    try { sendWs(out, line); } catch (IOException e) { /* ignore */ }
                }
                if (node.type() == NodeType.LLM_STREAMING) {
                    try { sendWs(out, renderer.startLlmStream()); } catch (IOException e) {}
                }
            }

            @Override
            public void onNodeUpdated(SessionNode node, String key, Object newValue) {
                if ("content".equals(key) && newValue instanceof String delta) {
                    String rendered = renderer.renderContentDelta(delta);
                    if (rendered != null) {
                        try { sendWs(out, rendered); } catch (IOException e) {}
                    }
                }
            }

            @Override
            public void onStatusChanged(SessionNode node, NodeStatus oldStatus, NodeStatus newStatus) {
                if (node.type() == NodeType.LLM_STREAMING && newStatus == NodeStatus.DONE) {
                    try { sendWs(out, renderer.endLlmStream(true)); } catch (IOException e) {}
                }
            }
        };
        sessionPool.subscribe("default", poolListener);

        // 发送欢迎消息
        sendWs(out, exec("/help"));

        // REPL 循环
        while (running && !client.isClosed()) {
            String cmd = readWs(in);
            if (cmd == null) break;

            String trimmed = cmd.trim().toLowerCase();
            if (trimmed.equals("/exit") || trimmed.equals("/quit")) {
                sendWs(out, "Goodbye.");
                break;
            }

            // 推入用户输入节点
            sessionPool.pushNode("default", NodeType.USER_INPUT,
                    Map.of("text", cmd, "source", "websocket"));

            // 创建临时 progress sink 用于实时推送事件
            CliWsProgressSink ps = new CliWsProgressSink(out);
            compositeSink.addSink(ps);

            String output = exec(cmd);

            compositeSink.removeSink(ps);
            ps.shutdown();

            sendWs(out, output);
        }
    }

    // ══════════════════════════════════════════
    // Chat JSON protocol (new)
    // ══════════════════════════════════════════

    private void handleChatClient(Socket client, InputStream in, OutputStream out,
                                   String sessionId) throws IOException {
        log.info("Chat WS connected: sessionId={}", sessionId);

        // 1. 回放已有历史节点
        List<SessionNode> existingNodes = sessionPool.getNodes(sessionId);
        if (existingNodes != null && !existingNodes.isEmpty()) {
            String historyJson = SessionNodeJsonRenderer.buildMessage("history",
                    SessionNodeJsonRenderer.renderHistory(existingNodes));
            sendWs(out, historyJson);
        }

        // 1b. 发送当前流式状态（如果存在 — 抗刷新丢失）
        if (existingNodes != null) {
            for (SessionNode node : existingNodes) {
                if (node.type() == NodeType.LLM_STREAMING) {
                    Object status = node.payload().get("status");
                    if (status == NodeStatus.STREAMING || status == NodeStatus.PENDING) {
                        String streamingJson = SessionNodeJsonRenderer.buildMessage("streamingState",
                                Map.of("node", SessionNodeJsonRenderer.render(node)));
                        sendWs(out, streamingJson);
                        break;
                    }
                }
            }
        }

        // 2. 订阅 SessionPool 实时事件
        SessionNodeListener poolListener = new SessionNodeListener() {
            @Override
            public void onNodePushed(SessionNode node) {
                if (!sessionId.equals(node.sessionId())) return;
                String json = SessionNodeJsonRenderer.buildMessage("nodePushed",
                        Map.of("node", SessionNodeJsonRenderer.render(node)));
                try { sendWs(out, json); } catch (IOException e) {}
            }

            @Override
            public void onNodeUpdated(SessionNode node, String key, Object newValue) {
                if (!sessionId.equals(node.sessionId())) return;
                String json = SessionNodeJsonRenderer.buildMessage("nodeUpdated",
                        SessionNodeJsonRenderer.renderUpdate(node.nodeId(), key, newValue));
                try { sendWs(out, json); } catch (IOException e) {}
            }

            @Override
            public void onStatusChanged(SessionNode node, NodeStatus oldStatus, NodeStatus newStatus) {
                if (!sessionId.equals(node.sessionId())) return;
                String json = SessionNodeJsonRenderer.buildMessage("nodeStatusChanged",
                        SessionNodeJsonRenderer.renderStatusChange(node.nodeId(), oldStatus, newStatus));
                try { sendWs(out, json); } catch (IOException e) {}
            }
        };
        sessionPool.subscribe(sessionId, poolListener);

        // 3. 保持连接（read loop — 处理客户端发来的 ping / 控制消息）
        while (running && !client.isClosed()) {
            String msg = readWs(in);
            if (msg == null) break;
            // Chat 协议目前只接收 ping，其他消息忽略
            if ("ping".equals(msg.trim())) {
                sendWs(out, "{\"event\":\"pong\"}");
            }
        }

        sessionPool.unsubscribe(sessionId, poolListener);
    }

    // ══════════════════════════════════════════
    // Command routing (shared)
    // ══════════════════════════════════════════

    private String exec(String cmd) {
        try {
            String trimmed = cmd.trim();
            if (trimmed.startsWith("/")) {
                int spaceIdx = trimmed.indexOf(' ');
                String cmdName = spaceIdx > 0 ? trimmed.substring(1, spaceIdx) : trimmed.substring(1);
                List<String> args = spaceIdx > 0
                    ? Arrays.asList(trimmed.substring(spaceIdx + 1).trim().split("\\s+"))
                    : List.of();

                return switch (cmdName) {
                    case "help" -> helpText();
                    case "world" -> worldCommand != null ? worldCommand.execute(args) : "World command not available.";
                    case "node" -> nodeCommand != null ? nodeCommand.execute(args) : "Node command not available.";
                    case "chat" -> chatCommand != null ? chatCommand.execute(args) : "Chat command not available.";
                    case "exit", "quit" -> "Goodbye.";
                    default -> "Unknown command: /" + cmdName + ". Type /help for available commands.";
                };
            } else {
                if (chatCommand != null) {
                    return chatCommand.execute(List.of(trimmed));
                }
                return "直接输入文本需要通过 /chat 命令发送。输入 /chat <message> 发送消息。";
            }
        } catch (Exception e) {
            return "[错误] " + e.getMessage();
        }
    }

    private String helpText() {
        return """
            Available commands:
              /world [list|create|switch]   — World management
              /node [status|list|goto|create] — Node management
              /chat <message>               — Send message to LLM
              /chat history [n]             — Show last n messages
              /chat clear                   — Clear chat session
              /exit, /quit                  — Exit
              /help                         — Show this help""";
    }

    // ══════════════════════════════════════════
    // WebSocket 协议（shared）
    // ══════════════════════════════════════════

    /** Handshake 结果：已解析的路径和查询参数。 */
    private record HandshakeResult(String path, Map<String, String> queryParams) {
        String queryParam(String key, String defaultVal) {
            return queryParams.getOrDefault(key, defaultVal);
        }
    }

    private HandshakeResult doHandshake(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String requestLine = reader.readLine();
        if (requestLine == null) return null;

        // Parse GET /path?query HTTP/1.1
        String[] parts = requestLine.split(" ");
        String rawPath = parts.length > 1 ? parts[1] : "/";

        String path;
        Map<String, String> queryParams = new java.util.LinkedHashMap<>();
        int qIdx = rawPath.indexOf('?');
        if (qIdx >= 0) {
            path = rawPath.substring(0, qIdx);
            parseQueryString(rawPath.substring(qIdx + 1), queryParams);
        } else {
            path = rawPath;
        }

        // Read headers, find Sec-WebSocket-Key
        String line;
        String key = null;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.startsWith("Sec-WebSocket-Key:")) {
                key = line.substring(19).trim();
            }
        }
        if (key == null) return null;

        String accept = sha1Base64(key + WS_MAGIC);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return new HandshakeResult(path, queryParams);
    }

    private static void parseQueryString(String query, Map<String, String> into) {
        if (query == null || query.isEmpty()) return;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                into.put(k, v);
            }
        }
    }

    /** Read a WebSocket text frame. Returns null on close / EOF. Returns "" for ping. */
    static String readWs(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int opcode = b0 & 0x0f;
        if (opcode == 0x8) return null; // close
        if (opcode == 0x9) return ""; // ping — ignore

        int b1 = in.read();
        if (b1 < 0) return null;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7f;
        if (len == 126) len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
        else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff);
        }

        byte[] mask = new byte[4];
        if (masked) {
            for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
        }

        byte[] payload = new byte[(int) len];
        int total = 0;
        while (total < len) {
            int r = in.read(payload, total, (int) (len - total));
            if (r < 0) return null;
            total += r;
        }

        if (masked) {
            for (int i = 0; i < len; i++) payload[i] ^= mask[i % 4];
        }

        return new String(payload, StandardCharsets.UTF_8);
    }

    /** Send a WebSocket text frame (unmasked, server→client). */
    static void sendWs(OutputStream out, String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int len = payload.length;

        out.write(0x81);  // FIN + text opcode

        if (len < 126) {
            out.write(len);
        } else if (len < 65536) {
            out.write(126);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) out.write((int) ((len >> (i * 8)) & 0xff));
        }

        out.write(payload);
        out.flush();
    }

    private static String sha1Base64(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
