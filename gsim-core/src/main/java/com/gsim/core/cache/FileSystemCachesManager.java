package com.gsim.core.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件系统缓存管理器 — {@link CachesManager} 的默认实现。
 *
 * <p>缓存存储于 {@code caches/} 目录下（由 {@link CacheStore#setCachesRoot} 配置），
 * 每个 .json 文件即一个 CacheSession。
 */
public class FileSystemCachesManager implements CachesManager {

    private static final Logger log = LoggerFactory.getLogger(FileSystemCachesManager.class);

    /**
     * 构造文件系统缓存管理器。
     * 缓存根目录通过 {@link CacheStore#setCachesRoot} 在启动时配置。
     */
    public FileSystemCachesManager() {}

    @Override
    public List<CacheInfo> listCaches() {
        return listCachesInternal(null);
    }

    @Override
    public List<CacheInfo> listCaches(String agentType) {
        return listCachesInternal(agentType);
    }

    private List<CacheInfo> listCachesInternal(String agentType) {
        List<CacheInfo> result = new ArrayList<>();
        Path dir = CacheStore.cachesDir();
        if (!Files.isDirectory(dir)) return result;

        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json")) continue;
                try {
                    CacheInfo info = readMeta(file);
                    if (info != null) {
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
    public CacheSession loadCache(String sessionId) {
        return CacheStore.load(sessionId);
    }

    @Override
    public CacheSession createCache(String agentName) {
        return CacheStore.createNew(agentName);
    }

    @Override
    public boolean deleteCache(String sessionId) {
        Path file = CacheStore.cacheFile(sessionId);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Failed to delete cache '{}': {}", sessionId, e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<CacheInfo> getCacheInfo(String sessionId) {
        Path file = CacheStore.cacheFile(sessionId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.ofNullable(readMeta(file));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 只解析顶层字段获取元信息（不加载 messages 数组）。 */
    private CacheInfo readMeta(Path file) {
        try {
            // 使用轻量解析：只读顶层标量字段，跳过 messages
            String raw = Files.readString(file);
            String agentName = extractJsonString(raw, "agentName");
            String sessionId = extractJsonString(raw, "sessionId");
            String createdAt = extractJsonString(raw, "createdAt");
            String previousSessionId = extractJsonString(raw, "previousSessionId");
            int msgCount = countMessages(raw);
            return new CacheInfo(
                    agentName != null ? agentName : "unknown",
                    CacheInfo.inferType(agentName),
                    sessionId != null ? sessionId : file.getFileName().toString(),
                    createdAt != null ? createdAt : "",
                    msgCount,
                    previousSessionId != null && !previousSessionId.isEmpty() ? previousSessionId : null,
                    null // firstUserMsg — lightweight meta reader doesn't extract this
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
