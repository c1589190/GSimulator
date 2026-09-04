package com.gsim.agentsmanager.cache;

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
     * 列出所有 cache（按 createdAt 降序）。
     *
     * @return cache 元信息列表
     */
    List<CacheInfo> listCaches();

    /**
     * 按 agent 类型过滤缓存列表。
     *
     * @param agentType Agent 类型（如 "orchestrator", "sim", "search"）
     * @return 过滤后的 cache 元信息列表
     */
    List<CacheInfo> listCaches(String agentType);

    /**
     * 加载完整 CacheSession（含全部消息）。
     *
     * @param sessionId 会话 ID
     * @return CacheSession 实例，未找到时返回 null
     */
    CacheSession loadCache(String sessionId);

    /**
     * 创建空 cache 并持久化。
     *
     * @param agentName Agent 名称
     * @return 新建的 CacheSession 实例
     */
    CacheSession createCache(String agentName);

    /**
     * 删除 cache 文件。
     *
     * @param sessionId 会话 ID
     * @return 删除成功返回 true，否则返回 false
     */
    boolean deleteCache(String sessionId);

    /**
     * 获取 cache 元信息（不加载消息体）。
     *
     * @param sessionId 会话 ID
     * @return 包含 CacheInfo 的 Optional，未找到时返回 empty
     */
    Optional<CacheInfo> getCacheInfo(String sessionId);
}
