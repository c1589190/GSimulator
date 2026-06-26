package com.gsim.commands;

import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * /chat — manual message to the LLM.
 *   /chat <message>                — send a message through the agent loop
 *   /chat history [n]              — show last n messages from cache
 *   /chat clear                    — compress and start new session
 */
public final class ChatCommand {

    private final Path worldsDir;
    private final Supplier<String> worldId;
    private final Supplier<CacheSession> activeCache;

    public ChatCommand(Path worldsDir, Supplier<String> worldId, Supplier<CacheSession> activeCache) {
        this.worldsDir = worldsDir;
        this.worldId = worldId;
        this.activeCache = activeCache;
    }

    public String execute(List<String> args) {
        if (args.isEmpty()) return "Usage: /chat [message|history|clear] ...";
        String sub = args.get(0);
        if ("history".equals(sub)) {
            int n = args.size() > 1 ? Integer.parseInt(args.get(1)) : 10;
            return showHistory(n);
        }
        if ("clear".equals(sub)) {
            return clearSession();
        }
        // default: the whole args is the message
        String message = String.join(" ", args);
        activeCache.get().addMessage(Map.of("role", "user", "content", message));
        CacheStore.save(worldsDir, worldId.get(), activeCache.get());
        return "(message queued, agent will process)";
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
