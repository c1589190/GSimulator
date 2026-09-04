package com.gsim.core.tools.worldinfo;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import com.gsim.core.worldinfo.loader.WorldManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * world_list -- 列出所有已创建的 World。
 *
 * <p>读取 _index.json 返回所有已注册世界的列表，包含每个世界的 ID、名称和创建时间。
 * 当前活跃世界标记为 (active)。
 *
 * <p>返回值按名称排序，活跃世界具有最高相关性评分（1.0），
 * 其他世界评分为 0.5。
 */
public final class WorldListTool implements AgentTool {

    private final Path worldsDir;
    private final Supplier<String> activeWorldId;

    public WorldListTool(Path worldsDir, Supplier<String> activeWorldId) {
        this.worldsDir = worldsDir;
        this.activeWorldId = activeWorldId;
    }

    @Override
    public String name() {
        return "world_list";
    }

    @Override
    public String description() {
        return "列出所有已创建的 World（独立根节点世界），含 id、名称、创建时间。当前活跃 world 标记为 (active)。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        List<WorldIndexManager.WorldEntry> worlds = new WorldManager(worldsDir).listWorlds();
        if (worlds.isEmpty()) {
            return ToolResult.ok(name(), List.of(new ToolResult.Item("(empty)", "", "尚无任何 World", 0)));
        }

        String active = activeWorldId.get();
        List<ToolResult.Item> items = new ArrayList<>();
        for (var w : worlds) {
            boolean isActive = w.id().equals(active);
            String title = w.name() + (isActive ? " (active)" : "");
            String snippet = "id=" + w.id() + " | created=" + w.createdAt();
            items.add(new ToolResult.Item(title, w.id(), snippet, isActive ? 1.0 : 0.5));
        }
        return ToolResult.ok(name(), items);
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
