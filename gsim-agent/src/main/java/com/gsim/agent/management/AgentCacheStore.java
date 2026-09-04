package com.gsim.agent.management;

import com.gsim.agent.AgentConfigStore;
import com.gsim.core.cache.CacheInfo;
import com.gsim.core.cache.CacheSession;
import com.gsim.docslib.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 对话缓存 Repository — 仿 DocStore 模式封装 CacheStore。
 *
 * <p>管理 {@code cachesDir/} 下的 {@link CacheSession} JSON 文件。
 * 使用轻量索引（不加载 messages 全文）进行列表操作。
 */
public class AgentCacheStore {

    private static final Logger log = LoggerFactory.getLogger(AgentCacheStore.class);

    private final Path cachesDir;
    private final AgentConfigStore configStore;
    private final ConcurrentHashMap<String, CacheInfo> index = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    public AgentCacheStore(Path cachesDir, AgentConfigStore configStore) {
        this.cachesDir = cachesDir;
        this.configStore = configStore;
    }

    /**
     * 扫描 {@code cachesDir} 目录，为所有 JSON 缓存文件构建轻量元信息索引。
     *
     * <p>索引仅包含 {@link com.gsim.core.cache.CacheInfo} 元信息（不含 messages 全文），
     * 提供快速的列表和过滤能力。已初始化时直接返回，不会重复扫描。
     */
    public void init() {
        if (initialized) return;
        try {
            Files.createDirectories(cachesDir);
        } catch (IOException e) {
            log.error("Failed to create caches dir: {}", e.getMessage());
            return;
        }
        try (var files = Files.list(cachesDir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                try {
                    CacheInfo info = readMeta(file);
                    if (info != null) {
                        index.put(info.sessionId(), info);
                    }
                } catch (Exception e) {
                    log.debug("Skipping unreadable cache file: {}", name);
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan caches dir: {}", e.getMessage());
        }
        initialized = true;
        log.info("AgentCacheStore initialized: {} caches in {}", index.size(), cachesDir);
    }

    // ── 列表 ──

    /** 列出所有缓存（按 createdAt 降序），支持按 agentType 过滤。 */
    public List<CacheInfo> list(String agentType) {
        return index.values().stream()
                .filter(c -> agentType == null || agentType.equals(c.agentType()))
                .sorted(Comparator.comparing(CacheInfo::createdAt).reversed())
                .toList();
    }

    /**
     * 列出所有缓存（无过滤条件）。
     *
     * @return 所有缓存的元信息列表，按 createdAt 降序排列
     */
    public List<CacheInfo> list() {
        return list(null);
    }

    // ── 读取 ──

    /**
     * 全文加载指定缓存的完整会话。
     *
     * @param cacheId 缓存会话 ID
     * @return CacheSession 对象，不存在时返回 null
     */
    public CacheSession get(String cacheId) {
        Path file = cacheFile(cacheId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), CacheSession.class);
        } catch (IOException e) {
            log.error("Failed to load cache: {}", cacheId, e);
            return null;
        }
    }

    /**
     * 获取缓存元信息（不加载 messages 全文）。
     *
     * @param cacheId 缓存会话 ID
     * @return CacheInfo 元信息对象，不存在时返回 null
     */
    public CacheInfo getSummary(String cacheId) {
        CacheInfo cached = index.get(cacheId);
        if (cached != null) return cached;
        Path file = cacheFile(cacheId);
        if (!Files.exists(file)) return null;
        CacheInfo info = readMeta(file);
        if (info != null) index.put(cacheId, info);
        return info;
    }

    // ── 创建 ──

    /**
     * 创建新对话缓存，自动注入 Agent 配置中的系统提示词作为首条消息。
     *
     * @param configId Agent 配置 ID（用于提取系统提示词）
     * @return 新建的 CacheSession（已持久化）
     */
    public CacheSession create(String configId) {
        String now = Instant.now().toString();
        String sessionId = configId + "_" + now.replace(":", "-").substring(0, 19) + ".json";

        CacheSession session = new CacheSession(configId, sessionId, now);

        // 自动注入系统提示词
        var config = configStore.get(configId);
        if (config != null) {
            String sp = config.fullSystemPrompt();
            if (sp != null && !sp.isBlank()) {
                session.addMessage(Map.of("role", "system", "content", sp));
            }
        }

        save(session);
        CacheInfo info = CacheInfo.fromSession(session);
        index.put(sessionId, info);
        log.info("Created agent cache: {} (config={})", sessionId, configId);
        return session;
    }

    // ── 删除 ──

    /**
     * 删除指定缓存文件。
     *
     * @param cacheId 缓存会话 ID
     * @return 是否成功删除（false 表示文件不存在或删除异常）
     */
    public boolean delete(String cacheId) {
        Path file = cacheFile(cacheId);
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                index.remove(cacheId);
                log.info("Deleted agent cache: {}", cacheId);
            }
            return deleted;
        } catch (IOException e) {
            log.error("Failed to delete cache: {}", cacheId, e);
            return false;
        }
    }

    // ── 写入 ──

    /**
     * 追加消息到缓存并持久化。
     *
     * @param cacheId 缓存会话 ID
     * @param message 要追加的消息（role + content 等字段构成的 Map）
     */
    public void appendMessage(String cacheId, Map<String, Object> message) {
        CacheSession session = get(cacheId);
        if (session == null) {
            log.warn("Cannot append to non-existent cache: {}", cacheId);
            return;
        }
        session.addMessage(message);
        save(session);
        // 更新索引计数
        CacheInfo old = index.get(cacheId);
        if (old != null) {
            index.put(cacheId, CacheInfo.fromSession(session));
        }
    }

    /**
     * 直接持久化整个 CacheSession 到文件。
     *
     * @param session 要保存的缓存会话
     */
    public void save(CacheSession session) {
        Path file = cacheFile(session.sessionId());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonUtils.toJson(session));
        } catch (IOException e) {
            log.error("Failed to save cache: {}", session.sessionId(), e);
            throw new RuntimeException("Failed to save cache: " + session.sessionId(), e);
        }
    }

    /**
     * 返回当前缓存索引中的文件数量。
     *
     * @return 索引中记录的缓存文件总数
     */
    public int count() {
        return index.size();
    }

    // ── 内部方法 ──

    private Path cacheFile(String sessionId) {
        return cachesDir.resolve(sessionId);
    }

    /** 轻量解析 JSON 元信息（不加载 messages 数组）。 */
    private CacheInfo readMeta(Path file) {
        try {
            String raw = Files.readString(file);
            String agentName = extractJsonString(raw, "agentName");
            String sessionId = extractJsonString(raw, "sessionId");
            String createdAt = extractJsonString(raw, "createdAt");
            String previousSessionId = extractJsonString(raw, "previousSessionId");
            int msgCount = countMessages(raw);

            // 提取首条 user prompt 摘要
            String firstUserMsg = extractFirstUserMsg(raw);

            return new CacheInfo(
                    agentName != null ? agentName : "unknown",
                    CacheInfo.inferType(agentName),
                    sessionId != null ? sessionId : file.getFileName().toString(),
                    createdAt != null ? createdAt : "",
                    msgCount,
                    previousSessionId,
                    firstUserMsg);
        } catch (IOException e) {
            return null;
        }
    }

    private static String extractJsonString(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return null;
        int valStart = json.indexOf('"', colonIdx + 1);
        if (valStart < 0) return null;
        int valEnd = json.indexOf('"', valStart + 1);
        if (valEnd < 0) return null;
        return json.substring(valStart + 1, valEnd);
    }

    private static int countMessages(String json) {
        int count = 0;
        int idx = 0;
        while ((idx = json.indexOf("\"role\"", idx)) != -1) {
            count++;
            idx += 6;
        }
        return count;
    }

    /** 提取第一条 user 消息的摘要（截断 120 字符）。 */
    private static String extractFirstUserMsg(String json) {
        int msgStart = json.indexOf("\"messages\"");
        if (msgStart < 0) return null;
        // 找到 messages 数组中第一个 "role": "user" 之后的 "content"
        int userIdx = json.indexOf("\"role\"", msgStart);
        while (userIdx > 0) {
            String roleVal = extractJsonStringAt(json, userIdx);
            if ("user".equals(roleVal)) {
                // 找到该 obj 中的 content
                int contentKeyIdx = json.indexOf("\"content\"", userIdx);
                if (contentKeyIdx > 0 && contentKeyIdx < userIdx + 5000) {
                    String content = extractJsonStringAt(json, contentKeyIdx);
                    if (content != null && content.length() > 120) {
                        return content.substring(0, 120) + "...";
                    }
                    return content;
                }
                break;
            }
            userIdx = json.indexOf("\"role\"", userIdx + 6);
        }
        return null;
    }

    private static String extractJsonStringAt(String json, int fromIdx) {
        int colonIdx = json.indexOf(':', fromIdx);
        if (colonIdx < 0) return null;
        int valStart = json.indexOf('"', colonIdx + 1);
        if (valStart < 0) return null;
        int valEnd = json.indexOf('"', valStart + 1);
        if (valEnd < 0) return null;
        return json.substring(valStart + 1, valEnd);
    }
}
