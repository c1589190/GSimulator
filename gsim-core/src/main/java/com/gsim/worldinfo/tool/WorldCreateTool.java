package com.gsim.worldinfo.tool;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.worldinfo.loader.WorldIndexManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * world_create -- 创建全新的独立 World。
 *
 * <p>每个 World 是一个独立的根节点世界，拥有完整的节点链、检查点和元素体系，
 * 与其他 World 的数据完全隔离。创建过程会：
 * <ul>
 *   <li>写入 world.json（元数据）</li>
 *   <li>生成根节点 n0000（含 worldview 和 narrative 检查点）</li>
 *   <li>初始化 active.json</li>
 *   <li>注册到 _index.json</li>
 * </ul>
 *
 * worldId 只允许字母、数字、连字符和下划线。
 */
public final class WorldCreateTool implements AgentTool {

    private final Path worldsDir;

    public WorldCreateTool(Path worldsDir) {
        this.worldsDir = worldsDir;
    }

    @Override
    public String name() {
        return "world_create";
    }

    @Override
    public String description() {
        return "创建一个全新的独立 World（含独立根节点 n0000，与当前世界的数据完全隔离）。" + "参数: worldId (文件夹名, 必填, 仅字母数字连字符), name (显示名称, 必填)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId", Map.of("type", "string", "description", "World ID，仅字母数字连字符"),
                                "name", Map.of("type", "string", "description", "World 显示名称")),
                "required", List.of("worldId", "name"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = call.param("worldId", "").trim();
        String name = call.param("name", "").trim();

        if (worldId.isEmpty()) return ToolResult.fail(name(), "worldId 不能为空");
        if (!worldId.matches("^[a-zA-Z0-9_-]+$")) {
            return ToolResult.fail(name(), "worldId 只能包含字母、数字、连字符");
        }
        if (name.isEmpty()) return ToolResult.fail(name(), "name 不能为空");

        // 检查是否已存在
        WorldIndexManager.WorldMeta existing = WorldIndexManager.loadWorldMeta(worldsDir, worldId);
        if (existing != null) {
            return ToolResult.fail(name(), "World 已存在: " + worldId + " (" + existing.name() + ")");
        }

        try {
            WorldIndexManager.WorldMeta meta = WorldIndexManager.createWorld(worldsDir, worldId, name);
            String snippet = "新 World 已创建\n"
                    + "id: " + meta.id() + "\n"
                    + "name: " + meta.name() + "\n"
                    + "root node: n0000\n"
                    + "创建时间: " + meta.createdAt() + "\n"
                    + "路径: " + worldsDir.resolve(worldId) + "\n\n"
                    + "使用 world_switch 切换到新 World。";
            return ToolResult.ok(name(), List.of(new ToolResult.Item(name, worldId, snippet, 1.0)));
        } catch (Exception e) {
            return ToolResult.fail(name(), "创建失败: " + e.getMessage());
        }
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
