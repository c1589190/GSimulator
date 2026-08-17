package com.gsim.core.worldinfo.loader;

import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 统一的世界读取入口。
 *
 * <p>集中封装 {@code worlds/} 目录的只读访问，所有需要读取世界元数据、
 * 世界节点集合或推导活跃节点的代码都应通过本类，而不是直接调用
 * {@link WorldIndexManager}、{@link WorldInfoBuilder} 或自行扫描节点目录。
 *
 * <p>本类不缓存数据：每次调用都从磁盘加载，调用方（如应用层）可在自身
 * 生命周期内缓存并负责失效。只读方法可在多线程环境安全使用。
 *
 * <p>典型用法：
 * <pre>{@code
 * WorldManager worlds = new WorldManager(worldsDir);
 * String activeNodeId = worlds.activeNodeIdOr("my-world", "n0000");
 * WorldInformation wi = worlds.loadWorld("my-world");
 * }</pre>
 */
public final class WorldManager {

    /** 与 {@link WorldInfoBuilder#discover} 保持一致的节点顺序：turn 升序，同 turn 按 nodeId。 */
    private static final Comparator<NodeSnapshot> BY_TURN_THEN_ID =
            Comparator.comparingInt(NodeSnapshot::turn).thenComparing(NodeSnapshot::nodeId);

    private final Path worldsDir;

    public WorldManager(Path worldsDir) {
        this.worldsDir =
                Objects.requireNonNull(worldsDir, "worldsDir").toAbsolutePath().normalize();
    }

    /** 返回本管理器读取的 {@code worlds/} 根目录。 */
    public Path worldsDir() {
        return worldsDir;
    }

    /** 列出所有包含 world.json 的世界。 */
    public List<WorldIndexManager.WorldEntry> listWorlds() {
        return WorldIndexManager.listWorlds(worldsDir);
    }

    /** 判断指定世界是否存在。 */
    public boolean exists(String worldId) {
        return WorldIndexManager.loadWorldMeta(worldsDir, worldId) != null;
    }

    /** 读取世界元数据（world.json），不存在时返回 null。 */
    public WorldIndexManager.WorldMeta loadMeta(String worldId) {
        return WorldIndexManager.loadWorldMeta(worldsDir, worldId);
    }

    /**
     * 读取完整世界信息。
     *
     * <p>使用 {@link WorldInfoBuilder#discover(Path, String)} 从磁盘扫描全部
     * 可读节点，不依赖硬编码的活跃节点 ID；断链与孤儿节点也会保留。
     *
     * @return 世界信息，世界不存在或没有可读节点时返回 null
     */
    public WorldInformation loadWorld(String worldId) {
        return WorldInfoBuilder.discover(worldsDir, worldId);
    }

    /**
     * 读取根节点 ID（turn 最小的节点），世界不存在或无节点时返回 null。
     *
     * <p>轻量读取：只扫描节点元数据，不构建 WorldInformation 索引。
     */
    public String rootNodeId(String worldId) {
        return WorldInfoBuilder.loadAllNodes(worldsDir, worldId).values().stream()
                .min(BY_TURN_THEN_ID)
                .map(NodeSnapshot::nodeId)
                .orElse(null);
    }

    /**
     * 读取活跃节点 ID（turn 最大的节点），世界不存在或无节点时返回 null。
     *
     * <p>轻量读取：只扫描节点元数据，不构建 WorldInformation 索引，
     * 与 {@link WorldInfoBuilder#discover} 的 activeNodeId 语义一致。
     */
    public String activeNodeId(String worldId) {
        return WorldInfoBuilder.loadAllNodes(worldsDir, worldId).values().stream()
                .max(BY_TURN_THEN_ID)
                .map(NodeSnapshot::nodeId)
                .orElse(null);
    }

    /**
     * 读取活跃节点 ID，失败时使用 fallback。
     *
     * <p>兼容旧行为：历史上大量调用方在无法推导活跃节点时回退到 {@code n0000}。
     */
    public String activeNodeIdOr(String worldId, String fallback) {
        String nodeId = activeNodeId(worldId);
        return nodeId != null ? nodeId : fallback;
    }

    /** 返回世界节点目录路径。 */
    public Path nodesDir(String worldId) {
        return NodeLoader.nodesDir(worldsDir, worldId);
    }

    /** 返回世界元数据文件路径。 */
    public Path worldFile(String worldId) {
        return WorldIndexManager.worldFile(worldsDir, worldId);
    }
}
