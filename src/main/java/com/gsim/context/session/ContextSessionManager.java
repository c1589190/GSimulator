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
 *   <li>第一次 chat/sim/run 自动创建 ContextSession</li>
 *   <li>同 session 后续轮次不重新渲染 BaseContext</li>
 *   <li>reset 关闭旧 session，创建新 session</li>
 *   <li>active branch 改变时创建新 session</li>
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
     * 获取或创建活跃 session。
     * apiSessionId 通常是 InteractionSession 的 sessionId。
     */
    public ContextSession getOrCreateActiveSession(String apiSessionId, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            branchId = dataManager.getActiveBranch();
        }

        // 查找已有活跃 session
        Optional<ContextSession> existing = sessionStore.findActive();
        if (existing.isPresent()) {
            ContextSession s = existing.get();
            // 检查 branch 是否一致
            if (!s.branchId().equals(branchId)) {
                log.info("Branch changed from {} to {}, creating new session", s.branchId(), branchId);
                closeSession(s.sessionId(), "branch changed");
            } else {
                // 更新最后活跃时间
                ContextSession touched = s.touch();
                rewrite(s, touched);
                return touched;
            }
        }

        // 创建新 session
        return createSession(apiSessionId, branchId, branchId);
    }

    /**
     * 创建新 ContextSession，渲染 BaseContextSnapshot。
     */
    public ContextSession createSession(String apiSessionId, String branchId, String startNodeId) {
        String sessionId = apiSessionId + "-ctx-" + IdGenerator.generate("cs");

        // 渲染 BaseContext
        BaseContextSnapshot snapshot = renderer.renderBaseContext(contextDir);

        ContextSession session = new ContextSession(
                sessionId, branchId, startNodeId, snapshot.id(),
                Instant.now(), Instant.now(), ContextSessionStatus.ACTIVE, null
        );

        sessionStore.save(session);
        log.info("ContextSession created: {} (branch: {}, base: {})",
                sessionId, branchId, snapshot.id());

        return session;
    }

    /**
     * 重置会话 — 关闭旧 session，创建新 session。
     */
    public ContextSession resetSession(String apiSessionId, String reason) {
        Optional<ContextSession> existing = sessionStore.findActive();
        String oldSummary = null;

        if (existing.isPresent()) {
            ContextSession old = existing.get();
            oldSummary = reason != null ? reason : "Manual reset";
            closeSession(old.sessionId(), oldSummary);
        }

        String branchId = dataManager.getActiveBranch();
        return createSession(apiSessionId, branchId, branchId);
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
            // 关闭其他 ACTIVE 状态
            if (all.get(i).isActive() && !all.get(i).sessionId().equals(contextSessionId)) {
                all.set(i, all.get(i).withStatus(ContextSessionStatus.CLOSED));
            }
        }
        sessionStore.rewriteAll(all);

        // 清理 message store
        messageStores.remove(contextSessionId);

        log.info("ContextSession closed: {}", contextSessionId);
        return closed;
    }

    /**
     * 获取活跃 session。
     */
    public Optional<ContextSession> getActiveSession(String apiSessionId) {
        return sessionStore.findActive();
    }

    /**
     * 渲染给 LLM 的完整上下文。
     */
    public String renderForLlm(String apiSessionId, String currentUserInput) {
        Optional<ContextSession> active = sessionStore.findActive();
        if (active.isEmpty()) {
            // 自动创建
            ContextSession session = getOrCreateActiveSession(apiSessionId, dataManager.getActiveBranch());
            BaseContextSnapshot snapshot = renderer.renderBaseContext(contextDir);
            return renderer.renderSessionContext(snapshot.markdown(), session.sessionId(), currentUserInput);
        }

        ContextSession session = active.get();
        // 获取已保存的 BaseContext
        BaseContextSnapshot snapshot = loadBaseContext(session.baseContextId());

        String baseMarkdown = snapshot != null ? snapshot.markdown() : renderer.renderBaseContext(contextDir).markdown();
        return renderer.renderSessionContext(baseMarkdown, session.sessionId(), currentUserInput);
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
        // 尝试从磁盘加载
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

        StringBuilder summary = new StringBuilder();
        summary.append("Conversation with ").append(msgs.size()).append(" messages.");

        List<String> userMsgs = msgs.stream()
                .filter(m -> "user".equals(m.role()))
                .map(SessionMessage::content)
                .toList();

        if (!userMsgs.isEmpty()) {
            summary.append(" User topics: ");
            for (int i = 0; i < Math.min(3, userMsgs.size()); i++) {
                String truncated = userMsgs.get(i);
                if (truncated.length() > 80) truncated = truncated.substring(0, 77) + "...";
                summary.append(truncated).append("; ");
            }
        }

        return new ContextSessionSummary(
                contextSessionId,
                session.map(ContextSession::branchId).orElse(""),
                summary.toString().trim(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private BaseContextSnapshot loadBaseContext(String baseContextId) {
        if (baseContextId == null) return null;
        Path file = contextDir.resolve("base_contexts").resolve(baseContextId + ".md");
        try {
            String markdown = java.nio.file.Files.readString(file);
            return new BaseContextSnapshot(
                    baseContextId, "", "", Instant.now(),
                    markdown, markdown.length(), List.of(), List.of()
            );
        } catch (Exception e) {
            return null;
        }
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
