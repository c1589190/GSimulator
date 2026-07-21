package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.loader.WorldIndexManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * world_switch -- 切换到指定 World 并重新加载整个 WorldInformation。
 *
 * <p>切换操作会触发回调函数重新加载目标世界的节点、缓存和系统提示词，
 * 当前对话上下文会被刷新。切换前验证目标 World 是否存在，
 * 若不存在则返回错误。
 *
 * <p>配合 {@link WorldListTool} 使用可以查看可用的 World 列表。
 * 切换后所有后续工具调用（如 query_node、write_element）将在新世界中执行。
 */
public final class WorldSwitchTool implements AgentTool {

    private final Path worldsDir;
    private final Function<String, String> switchCallback; // worldId → result message or null on success

    public WorldSwitchTool(Path worldsDir, Function<String, String> switchCallback) {
        this.worldsDir = worldsDir;
        this.switchCallback = switchCallback;
    }

    @Override
    public String name() {
        return "world_switch";
    }

    @Override
    public String description() {
        return "切换到指定 World（重新加载节点、缓存、系统提示词）。" + "参数: worldId (目标 World ID, 必填)。切换后当前对话上下文会刷新。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("worldId", Map.of("type", "string", "description", "目标 World ID")),
                "required", List.of("worldId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = call.param("worldId", "").trim();
        if (worldId.isEmpty()) return ToolResult.fail(name(), "worldId 不能为空");

        // 验证 world 存在
        WorldIndexManager.WorldMeta meta = WorldIndexManager.loadWorldMeta(worldsDir, worldId);
        if (meta == null) {
            return ToolResult.fail(name(), "World 不存在: " + worldId);
        }

        // 执行切换
        String error = switchCallback.apply(worldId);
        if (error != null) {
            return ToolResult.fail(name(), "切换失败: " + error);
        }

        return ToolResult.ok(
                name(), List.of(new ToolResult.Item("已切换到 " + worldId, worldId, "World 切换成功。系统提示词、节点状态、缓存均已刷新。", 1.0)));
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
