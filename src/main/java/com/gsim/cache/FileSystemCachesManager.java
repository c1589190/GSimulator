package com.gsim.cache;

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
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 文件系统缓存管理器 — {@link CachesManager} 的默认实现。
 *
 * <p>缓存存储于 {@code worlds/{worldId}/caches/} 目录下，每个 .json 文件即一个 CacheSession。
 */
public class FileSystemCachesManager implements CachesManager {

    private static final Logger log = LoggerFactory.getLogger(FileSystemCachesManager.class);

    private final Path worldsDir;

    public FileSystemCachesManager(Path worldsDir) {
        this.worldsDir = worldsDir;
    }

    @Override
    public List<CacheInfo> listCaches(String worldId) {
        return listCachesInternal(worldId, null);
    }

    @Override
    public List<CacheInfo> listCaches(String worldId, String agentType) {
        return listCachesInternal(worldId, agentType);
    }

    private List<CacheInfo> listCachesInternal(String worldId, String agentType) {
        List<CacheInfo> result = new ArrayList<>();
        Path dir = CacheStore.cachesDir(worldsDir);
        if (!Files.isDirectory(dir)) return result;

        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                try {
                    CacheInfo info = readMeta(file);
                    if (info != null) {
                        // 按 worldId 过滤（null 表示不过滤）
                        if (worldId != null && !worldId.equals(info.worldId())) continue;
                        // 按 agentType 过滤
                        if (agentType != null && !agentType.equals(info.agentType())) continue;
                        result.add(info);
                    }
                } catch (Exception e) {
                    log.debug("Skipping unreadable cache file: {}", name);
                }
            }
        } catch (IOException e) {
            log.error("Failed to list caches: {}", e.getMessage());
        }

        // 按 createdAt 降序（最新在前）
        result.sort(Comparator.comparing(CacheInfo::createdAt).reversed());
        return result;
    }

    @Override
    public CacheSession loadCache(String worldId, String sessionId) {
        CacheSession session = CacheStore.load(worldsDir, sessionId);
        if (session != null && worldId != null && !worldId.equals(session.worldId())) {
            log.warn("Cache {} worldId mismatch: expected {}, got {}",
                    sessionId, worldId, session.worldId());
        }
        return session;
    }

    @Override
    public CacheSession createCache(String worldId, String agentName, String nodeId) {
        return CacheStore.createNew(worldsDir, worldId, agentName, nodeId);
    }

    @Override
    public boolean deleteCache(String worldId, String sessionId) {
        // 可选：验证 worldId 匹配再删除
        if (worldId != null) {
            CacheSession session = CacheStore.load(worldsDir, sessionId);
            if (session != null && !worldId.equals(session.worldId())) {
                log.warn("Refusing to delete cache {}: worldId mismatch (expected {}, got {})",
                        sessionId, worldId, session.worldId());
                return false;
            }
        }
        Path file = CacheStore.cacheFile(worldsDir, sessionId);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Failed to delete cache '{}': {}", sessionId, e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<CacheInfo> getCacheInfo(String worldId, String sessionId) {
        Path file = CacheStore.cacheFile(worldsDir, sessionId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            CacheInfo info = readMeta(file);
            if (info != null && worldId != null && !worldId.equals(info.worldId())) {
                return Optional.empty();
            }
            return Optional.ofNullable(info);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 只解析顶层字段获取元信息（不加载 messages 数组）。
     *  worldId 从文件内容中读取，不再从目录路径推断。 */
    private CacheInfo readMeta(Path file) {
        try {
            // 使用轻量解析：只读顶层标量字段，跳过 messages
            String raw = Files.readString(file);
            String agentName = extractJsonString(raw, "agentName");
            String sessionId = extractJsonString(raw, "sessionId");
            String fileWorldId = extractJsonString(raw, "worldId");
            String nodeId = extractJsonString(raw, "nodeId");
            String createdAt = extractJsonString(raw, "createdAt");
            String previousSessionId = extractJsonString(raw, "previousSessionId");
            int msgCount = countMessages(raw);
            return new CacheInfo(
                    agentName != null ? agentName : "unknown",
                    CacheInfo.inferType(agentName),
                    sessionId != null ? sessionId : file.getFileName().toString(),
                    fileWorldId != null ? fileWorldId : "unknown",
                    nodeId != null ? nodeId : "n0000",
                    createdAt != null ? createdAt : "",
                    msgCount,
                    previousSessionId != null && !previousSessionId.isEmpty() ? previousSessionId : null
            );
        } catch (IOException e) {
            return null;
        }
    }

    /** 简单 JSON 字符串字段提取（不依赖完整反序列化）。 */
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

    /** 估算 messages 数组中的元素数量（统计 "role" 字段出现次数）。 */
    private static int countMessages(String json) {
        int count = 0;
        int idx = 0;
        while ((idx = json.indexOf("\"role\"", idx)) != -1) {
            count++;
            idx += 6;
        }
        return count;
    }
}
