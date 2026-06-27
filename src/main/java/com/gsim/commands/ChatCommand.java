package com.gsim.commands;

import com.gsim.agent.core.AgentResult;
import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
import com.gsim.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * /chat — manual message to the LLM.
 *   /chat <message>                — send a message through the agent loop
 *   /chat history [n]              — show last n messages from cache
 *   /chat clear                    — compress and start new session
 *
 * <p>支持 ESC 取消：当 agentRunner 异步执行时，监听终端输入中的 ESC 字节，
 * 触发 cancelCallback 中断当前对话及所有 SubAgent。
 */
public final class ChatCommand {

    private static final Logger log = LoggerFactory.getLogger(ChatCommand.class);

    private final Path worldsDir;
    private final Supplier<String> worldId;
    private final Supplier<CacheSession> activeCache;
    private final java.util.function.BiFunction<String, List<LlmMessage>, AgentResult> agentRunner;

    /** ESC / Ctrl+C 取消回调（由 GSimulatorApplication 注入 orchestrator::cancel）。 */
    private volatile Runnable cancelCallback;

    public ChatCommand(Path worldsDir, Supplier<String> worldId, Supplier<CacheSession> activeCache) {
        this(worldsDir, worldId, activeCache, null);
    }

    public ChatCommand(Path worldsDir, Supplier<String> worldId, Supplier<CacheSession> activeCache,
                       java.util.function.BiFunction<String, List<LlmMessage>, AgentResult> agentRunner) {
        this.worldsDir = worldsDir;
        this.worldId = worldId;
        this.activeCache = activeCache;
        this.agentRunner = agentRunner;
    }

    /** 注入取消回调（由 GSimulatorApplication 在 wiring 阶段调用）。 */
    public void setCancelCallback(Runnable cb) {
        this.cancelCallback = cb;
    }

    /** 公开取消方法，供 HTTP API 调用。 */
    public void cancel() {
        Runnable cb = this.cancelCallback;
        if (cb != null) {
            cb.run();
        }
    }

    public String execute(List<String> args) {
        if (args.isEmpty()) return "Usage: /chat [message|history|clear] ...";
        String sub = args.get(0);
        if ("history".equals(sub)) {
            int n = 10;
            if (args.size() > 1) {
                try {
                    n = Integer.parseInt(args.get(1));
                } catch (NumberFormatException e) {
                    return "Invalid number: " + args.get(1) + ". Usage: /chat history [n]";
                }
            }
            return showHistory(n);
        }
        if ("clear".equals(sub)) {
            return clearSession();
        }
        // default: the whole args is the message
        String message = String.join(" ", args);

        // Load prior conversation from cache (skip stale system prompt)
        List<LlmMessage> priorMessages = loadPriorMessages();

        if (agentRunner != null) {
            return executeWithCancelSupport(message, priorMessages);
        }
        return "(message queued, agent will process)";
    }

    /**
     * 在后台线程执行 agentRunner，同时监听终端 ESC 按键以支持取消。
     *
     * <p>终端被临时设为非 canonical 模式（逐字节读取），完成后恢复。
     * ESC (0x1B) 或 Ctrl+C (0x03) 触发 {@link #cancelCallback}。
     */
    private String executeWithCancelSupport(String message, List<LlmMessage> priorMessages) {
        var resultFuture = new CompletableFuture<AgentResult>();
        var cancelled = new AtomicBoolean(false);

        // 在 Virtual Thread 中运行 agent
        Thread agentThread = Thread.startVirtualThread(() -> {
            try {
                resultFuture.complete(agentRunner.apply(message, priorMessages));
            } catch (Exception e) {
                resultFuture.completeExceptionally(e);
            }
        });

        // 临时将终端设为非 canonical 模式（逐字节读取，不回显）
        boolean rawMode = setTerminalNonCanonical();

        // ESC 监听线程
        Thread watcher = Thread.startVirtualThread(() -> {
            try {
                InputStream in = System.in;
                while (agentThread.isAlive() && !cancelled.get()) {
                    int avail = in.available();
                    if (avail > 0) {
                        byte[] buf = new byte[Math.min(avail, 16)];
                        int n = in.read(buf);
                        for (int i = 0; i < n; i++) {
                            byte b = buf[i];
                            if (b == 0x1B || b == 0x03) { // ESC or Ctrl+C
                                cancelled.set(true);
                                log.info("[ChatCommand] ESC/Ctrl+C detected, cancelling agent");
                                Runnable cb = cancelCallback;
                                if (cb != null) cb.run();
                                break;
                            }
                        }
                    }
                    Thread.sleep(150);
                }
            } catch (Exception e) {
                // watcher 被中断或 stdin 不可用 — 忽略
            }
        });

        try {
            // 等待 agent 完成
            agentThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 恢复终端
            if (rawMode) restoreTerminal();
        }

        AgentResult result;
        try {
            result = resultFuture.get();
        } catch (Exception e) {
            return "[Agent error] " + e.getMessage();
        }

        if (cancelled.get()) {
            return "[已取消]";
        }

        if (result == null) {
            return "[Agent error] null result";
        }

        if (!result.success()) {
            return "[Agent error] " + result.error();
        }
        // success: streaming already printed content — return empty to avoid duplication
        return "";
    }

    /** 将终端设为非 canonical 模式（逐字节读取，无回显，无缓冲行）。返回 true 表示成功。 */
    private boolean setTerminalNonCanonical() {
        try {
            new ProcessBuilder("stty", "-icanon", "-echo", "min", "0", "time", "1")
                    .inheritIO().start().waitFor();
            return true;
        } catch (Exception e) {
            log.debug("[ChatCommand] stty raw failed: {}", e.getMessage());
            return false;
        }
    }

    /** 恢复终端为正常模式。 */
    private void restoreTerminal() {
        try {
            new ProcessBuilder("stty", "sane").inheritIO().start().waitFor();
        } catch (Exception e) {
            log.debug("[ChatCommand] stty sane failed: {}", e.getMessage());
        }
    }

    /** Load prior messages from cache, skipping the system prompt (regenerated each run). */
    private List<LlmMessage> loadPriorMessages() {
        List<LlmMessage> out = new ArrayList<>();
        CacheSession session = activeCache.get();
        if (session == null) return out;
        for (Map<String, Object> m : session.messages()) {
            String role = (String) m.get("role");
            if ("system".equals(role)) continue;  // stale — regenerated fresh each run
            out.add(LlmMessage.fromCacheMap(m));
        }
        return out;
    }

    private String showHistory(int n) {
        CacheSession session = activeCache.get();
        List<Map<String, Object>> msgs = session.messages();
        int start = Math.max(0, msgs.size() - n);
        StringBuilder sb = new StringBuilder("Last " + n + " messages:\n");
        for (int i = start; i < msgs.size(); i++) {
            Map<String, Object> m = msgs.get(i);
            String role = (String) m.get("role");
            String content = (String) m.get("content");
            if (content == null) content = "[tool_calls]";
            if (content.length() > 80) content = content.substring(0, 80) + "...";
            sb.append("  [").append(role).append("] ").append(content).append("\n");
        }
        return sb.toString();
    }

    private String clearSession() {
        CacheSession old = activeCache.get();
        // Create new session with compression chain
        String summary = "(manual clear — previous session " + old.sessionId() + ")";
        CacheSession fresh = CacheStore.createNew(worldsDir, worldId.get(),
            "Orchestrator", old.nodeId());
        fresh.previousSessionId(old.sessionId());
        fresh.compressionNote(summary);
        CacheStore.save(worldsDir, worldId.get(), fresh);
        return "Cleared. New session: " + fresh.sessionId();
    }
}
