package com.gsim.agentsmanager.cache;

import com.gsim.docslib.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Reads and writes CacheSession JSON files.
 */
@Deprecated // B方案: Caches JSON 已迁 agentsmanager.cache, 静态 API 保留兼容
public final class CacheStore {

    private CacheStore() {}

    /** Caches root directory — peer to the worlds directory (e.g. ./caches/). */
    private static volatile Path cachesRoot = null;

    /**
     * 设置缓存根目录（启动时调用）。
     *
     * @param root 缓存根目录路径
     */
    public static void setCachesRoot(Path root) {
        cachesRoot = root;
    }

    /**
     * 获取缓存根目录（扁平结构，不含世界子目录）。
     *
     * @return 缓存根目录路径
     * @throws IllegalStateException cachesRoot 未配置时抛出
     */
    public static Path cachesDir() {
        if (cachesRoot == null) {
            throw new IllegalStateException("Caches root not configured — call setCachesRoot first");
        }
        return cachesRoot;
    }

    /**
     * 获取指定缓存文件的完整路径（格式：caches/{sessionId}）。
     *
     * @param sessionId 会话 ID（文件名）
     * @return 缓存文件的完整路径
     */
    public static Path cacheFile(String sessionId) {
        return cachesDir().resolve(sessionId);
    }

    /**
     * 从磁盘加载缓存会话。如果文件不存在则返回 null。
     *
     * @param sessionId 会话 ID
     * @return CacheSession 实例，未找到时返回 null
     * @throws RuntimeException 文件读取或 JSON 解析失败时抛出
     */
    public static CacheSession load(String sessionId) {
        Path file = cacheFile(sessionId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), CacheSession.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load cache session: " + sessionId, e);
        }
    }

    /**
     * 将缓存会话保存到磁盘。自动创建缓存目录（如需）。
     *
     * @param session 要保存的 CacheSession 实例
     * @throws RuntimeException 文件写入失败时抛出
     */
    public static void save(CacheSession session) {
        Path file = cacheFile(session.sessionId());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonUtils.toJson(session));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save cache session: " + session.sessionId(), e);
        }
    }

    /**
     * 创建一个新的空会话，使用基于时间戳的 sessionId。不会自动保存——仅当首次调用 appendAndSave() 时持久化。
     *
     * @param agentName Agent 名称
     * @return 新建的 CacheSession 实例
     */
    public static CacheSession createNew(String agentName) {
        String now = Instant.now().toString();
        // use agent-timestamp format for the session ID
        String sessionId = agentName + "_" + now.replace(":", "-").substring(0, 19) + ".json";
        String finalNow = now;

        return new CacheSession(agentName, sessionId, finalNow);
    }

    /**
     * 追加消息并持久化到磁盘。用于流式增量保存。
     *
     * @param session 要更新的 CacheSession 实例
     * @param message 要追加的消息（符合 OpenAI 格式）
     */
    public static void appendAndSave(CacheSession session, Map<String, Object> message) {
        session.addMessage(message);
        save(session);
    }
}
