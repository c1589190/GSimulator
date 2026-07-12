package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.loader.WorldIndexManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 列出所有 World。
 */
public final class WorldListTool implements AgentTool {

    private final Path worldsDir;
    private final Supplier<String> activeWorldId;

    public WorldListTool(Path worldsDir, Supplier<String> activeWorldId) {
        this.worldsDir = worldsDir;
        this.activeWorldId = activeWorldId;
    }

    @Override
    public String name() { return "world_list"; }

    @Override
    public String description() {
        return "列出所有已创建的 World（独立根节点世界），含 id、名称、创建时间。当前活跃 world 标记为 (active)。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        List<WorldIndexManager.WorldEntry> worlds = WorldIndexManager.listWorlds(worldsDir);
        if (worlds.isEmpty()) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("(empty)", "", "尚无任何 World", 0)));
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
}
