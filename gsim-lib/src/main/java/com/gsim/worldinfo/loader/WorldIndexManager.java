package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.NodeSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 世界索引管理器 -- 管理 _index.json 和 world.json 文件。
 *
 * <p>_index.json 维护所有世界的列表（{@link WorldEntry}），位于 worlds 根目录。
 * world.json 存储单个世界的元数据（{@link WorldMeta}），位于每个 world 目录内。
 * 创建新世界时同时写入 _index.json 和 world.json，并初始化根节点 n0000 和 active.json。
 *
 * <p>此类为纯静态工具类，不可实例化。
 */
public final class WorldIndexManager {

    private static final Logger log = LoggerFactory.getLogger(WorldIndexManager.class);

    private WorldIndexManager() {}

    /**
     * 世界列表条目记录。
     *
     * @param id        世界 ID（文件夹名）
     * @param name      显示名称
     * @param createdAt 创建时间（ISO-8601 字符串）
     */
    public record WorldEntry(String id, String name, String createdAt) {}

    // ---- _index.json ----

    /**
     * 获取 _index.json 文件路径。
     *
     * @param worldsDir worlds 根目录
     * @return _index.json 的完整路径
     */
    public static Path indexFile(Path worldsDir) {
        return worldsDir.resolve("_index.json");
    }

    private static void saveIndex(Path worldsDir, List<WorldEntry> entries) {
        try {
            Files.createDirectories(worldsDir);
            Files.writeString(indexFile(worldsDir), JsonUtils.toJson(entries));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write _index.json", e);
        }
    }

    // ---- world.json ----

    /**
     * 世界元数据记录。
     *
     * @param id            世界 ID
     * @param name          显示名称
     * @param createdAt     创建时间（ISO-8601 字符串）
     * @param currentNodeId 当前活跃节点 ID
     */
    public record WorldMeta(String id, String name, String createdAt, String currentNodeId) {}

    /**
     * 获取指定世界的 world.json 文件路径。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @return world.json 的完整路径
     */
    public static Path worldFile(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("world.json");
    }

    /**
     * 加载指定世界的元数据。
     *
     * <p>如果 world.json 中的 id 字段与目录名不匹配，自动修复并保存。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @return WorldMeta 记录，若 world.json 不存在则返回 null
     * @throws RuntimeException 文件存在但读取/反序列化失败时抛出
     */
    public static WorldMeta loadWorldMeta(Path worldsDir, String worldId) {
        Path file = worldFile(worldsDir, worldId);
        if (!Files.exists(file)) return null;
        try {
            String raw = Files.readString(file);
            WorldMeta meta = JsonUtils.fromJson(raw, WorldMeta.class);
            if (meta != null && !meta.id().equals(worldId)) {
                log.warn(
                        "World '{}' has mismatched id '{}' in world.json — auto-fixing to '{}'",
                        worldId,
                        meta.id(),
                        worldId);
                WorldMeta fixed = new WorldMeta(worldId, meta.name(), meta.createdAt(), meta.currentNodeId());
                Files.writeString(file, JsonUtils.toJson(fixed));
                return fixed;
            }
            return meta;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load world.json: " + worldId, e);
        }
    }

    /**
     * 列出所有世界，结合 _index.json 和文件系统扫描。
     *
     * <p>首先从 _index.json 读取，然后扫描 worlds 目录找出未被索引的世界目录
     * （例如手动复制而未调用 world_create 创建的目录），读取其 world.json 并追加。
     * 不存在 world.json 的目录会被跳过并记录警告。
     *
     * @param worldsDir worlds 根目录
     * @return 完整的世界条目列表
     */
    public static List<WorldEntry> listWorlds(Path worldsDir) {
        List<WorldEntry> entries = loadIndexEntries(worldsDir);

        // 扫描文件系统，发现 _index.json 中未记录的世界目录
        java.io.File[] dirs = worldsDir.toFile().listFiles(java.io.File::isDirectory);
        if (dirs != null) {
            for (java.io.File d : dirs) {
                String dirName = d.getName();
                if (dirName.startsWith(".")) continue;

                boolean inIndex = entries.stream().anyMatch(e -> e.id().equals(dirName));
                if (!inIndex) {
                    // 尝试从 world.json 加载
                    WorldMeta meta = loadWorldMeta(worldsDir, dirName);
                    if (meta != null) {
                        log.info("Discovered unindexed world '{}' ({}) — adding to listing", dirName, meta.name());
                        entries.add(new WorldEntry(meta.id(), meta.name(), meta.createdAt()));
                    } else {
                        log.warn("Directory '{}' exists under worlds/ but has no world.json — skipping", dirName);
                    }
                }
            }
        }

        return entries;
    }

    /** Load entries from _index.json only. */
    private static List<WorldEntry> loadIndexEntries(Path worldsDir) {
        Path file = indexFile(worldsDir);
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            WorldEntry[] arr = JsonUtils.fromJson(Files.readString(file), WorldEntry[].class);
            return arr != null ? new ArrayList<>(List.of(arr)) : new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    // ---- creation ----

    /**
     * Create a new world. Generates n0000 root node with empty checkpoints.
     * Returns the world meta.
     */
    public static WorldMeta createWorld(Path worldsDir, String worldId, String name) {
        String now = Instant.now().toString();

        // world.json
        WorldMeta meta = new WorldMeta(worldId, name, now, "n0000");
        try {
            Files.createDirectories(worldFile(worldsDir, worldId).getParent());
            Files.writeString(worldFile(worldsDir, worldId), JsonUtils.toJson(meta));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create world.json", e);
        }

        // n0000.json (empty root node)
        NodeSnapshot root = new NodeSnapshot(
                "n0000",
                null,
                0,
                "时间原点",
                "initial",
                now,
                Map.of(
                        "worldview", new Checkpoint("世界观", "worldview", new ArrayList<>()),
                        "narrative", new Checkpoint("推文", "narrative", new ArrayList<>())),
                new LinkedHashMap<>());
        NodeLoader.save(NodeLoader.nodeFile(worldsDir, worldId, "n0000"), root);

        // _index.json (use loadIndexEntries to avoid double-counting partially-created dirs)
        List<WorldEntry> entries = new ArrayList<>(loadIndexEntries(worldsDir));
        entries.add(new WorldEntry(worldId, name, now));
        saveIndex(worldsDir, entries);

        // active.json
        ActiveStateManager.save(worldsDir, worldId, new ActiveStateManager.ActiveState("n0000", Map.of()));

        return meta;
    }
}
