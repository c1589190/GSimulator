package com.gsim.cache;

import java.util.List;
import java.util.Optional;

/**
 * 缓存管理接口 — 统一管理所有 Agent 的对话缓存。
 *
 * <p>提供缓存列表、加载、创建、删除操作。
 * 当前 CLI 实现为 {@link FileSystemCachesManager}，后续 WebUI 可有其他实现。
 */
public interface CachesManager {

    /** 列出某 world 下所有 cache（按 createdAt 降序）。 */
    List<CacheInfo> listCaches(String worldId);

    /** 按 agent 类型过滤。 */
    List<CacheInfo> listCaches(String worldId, String agentType);

    /** 加载完整 CacheSession（含全部消息）。 */
    CacheSession loadCache(String worldId, String sessionId);

    /** 创建空 cache 并持久化。 */
    CacheSession createCache(String worldId, String agentName, String nodeId);

    /** 删除 cache 文件。返回 true 表示删除成功。 */
    boolean deleteCache(String worldId, String sessionId);

    /** 获取 cache 元信息（不加载消息体）。 */
    Optional<CacheInfo> getCacheInfo(String worldId, String sessionId);
}
