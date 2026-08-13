package com.gsim.commands;

import com.gsim.agent.core.AgentResult;
import com.gsim.core.cache.CacheSession;
import com.gsim.core.cache.CacheStore;
import com.gsim.core.llm.LlmMessage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /chat — manual message to the LLM.
 *   /chat <message>                — send a message through the agent loop
 *   /chat history [n]              — show last n messages from cache
 *   /chat clear                    — compress and start new session
 *
 * <p>支持 ESC 取消：当 agentRunner 异步执行时，通过 JLine Terminal API 监听终端输入。
 * 使用 JLine 原生终端属性管理（不 shell out 到 stty），
 * 确保 JLine 的内部终端状态不被破坏，滚动缓冲区保持正常。
 */
public final class ChatCommand {

    private static final Logger log = LoggerFactory.getLogger(ChatCommand.class);

    private final Path worldsDir;
    private final Supplier<String> worldId;
    private final Supplier<CacheSession> activeCache;
    private java.util.function.Consumer<CacheSession> activeCacheSetter;
    private final java.util.function.BiFunction<String, List<LlmMessage>, AgentResult> agentRunner;

    /** ESC / Ctrl+C 取消回调（由 GSimulatorApplication 注入 orchestrator::cancel）。 */
    private volatile Runnable cancelCallback;

    /** JLine Terminal 引用（用于非阻塞 ESC 检测，避免与 JLine 终端状态冲突）。 */
    private volatile org.jline.terminal.Terminal jlineTerminal;

    public ChatCommand(Path worldsDir, Supplier<String> worldId, Supplier<CacheSession> activeCache) {
        this(worldsDir, worldId, activeCache, null);
    }

    public ChatCommand(
            Path worldsDir,
            Supplier<String> worldId,
            Supplier<CacheSession> activeCache,
            java.util.function.BiFunction<String, List<LlmMessage>, AgentResult> agentRunner) {
        this.worldsDir = worldsDir;
        this.worldId = worldId;
        this.activeCache = activeCache;
        this.agentRunner = agentRunner;
    }

    /**
     * 注入取消回调，用于 ESC/Ctrl+C 时取消正在运行的 Agent。
     * <p>由 {@code GSimulatorApplication} 在 wiring 阶段调用。</p>
     *
     * @param cb 取消回调，通常为 {@code orchestrator::cancel}
     */
    public void setCancelCallback(Runnable cb) {
        this.cancelCallback = cb;
    }

    /**
     * 注入 JLine Terminal 引用，用于非阻塞 ESC 检测。
     * <p>避免直接操作 stty 导致 JLine 内部终端状态被破坏。</p>
     *
     * @param terminal JLine Terminal 实例
     */
    public void setJlineTerminal(org.jline.terminal.Terminal terminal) {
        this.jlineTerminal = terminal;
    }

    /**
     * 注入 activeCache 更新回调，用于创建新会话后更新引用。
     * <p>由 {@code GSimulatorApplication} 在 wiring 阶段调用。</p>
     *
     * @param setter 接受新 {@link CacheSession} 的回调
     */
    public void setActiveCacheSetter(java.util.function.Consumer<CacheSession> setter) {
        this.activeCacheSetter = setter;
    }

    /**
     * 取消当前正在运行的 Agent 操作。
     * <p>供 HTTP API 及 ESC 监听线程调用。</p>
     */
    public void cancel() {
        Runnable cb = this.cancelCallback;
        if (cb != null) {
            cb.run();
        }
    }

    /**
     * 执行 /chat 命令，根据子命令分发到对应处理逻辑。
     *
     * @param args 命令行参数列表，第一个元素为子命令（history / clear）或消息内容
     * @return 命令执行结果文本
     */
    public String execute(List<String> args) {
        if (args.isEmpty()) return "Usage: /chat [message|history|clear] ...";
        String sub = args.get(0);
        if ("history".equals(sub)) {
            CacheSession session = activeCache.get();
            if (session == null) {
                return "没有活跃的对话缓存。请先在启动时选择一个 Orchestrator 缓存，或使用 /chat clear 创建新会话。";
            }
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

        // 必须存在活跃缓存才能对话
        CacheSession session = activeCache.get();
        if (session == null) {
            return "没有活跃的对话缓存。请先在启动时选择一个 Orchestrator 缓存，或使用 /chat clear 创建新会话。";
        }

        // Load prior conversation from cache (skip stale system prompt)
        List<LlmMessage> priorMessages = loadPriorMessages();

        if (agentRunner != null) {
            return executeWithCancelSupport(message, priorMessages);
        }
        return "(message queued, agent will process)";
    }

    /**
     * 在后台线程执行 agentRunner，同时监听终端 ESC/Ctrl+C 以支持取消。
     *
     * <p>使用 JLine Terminal API 管理终端输入（不 shell out 到 stty），
     * 确保 JLine 的内部终端状态不被破坏，滚动缓冲区保持正常。
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

        // ESC 监听线程 — 通过 JLine Terminal 读取（不修改终端属性，不与 stty 冲突）
        final org.jline.terminal.Terminal term = this.jlineTerminal;
        Thread watcher = Thread.startVirtualThread(() -> {
            try {
                while (agentThread.isAlive() && !cancelled.get()) {
                    if (term != null) {
                        // 非阻塞轮询 JLine 终端输入
                        int ch = term.reader().read(150);
                        if (ch == 0x1B || ch == 0x03) { // ESC or Ctrl+C
                            cancelled.set(true);
                            log.info("[ChatCommand] ESC/Ctrl+C detected (JLine), cancelling agent");
                            Runnable cb = cancelCallback;
                            if (cb != null) cb.run();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // watcher 被中断或终端不可用 — 忽略
            }
        });

        try {
            // 等待 agent 完成
            agentThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    /** Load prior messages from cache. System prompt (if present) is loaded as-is —
     *  it is static content written once at cache creation time. */
    private List<LlmMessage> loadPriorMessages() {
        List<LlmMessage> out = new ArrayList<>();
        CacheSession session = activeCache.get();
        if (session == null) return out;
        for (Map<String, Object> m : session.messages()) {
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
        CacheSession fresh = CacheStore.createNew(worldsDir, worldId.get(), "Orchestrator", old.nodeId());
        fresh.previousSessionId(old.sessionId());
        fresh.compressionNote(summary);
        CacheStore.save(worldsDir, fresh);

        // 更新活跃缓存引用，确保后续消息写入新缓存
        if (activeCacheSetter != null) {
            activeCacheSetter.accept(fresh);
        }
        return "Cleared. New session: " + fresh.sessionId();
    }
}
