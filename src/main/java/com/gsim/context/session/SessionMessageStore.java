package com.gsim.context.session;

import com.gsim.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ContextSession 消息存储 — 内存 + JSONL 持久化。
 *
 * <p>文件路径: data/worlds/{world}/context/session_messages/{contextSessionId}.jsonl
 */
public class SessionMessageStore {

    private static final Logger log = LoggerFactory.getLogger(SessionMessageStore.class);

    private final Path messagesDir;
    private final String contextSessionId;
    private final List<SessionMessage> messages = new CopyOnWriteArrayList<>();

    public SessionMessageStore(Path worldDir, String contextSessionId) {
        this.messagesDir = worldDir.resolve("context").resolve("session_messages");
        this.contextSessionId = contextSessionId;
        loadFromDisk();
    }

    private Path messagesFile() {
        return messagesDir.resolve(contextSessionId + ".jsonl");
    }

    /**
     * 追加一条消息。
     */
    public void append(SessionMessage message) {
        messages.add(message);
        try {
            Files.createDirectories(messagesDir);
            String line = JsonUtils.toJsonCompact(message) + "\n";
            Files.writeString(messagesFile(), line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to persist message {}: {}", message.id(), e.getMessage());
        }
    }

    /**
     * 获取所有消息。
     */
    public List<SessionMessage> getAll() {
        return new ArrayList<>(messages);
    }

    /**
     * 获取指定 role 的消息。
     */
    public List<SessionMessage> getByRole(String role) {
        return messages.stream()
                .filter(m -> m.role().equals(role))
                .toList();
    }

    /**
     * 获取消息数量。
     */
    public int count() {
        return messages.size();
    }

    /**
     * 清空内存消息（不删除文件）。
     */
    public void clear() {
        messages.clear();
    }

    private void loadFromDisk() {
        Path file = messagesFile();
        if (!Files.exists(file)) return;

        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    SessionMessage msg = JsonUtils.fromJson(line, SessionMessage.class);
                    messages.add(msg);
                } catch (Exception e) {
                    log.warn("Failed to parse session message: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to load session messages: {}", e.getMessage());
        }
    }
}
