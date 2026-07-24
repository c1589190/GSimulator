package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 活跃状态管理器 -- 读写 world 目录下的 active.json 文件。
 *
 * <p>active.json 记录各 Agent 的会话文件名映射（如 Orchestrator 缓存文件），
 * 用于跨重启恢复 Agent 会话。不再追踪活跃节点 ID（该状态由 SessionPool 在内存中管理）。
 *
 * <p>此类为纯静态工具类，不可实例化。
 */
public final class ActiveStateManager {

    private ActiveStateManager() {}

    /**
     * 活跃状态记录 -- 保存 Agent 会话文件映射。
     *
     * @param sessions Agent 名称到会话文件名的映射（如 "Orchestrator" → "orch-xxx.json"）
     */
    public record ActiveState(Map<String, String> sessions // agentName → sessionFileName
            ) {
        public ActiveState {
            if (sessions == null) sessions = new LinkedHashMap<>();
        }
    }

    /**
     * 获取指定 world 的 active.json 文件路径。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @return active.json 的完整路径
     */
    public static Path activeFile(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("active.json");
    }

    /**
     * 加载指定 world 的活跃状态。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @return ActiveState 记录，若文件不存在则返回 null
     * @throws RuntimeException 文件存在但读取/反序列化失败时抛出
     */
    public static ActiveState load(Path worldsDir, String worldId) {
        Path file = activeFile(worldsDir, worldId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), ActiveState.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load active.json for world: " + worldId, e);
        }
    }

    /**
     * 保存指定 world 的活跃状态到 active.json。
     * 自动创建父目录。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @param state     要保存的活跃状态
     * @throws RuntimeException 写入失败时抛出
     */
    public static void save(Path worldsDir, String worldId, ActiveState state) {
        Path file = activeFile(worldsDir, worldId);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonUtils.toJson(state));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save active.json", e);
        }
    }

    /**
     * 获取 Orchestrator Agent 的会话文件名。
     *
     * @param state 活跃状态（允许为 null）
     * @return Orchestrator 会话文件名，若 state 为 null 或不存在 Orchestrator 会话则返回 null
     */
    public static String orchestratorSession(ActiveState state) {
        return state != null ? state.sessions().get("Orchestrator") : null;
    }
}
