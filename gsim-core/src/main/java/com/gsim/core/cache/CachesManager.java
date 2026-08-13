package com.gsim.core.cache;

import java.util.List;
import java.util.Optional;

/**
 * 缓存管理接口 — 统一管理所有 Agent 的对话缓存。
 *
 * <p>提供缓存列表、加载、创建、删除操作。
 * 当前 CLI 实现为 {@link FileSystemCachesManager}，后续 WebUI 可有其他实现。
 */
public interface CachesManager {

    /**
     * 列出某 world 下所有 cache（按 createdAt 降序）。
     *
     * @param worldId 世界 ID
     * @return cache 元信息列表
     */
    List<CacheInfo> listCaches(String worldId);

    /**
     * 按 agent 类型过滤缓存列表。
     *
     * @param worldId   世界 ID
     * @param agentType Agent 类型（如 "orchestrator", "sim", "search"）
     * @return 过滤后的 cache 元信息列表
     */
    List<CacheInfo> listCaches(String worldId, String agentType);

    /**
     * 加载完整 CacheSession（含全部消息）。
     *
     * @param worldId   世界 ID
     * @param sessionId 会话 ID
     * @return CacheSession 实例，未找到时返回 null
     */
    CacheSession loadCache(String worldId, String sessionId);

    /**
     * 创建空 cache 并持久化。
     *
     * @param worldId   世界 ID
     * @param agentName Agent 名称
     * @param nodeId    当前节点 ID
     * @return 新建的 CacheSession 实例
     */
    CacheSession createCache(String worldId, String agentName, String nodeId);

    /**
     * 删除 cache 文件。
     *
     * @param worldId   世界 ID
     * @param sessionId 会话 ID
     * @return 删除成功返回 true，否则返回 false
     */
    boolean deleteCache(String worldId, String sessionId);

    /**
     * 获取 cache 元信息（不加载消息体）。
     *
     * @param worldId   世界 ID
     * @param sessionId 会话 ID
     * @return 包含 CacheInfo 的 Optional，未找到时返回 empty
     */
    Optional<CacheInfo> getCacheInfo(String worldId, String sessionId);
}
