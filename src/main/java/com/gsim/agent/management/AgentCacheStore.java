package com.gsim.agent.management;

import com.gsim.agent.config.AgentConfigStore;
import com.gsim.cache.CacheInfo;
import com.gsim.cache.CacheSession;
import com.gsim.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 扫描 cachesDir 构建轻量索引。 */
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

    /** 列出所有缓存（按 createdAt 降序），支持按 worldId 和 agentType 过滤。 */
    public List<CacheInfo> list(String worldId, String agentType) {
        return index.values().stream()
                .filter(c -> worldId == null || worldId.equals(c.worldId()))
                .filter(c -> agentType == null || agentType.equals(c.agentType()))
                .sorted(Comparator.comparing(CacheInfo::createdAt).reversed())
                .toList();
    }

    public List<CacheInfo> list() {
        return list(null, null);
    }

    // ── 读取 ──

    /** 全文加载缓存。 */
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

    /** 获取缓存元信息（不加载全文）。 */
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
     * @param worldId  所属 World ID
     * @param configId Agent 配置 ID（用于提取系统提示词）
     * @param nodeId   关联节点 ID
     * @return 新建的 CacheSession（已持久化）
     */
    public CacheSession create(String worldId, String configId, String nodeId) {
        String now = Instant.now().toString();
        String sessionId = configId + "_" + now.replace(":", "-").substring(0, 19) + ".json";

        CacheSession session = new CacheSession(configId, worldId, nodeId, sessionId, now);

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
        log.info("Created agent cache: {} (config={}, world={})", sessionId, configId, worldId);
        return session;
    }

    // ── 删除 ──

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

    /** 追加消息并持久化。 */
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

    /** 直接持久化整个 CacheSession。 */
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

    /** 缓存文件总数。 */
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
            String worldId = extractJsonString(raw, "worldId");
            String nodeId = extractJsonString(raw, "nodeId");
            String createdAt = extractJsonString(raw, "createdAt");
            String previousSessionId = extractJsonString(raw, "previousSessionId");
            int msgCount = countMessages(raw);

            // 提取首条 user prompt 摘要
            String firstUserMsg = extractFirstUserMsg(raw);

            return new CacheInfo(
                    agentName != null ? agentName : "unknown",
                    CacheInfo.inferType(agentName),
                    sessionId != null ? sessionId : file.getFileName().toString(),
                    worldId != null ? worldId : "unknown",
                    nodeId != null ? nodeId : "n0000",
                    createdAt != null ? createdAt : "",
                    msgCount,
                    previousSessionId,
                    firstUserMsg
            );
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
