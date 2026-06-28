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
 * 分段读取 Skill 内容 — 支持 offset/limit 按行读取。
 */
public final class SkillReadTool implements AgentTool {

    private final Path skillsDir;

    public SkillReadTool(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    @Override
    public String name() { return "skill_read"; }

    @Override
    public String description() {
        return "分段读取 Skill 文件内容。参数: skillId (必填), offset (起始行号, 默认0), limit (行数, 默认200)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "skillId", Map.of("type", "string", "description", "Skill 文件夹名"),
                        "offset", Map.of("type", "integer", "description", "起始行号 (0-based)，默认 0"),
                        "limit", Map.of("type", "integer", "description", "读取行数，默认 200，最大 500")
                ),
                "required", List.of("skillId")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String skillId = call.param("skillId", "").trim();
        if (skillId.isEmpty()) return ToolResult.fail(name(), "skillId 不能为空");

        // 安全检查：只允许 a-z 0-9 -
        if (!skillId.matches("^[a-zA-Z0-9-]+$")) {
            return ToolResult.fail(name(), "skillId 只能包含字母、数字、连字符");
        }

        Path mdFile = skillsDir.resolve(skillId).resolve("SKILL.md");
        if (!Files.isRegularFile(mdFile)) {
            return ToolResult.fail(name(), "Skill 不存在: " + skillId);
        }

        int offset = parseInt(call.param("offset"), 0);
        int limit = Math.min(parseInt(call.param("limit"), 200), 500);

        try {
            List<String> allLines = Files.readAllLines(mdFile);
            int totalLines = allLines.size();
            int start = Math.max(0, Math.min(offset, totalLines));
            int end = Math.min(start + limit, totalLines);

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(String.format("%6d| ", i)).append(allLines.get(i)).append("\n");
            }

            String snippet = sb.toString();
            if (snippet.isEmpty()) {
                snippet = "(文件为空)";
            }

            String title = "SKILL.md (" + skillId + ") lines " + start + "-" + (end - 1)
                    + " / " + totalLines;
            return ToolResult.ok(name(), List.of(new ToolResult.Item(title, skillId, snippet, 1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "读取失败: " + e.getMessage());
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
