package com.gsim.webui;

import com.gsim.agent.CompositeAgentProgressSink;
import com.gsim.app.ApplicationContext;
import com.gsim.commands.ChatCommand;
import com.gsim.commands.NodeCommand;
import com.gsim.commands.WorldCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
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
 * 最小化 WebSocket 服务器 — 在独立端口上承载真实 CLI REPL 会话。
 *
 * <p>协议：纯 WebSocket 文本帧，无依赖。
 * <p>每个 WebSocket 连接 = 一个独立的 REPL 会话。
 */
public class CliWebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(CliWebSocketServer.class);
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ApplicationContext ctx;
    private final int port;
    private final CompositeAgentProgressSink compositeSink;
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
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        log.info("CLI WebSocket server started on ws://0.0.0.0:{}", port);

        executor.submit(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) log.error("CLI WS accept error: {}", e.getMessage());
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
    // Client handler — REPL loop
    // ══════════════════════════════════════════

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(0);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // WebSocket 握手
            if (!doHandshake(in, out)) {
                client.close();
                return;
            }

            // 发送欢迎消息
            sendWs(out, exec("/help"));

            // REPL 循环
            while (running && !client.isClosed()) {
                String cmd = readWs(in);
                if (cmd == null) break;

                // Escape exit/quit
                String trimmed = cmd.trim().toLowerCase();
                if (trimmed.equals("/exit") || trimmed.equals("/quit")) {
                    sendWs(out, "Goodbye.");
                    break;
                }

                // 创建临时 progress sink 用于实时推送事件
                CliWsProgressSink ps = new CliWsProgressSink(out);
                compositeSink.addSink(ps);

                String output = exec(cmd);

                // 移除 sink 并等待发送完成
                compositeSink.removeSink(ps);
                ps.shutdown();

                sendWs(out, output);
            }
        } catch (Exception e) {
            log.debug("CLI WS client disconnected: {}", e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

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
                // Non-command input → route to /chat
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
    // WebSocket 协议
    // ══════════════════════════════════════════

    private boolean doHandshake(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line;
        String key = null;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.startsWith("Sec-WebSocket-Key:")) {
                key = line.substring(19).trim();
            }
        }
        if (key == null) return false;

        String accept = sha1Base64(key + WS_MAGIC);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return true;
    }

    private String readWs(InputStream in) throws IOException {
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

    private void sendWs(OutputStream out, String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int len = payload.length;

        // FIN + text opcode
        out.write(0x81);

        // length
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
