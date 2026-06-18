package com.gsim.context.session;

import com.gsim.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ContextSession JSONL 持久化存储。
 *
 * <p>文件路径: data/worlds/{world}/context/sessions.jsonl
 * <p>线程安全：所有文件写入操作由 per-file lock 保护。
 */
public class ContextSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ContextSessionStore.class);

    private final Path sessionsFile;
    private final Object lock = new Object();

    public ContextSessionStore(Path worldDir) {
        Path contextDir = worldDir.resolve("context");
        this.sessionsFile = contextDir.resolve("sessions.jsonl");
    }

    /**
     * 保存一个 session（追加一行 JSON）。
     * 线程安全：synchronized 保证原子追加。
     */
    public void save(ContextSession session) {
        synchronized (lock) {
            try {
                Files.createDirectories(sessionsFile.getParent());
                String line = JsonUtils.toJsonCompact(session) + "\n";
                Files.writeString(sessionsFile, line, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("Failed to save session {}: {}", session.sessionId(), e.getMessage());
            }
        }
    }

    /**
     * 加载所有 sessions。
     */
    public List<ContextSession> loadAll() {
        List<ContextSession> sessions = new ArrayList<>();
        if (!Files.exists(sessionsFile)) return sessions;

        synchronized (lock) {
            try {
                List<String> lines = Files.readAllLines(sessionsFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    try {
                        ContextSession s = JsonUtils.fromJson(line, ContextSession.class);
                        sessions.add(s);
                    } catch (Exception e) {
                        log.warn("Failed to parse session line: {}", e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.error("Failed to read sessions: {}", e.getMessage());
            }
        }
        return sessions;
    }

    /**
     * 按 sessionId 查找 session。
     */
    public Optional<ContextSession> findById(String sessionId) {
        return loadAll().stream()
                .filter(s -> s.sessionId().equals(sessionId))
                .findFirst();
    }

    /**
     * @deprecated 使用 {@link #findActiveByApiSessionId(String)} 替代。
     *             全局 findActive 无法区分不同 API session。
     */
    @Deprecated
    public Optional<ContextSession> findActive() {
        return loadAll().stream()
                .filter(ContextSession::isActive)
                .findFirst();
    }

    /**
     * 按 apiSessionId 查找活跃 session。
     */
    public Optional<ContextSession> findActiveByApiSessionId(String apiSessionId) {
        String id = (apiSessionId == null || apiSessionId.isBlank()) ? "default" : apiSessionId;
        return loadAll().stream()
                .filter(s -> s.isActive() && s.apiSessionId().equals(id))
                .findFirst();
    }

    /**
     * 查找指定 branch 的活跃 session。
     */
    public Optional<ContextSession> findActiveByBranch(String branchId) {
        return loadAll().stream()
                .filter(s -> s.isActive() && s.branchId().equals(branchId))
                .findFirst();
    }

    /**
     * 查找指定 apiSessionId 和 branchId 的活跃 session。
     */
    public Optional<ContextSession> findActiveByApiSessionAndBranch(String apiSessionId, String branchId) {
        String id = (apiSessionId == null || apiSessionId.isBlank()) ? "default" : apiSessionId;
        return loadAll().stream()
                .filter(s -> s.isActive() && s.apiSessionId().equals(id) && s.branchId().equals(branchId))
                .findFirst();
    }

    /**
     * 全量重写 sessions 文件（用于更新状态）。
     * 线程安全：synchronized 保证原子写入。
     */
    public void rewriteAll(List<ContextSession> sessions) {
        synchronized (lock) {
            try {
                Files.createDirectories(sessionsFile.getParent());
                StringBuilder sb = new StringBuilder();
                for (ContextSession s : sessions) {
                    sb.append(JsonUtils.toJsonCompact(s)).append("\n");
                }
                Files.writeString(sessionsFile, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Failed to rewrite sessions: {}", e.getMessage());
            }
        }
    }

    public Path getSessionsFile() {
        return sessionsFile;
    }
}
