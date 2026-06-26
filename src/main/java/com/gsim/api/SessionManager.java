package com.gsim.api;

import com.gsim.app.ApplicationContext;
import com.gsim.interaction.InteractionContext;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session 管理器 — 管理 sessionId → InteractionSession 映射。
 *
 * <p>职责：
 * <ul>
 *   <li>如果请求没有 sessionId，使用 "default"</li>
 *   <li>如果 sessionId 不存在，自动创建新 session</li>
 *   <li>API 请求不再全部共享同一个 session</li>
 * </ul>
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ApplicationContext ctx;
    private final Map<String, InteractionSession> sessions = new ConcurrentHashMap<>();

    public SessionManager(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 获取或创建 session。
     * sessionId 为 null 或 blank 时使用 "default"。
     */
    public InteractionSession getOrCreateSession(String sessionId) {
        String id = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;

        return sessions.computeIfAbsent(id, k -> {
            log.debug("Creating new session: {}", id);
            return createSession(id);
        });
    }

    /**
     * 获取 session，不存在返回 null。
     */
    public InteractionSession getSession(String sessionId) {
        String id = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        return sessions.get(id);
    }

    /**
     * 列出所有 session ID。
     */
    public Set<String> listSessions() {
        return Set.copyOf(sessions.keySet());
    }

    /**
     * 获取 session 数量。
     */
    public int sessionCount() {
        return sessions.size();
    }

    /**
     * 克隆一个新 session。
     * 创建独立的 InteractionContext，共享底层 services。
     */
    private InteractionSession createSession(String sessionId) {
        InteractionContext newContext = new InteractionContext();

        return new InteractionSession(
                newContext,
                ctx.getConfig(),
                ctx.getToolRegistry(),
                ctx.getLlmManager()
        );
    }
}
