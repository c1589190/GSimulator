package com.gsim.skill.tool;

import com.gsim.skill.SkillMeta;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 创建新 Skill — 在 skills/ 下创建文件夹和 SKILL.md 模板文件。
 */
public final class SkillCreateTool implements AgentTool {

    private final Path skillsDir;

    public SkillCreateTool(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    @Override
    public String name() { return "skill_create"; }

    @Override
    public String description() {
        return "创建一个新 Skill：在 skills/ 下创建文件夹和 SKILL.md 模板。"
                + "参数: skillId (文件夹名, 必填, 仅字母数字连字符), "
                + "name (显示名称, 必填), description (一句话描述, 必填), content (初始正文, 可选)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "skillId", Map.of("type", "string",
                                "description", "Skill 文件夹名，仅字母数字连字符"),
                        "name", Map.of("type", "string", "description", "Skill 显示名称"),
                        "description", Map.of("type", "string", "description", "一句话描述这个 Skill 做什么"),
                        "content", Map.of("type", "string", "description", "初始正文内容（可选）")
                ),
                "required", List.of("skillId", "name", "description")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String skillId = call.param("skillId", "").trim();
        String name = call.param("name", "").trim();
        String description = call.param("description", "").trim();
        String content = call.param("content", "").trim();

        if (skillId.isEmpty()) return ToolResult.fail(name(), "skillId 不能为空");
        if (!skillId.matches("^[a-zA-Z0-9-]+$")) {
            return ToolResult.fail(name(), "skillId 只能包含字母、数字、连字符");
        }
        if (name.isEmpty()) return ToolResult.fail(name(), "name 不能为空");
        if (description.isEmpty()) return ToolResult.fail(name(), "description 不能为空");

        Path skillDir = skillsDir.resolve(skillId);
        if (Files.isDirectory(skillDir)) {
            return ToolResult.fail(name(), "Skill 已存在: " + skillId);
        }

        try {
            Files.createDirectories(skillDir);
            SkillMeta meta = new SkillMeta(name, description, Map.of());
            String mdContent = meta.toFrontmatter() + "\n" + (content.isEmpty() ? "# " + name + "\n" : content) + "\n";
            Path mdFile = skillDir.resolve("SKILL.md");
            Files.writeString(mdFile, mdContent);

            return ToolResult.ok(name(), List.of(new ToolResult.Item(
                    name, skillId,
                    "Skill 已创建: " + mdFile + "\n" + meta.toFrontmatter(),
                    1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "创建失败: " + e.getMessage());
        }
    }
}
