package com.gsim.context.session;

import com.gsim.context.BranchContextRenderer;
import com.gsim.context.summary.ContextSessionSummary;
import com.gsim.data.DataManager;
import com.gsim.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * ContextSession 管理器。
 *
 * <p>职责：
 * <ul>
 *   <li>按 apiSessionId 隔离不同客户端/连接的 ContextSession</li>
 *   <li>第一次请求时自动创建 ContextSession</li>
 *   <li>同 session 后续轮次不重新渲染 BaseContext</li>
 *   <li>reset 关闭旧 session，创建新 session</li>
 * </ul>
 */
public class ContextSessionManager {

    private static final Logger log = LoggerFactory.getLogger(ContextSessionManager.class);

    private final ContextSessionStore sessionStore;
    private final BranchContextRenderer renderer;
    private final DataManager dataManager;
    private final Path worldDir;
    private final Path contextDir;

    private final Map<String, SessionMessageStore> messageStores = new HashMap<>();

    public ContextSessionManager(ContextSessionStore sessionStore,
                                  BranchContextRenderer renderer,
                                  DataManager dataManager,
                                  Path worldDir) {
        this.sessionStore = sessionStore;
        this.renderer = renderer;
        this.dataManager = dataManager;
        this.worldDir = worldDir;
        this.contextDir = worldDir.resolve("context");
    }

    /**
     * 获取或创建活跃 session（按 apiSessionId 隔离）。
     */
    public ContextSession getOrCreateActiveSession(String apiSessionId, String branchId) {
        String apiId = normalizeApiSessionId(apiSessionId);
        if (branchId == null || branchId.isBlank()) {
            branchId = dataManager.getActiveBranch();
        }

        // 按 apiSessionId 查找活跃 session
        Optional<ContextSession> existing = sessionStore.findActiveByApiSessionId(apiId);
        if (existing.isPresent()) {
            ContextSession s = existing.get();
            if (!s.branchId().equals(branchId)) {
                log.info("Branch changed from {} to {}, creating new session", s.branchId(), branchId);
                closeSession(s.sessionId(), "branch changed");
            } else {
                ContextSession touched = s.touch();
                rewrite(s, touched);
                return touched;
            }
        }

        return createSession(apiId, branchId, branchId);
    }

    /**
     * 创建新 ContextSession，渲染 BaseContextSnapshot。
     */
    public ContextSession createSession(String apiSessionId, String branchId, String startNodeId) {
        String apiId = normalizeApiSessionId(apiSessionId);
        String sessionId = apiId + "-ctx-" + IdGenerator.generate("cs");

        // 渲染 BaseContext（仅创建时渲染一次）
        BaseContextSnapshot snapshot = renderer.renderBaseContext(contextDir);

        ContextSession session = new ContextSession(
                apiId, sessionId, branchId, startNodeId, snapshot.id(),
                Instant.now(), Instant.now(), ContextSessionStatus.ACTIVE, null
        );

        sessionStore.save(session);
        log.info("ContextSession created: {} (apiSession: {}, branch: {}, base: {})",
                sessionId, apiId, branchId, snapshot.id());

        return session;
    }

    /**
     * 重置会话 — 关闭旧 session，创建新 session。
     */
    public ContextSession resetSession(String apiSessionId, String reason) {
        String apiId = normalizeApiSessionId(apiSessionId);

        Optional<ContextSession> existing = sessionStore.findActiveByApiSessionId(apiId);
        if (existing.isPresent()) {
            ContextSession old = existing.get();
            closeSession(old.sessionId(), reason != null ? reason : "Manual reset");
        }

        String branchId = dataManager.getActiveBranch();
        return createSession(apiId, branchId, branchId);
    }

    /**
     * 关闭一个 ContextSession。
     */
    public ContextSession closeSession(String contextSessionId, String reason) {
        Optional<ContextSession> existing = sessionStore.findById(contextSessionId);
        if (existing.isEmpty()) return null;

        ContextSession old = existing.get();
        String summary = reason != null ? reason : "Session closed";
        ContextSession closed = old.withSummary(summary).withStatus(ContextSessionStatus.CLOSED);

        List<ContextSession> all = sessionStore.loadAll();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).sessionId().equals(contextSessionId)) {
                all.set(i, closed);
            }
        }
        sessionStore.rewriteAll(all);

        messageStores.remove(contextSessionId);

        log.info("ContextSession closed: {}", contextSessionId);
        return closed;
    }

    /**
     * 按 apiSessionId 获取活跃 session。
     */
    public Optional<ContextSession> getActiveSession(String apiSessionId) {
        return sessionStore.findActiveByApiSessionId(normalizeApiSessionId(apiSessionId));
    }

    /**
     * 渲染给 LLM 的完整上下文（baseContext + session messages）。
     * 不重新生成 BaseContext — 只读取 session.baseContextId 对应的 .md 文件。
     */
    public String renderForLlm(String apiSessionId, String currentUserInput) {
        String apiId = normalizeApiSessionId(apiSessionId);
        Optional<ContextSession> active = sessionStore.findActiveByApiSessionId(apiId);

        if (active.isEmpty()) {
            // 自动创建 session
            ContextSession session = getOrCreateActiveSession(apiId, dataManager.getActiveBranch());
            String baseMarkdown = loadBaseContextMarkdown(session.baseContextId());
            List<SessionMessage> msgs = getSessionMessages(session.sessionId());
            return renderer.renderSessionContext(baseMarkdown, msgs, currentUserInput);
        }

        ContextSession session = active.get();
        // 只读取已保存的 BaseContext markdown，不重新渲染
        String baseMarkdown = loadBaseContextMarkdown(session.baseContextId());
        List<SessionMessage> msgs = getSessionMessages(session.sessionId());
        return renderer.renderSessionContext(baseMarkdown, msgs, currentUserInput);
    }

    /**
     * 获取 BaseContext markdown（不生成新快照）。
     */
    public String getBaseContextMarkdown(String apiSessionId) {
        Optional<ContextSession> active = sessionStore.findActiveByApiSessionId(
                normalizeApiSessionId(apiSessionId));
        if (active.isPresent()) {
            return loadBaseContextMarkdown(active.get().baseContextId());
        }
        // 无活跃 session 时返回 null
        return null;
    }

    /**
     * 追加消息到当前 session。
     */
    public void appendMessage(String contextSessionId, SessionMessage message) {
        SessionMessageStore store = messageStores.computeIfAbsent(contextSessionId,
                k -> new SessionMessageStore(worldDir, contextSessionId));
        store.append(message);
    }

    /**
     * 获取当前 session 的消息。
     */
    public List<SessionMessage> getSessionMessages(String contextSessionId) {
        SessionMessageStore store = messageStores.get(contextSessionId);
        if (store != null) return store.getAll();
        store = new SessionMessageStore(worldDir, contextSessionId);
        messageStores.put(contextSessionId, store);
        return store.getAll();
    }

    /**
     * 生成会话摘要。
     */
    public ContextSessionSummary generateSummary(String contextSessionId) {
        List<SessionMessage> msgs = getSessionMessages(contextSessionId);
        Optional<ContextSession> session = sessionStore.findById(contextSessionId);

        StringBuilder sb = new StringBuilder();
        sb.append("Conversation with ").append(msgs.size()).append(" messages.");

        List<String> userMsgs = msgs.stream()
                .filter(m -> "user".equals(m.role()))
                .map(SessionMessage::content)
                .toList();

        if (!userMsgs.isEmpty()) {
            sb.append(" User topics: ");
            for (int i = 0; i < Math.min(3, userMsgs.size()); i++) {
                String t = userMsgs.get(i);
                if (t.length() > 80) t = t.substring(0, 77) + "...";
                sb.append(t).append("; ");
            }
        }

        return new ContextSessionSummary(
                contextSessionId,
                session.map(ContextSession::branchId).orElse(""),
                sb.toString().trim(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private String loadBaseContextMarkdown(String baseContextId) {
        if (baseContextId == null) return "";
        Path file = contextDir.resolve("base_contexts").resolve(baseContextId + ".md");
        try {
            return java.nio.file.Files.readString(file);
        } catch (Exception e) {
            log.warn("Failed to load BaseContext {}: {}", baseContextId, e.getMessage());
            // 重新渲染作为回退
            return renderer.renderBaseContext(contextDir).markdown();
        }
    }

    private String normalizeApiSessionId(String apiSessionId) {
        return (apiSessionId == null || apiSessionId.isBlank()) ? "default" : apiSessionId;
    }

    private void rewrite(ContextSession old, ContextSession updated) {
        List<ContextSession> all = sessionStore.loadAll();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).sessionId().equals(old.sessionId())) {
                all.set(i, updated);
            }
        }
        sessionStore.rewriteAll(all);
    }
}
