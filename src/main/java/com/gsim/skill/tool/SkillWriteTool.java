package com.gsim.skill.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 修改 Skill 内容 — 支持 replace（精确替换）、append（追加）、overwrite（覆盖 body）。
 *
 * <p>类似 Claude Code 的 Edit 工具：replace 模式要求 old_string 在文件中唯一。
 */
public final class SkillWriteTool implements AgentTool {

    private final Path skillsDir;

    public SkillWriteTool(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    @Override
    public String name() { return "skill_write"; }

    @Override
    public String description() {
        return "修改 SKILL.md 文件内容。mode: replace (精确替换), append (追加到末尾), overwrite (覆盖整个 body 不含 frontmatter)。"
                + "参数: skillId (必填), mode (必填, replace/append/overwrite), "
                + "old_string (replace 时必填), new_string (必填)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "skillId", Map.of("type", "string", "description", "Skill 文件夹名"),
                        "mode", Map.of("type", "string", "description", "replace / append / overwrite"),
                        "old_string", Map.of("type", "string", "description", "要替换的原字符串 (replace 模式)"),
                        "new_string", Map.of("type", "string", "description", "新字符串 / 追加内容 / 新 body 内容")
                ),
                "required", List.of("skillId", "mode", "new_string")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String skillId = call.param("skillId", "").trim();
        String mode = call.param("mode", "replace").trim().toLowerCase();
        String oldString = call.param("old_string", "");
        String newString = call.param("new_string", "");

        if (skillId.isEmpty()) return ToolResult.fail(name(), "skillId 不能为空");
        if (!skillId.matches("^[a-zA-Z0-9_-]+$")) {
            return ToolResult.fail(name(), "skillId 只能包含字母、数字、连字符");
        }

        Path mdFile = skillsDir.resolve(skillId).resolve("SKILL.md");
        if (!Files.isRegularFile(mdFile)) {
            return ToolResult.fail(name(), "Skill 不存在: " + skillId);
        }

        try {
            String content = Files.readString(mdFile);
            String newContent;
            String action;

            switch (mode) {
                case "replace" -> {
                    if (oldString.isEmpty()) {
                        return ToolResult.fail(name(), "replace 模式需要 old_string 参数");
                    }
                    int idx = content.indexOf(oldString);
                    if (idx < 0) {
                        return ToolResult.fail(name(), "old_string 未在文件中找到");
                    }
                    // 检查唯一性
                    int idx2 = content.indexOf(oldString, idx + 1);
                    if (idx2 >= 0) {
                        return ToolResult.fail(name(), "old_string 在文件中出现多次，请提供更精确的唯一匹配字符串");
                    }
                    newContent = content.substring(0, idx) + newString
                            + content.substring(idx + oldString.length());
                    action = "replaced at offset " + idx;
                }
                case "append" -> {
                    newContent = content + "\n" + newString;
                    action = "appended " + newString.lines().count() + " lines";
                }
                case "overwrite" -> {
                    // 保留 frontmatter，替换 body
                    String frontmatter = "";
                    String bodyStart = content;
                    if (content.startsWith("---")) {
                        int endFm = content.indexOf("---", 3);
                        if (endFm >= 0) {
                            frontmatter = content.substring(0, endFm + 3);
                            bodyStart = content.substring(endFm + 3);
                        }
                    }
                    newContent = frontmatter + "\n" + newString;
                    action = "body overwritten";
                }
                default -> {
                    return ToolResult.fail(name(), "未知 mode: " + mode + "，支持 replace / append / overwrite");
                }
            }

            Files.writeString(mdFile, newContent);

            int totalLines = newContent.lines().toList().size();
            return ToolResult.ok(name(), List.of(new ToolResult.Item(
                    skillId + " (" + action + ")", skillId,
                    "文件已更新，共 " + totalLines + " 行", 1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "写入失败: " + e.getMessage());
        }
    }
}
