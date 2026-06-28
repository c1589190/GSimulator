package com.gsim.skill.tool;

import com.gsim.skill.SkillIndex;
import com.gsim.skill.SkillMeta;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 列出所有 Skill — 遍历 skills/ 目录，返回名称、描述、大小、是否已索引。
 */
public final class SkillListTool implements AgentTool {

    private final Path skillsDir;
    private final SkillIndex index;

    public SkillListTool(Path skillsDir, SkillIndex index) {
        this.skillsDir = skillsDir;
        this.index = index;
    }

    @Override
    public String name() { return "skill_list"; }

    @Override
    public String description() {
        return "列出所有已创建的 Skill，含名称、描述、文件大小、是否已建立语义索引。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!Files.isDirectory(skillsDir)) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("(empty)", skillsDir.toString(), "skills 目录尚未创建", 0)));
        }

        List<ToolResult.Item> items = new ArrayList<>();
        try (var dirs = Files.newDirectoryStream(skillsDir, p ->
                Files.isDirectory(p) && !p.getFileName().toString().startsWith("."))) {
            for (Path dir : dirs) {
                String skillId = dir.getFileName().toString();
                Path mdFile = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(mdFile)) continue;

                SkillMeta meta;
                try {
                    meta = SkillMeta.fromFile(mdFile);
                } catch (IOException e) {
                    meta = new SkillMeta(skillId, "(读取失败)", java.util.Map.of());
                }

                long size = 0;
                try { size = Files.size(mdFile); } catch (IOException ignored) {}
                boolean indexed = index.isIndexed(skillId);

                String snippet = meta.description()
                        + " | size=" + size + "B"
                        + " | indexed=" + (indexed ? "yes" : "no");
                items.add(new ToolResult.Item(meta.name(), skillId, snippet, 0));
            }
        } catch (IOException e) {
            return ToolResult.fail(name(), "无法读取 skills 目录: " + e.getMessage());
        }

        if (items.isEmpty()) {
            items.add(new ToolResult.Item("(empty)", skillsDir.toString(), "尚无任何 Skill", 0));
        }
        return ToolResult.ok(name(), items);
    }
}
