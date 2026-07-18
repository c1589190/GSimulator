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

    /**
     * 列出所有已注册的世界。
     *
     * @param worldsDir worlds 根目录
     * @return 世界条目列表，若 _index.json 不存在则返回空列表
     */
    public static List<WorldEntry> listWorlds(Path worldsDir) {
        Path file = indexFile(worldsDir);
        if (!Files.exists(file)) return List.of();
        try {
            WorldEntry[] arr = JsonUtils.fromJson(Files.readString(file), WorldEntry[].class);
            return arr != null ? List.of(arr) : List.of();
        } catch (IOException e) {
            return List.of();
        }
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
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @return WorldMeta 记录，若 world.json 不存在则返回 null
     * @throws RuntimeException 文件存在但读取/反序列化失败时抛出
     */
    public static WorldMeta loadWorldMeta(Path worldsDir, String worldId) {
        Path file = worldFile(worldsDir, worldId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), WorldMeta.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load world.json: " + worldId, e);
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

        // _index.json
        List<WorldEntry> entries = new ArrayList<>(listWorlds(worldsDir));
        entries.add(new WorldEntry(worldId, name, now));
        saveIndex(worldsDir, entries);

        // active.json
        ActiveStateManager.save(worldsDir, worldId, new ActiveStateManager.ActiveState("n0000", Map.of()));

        return meta;
    }
}
