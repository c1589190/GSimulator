package com.gsim.skill.tool;

import com.gsim.llm.EmbeddingClient;
import com.gsim.skill.SkillIndex;
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
 * 索引 Skill — 读取 SKILL.md 的 frontmatter，生成 summary，计算 embedding 向量并存入 embdb。
 */
public final class SkillIndexTool implements AgentTool {

    private final Path skillsDir;
    private final SkillIndex index;
    private final EmbeddingClient embeddingClient;

    public SkillIndexTool(Path skillsDir, SkillIndex index, EmbeddingClient embeddingClient) {
        this.skillsDir = skillsDir;
        this.index = index;
        this.embeddingClient = embeddingClient;
    }

    @Override
    public String name() { return "skill_index"; }

    @Override
    public String description() {
        return "为指定 Skill 建立语义索引：提取描述文本，计算 embedding 向量并存入索引数据库。"
                + "参数: skillId (Skill 文件夹名, 必填)。"
                + (embeddingClient != null && embeddingClient.isConfigured()
                        ? " 当前使用 embedding 向量索引。"
                        : " 当前仅保存文本摘要（无 embedding 向量），搜索降级为关键词匹配。");
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "skillId", Map.of("type", "string", "description", "Skill 文件夹名")
                ),
                "required", List.of("skillId")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String skillId = call.param("skillId", "").trim();
        if (skillId.isEmpty()) return ToolResult.fail(name(), "skillId 不能为空");
        if (!skillId.matches("^[a-zA-Z0-9-]+$")) {
            return ToolResult.fail(name(), "skillId 只能包含字母、数字、连字符");
        }

        Path mdFile = skillsDir.resolve(skillId).resolve("SKILL.md");
        if (!Files.isRegularFile(mdFile)) {
            return ToolResult.fail(name(), "Skill 不存在: " + skillId);
        }

        try {
            SkillMeta meta = SkillMeta.fromFile(mdFile);
            String summary = meta.embeddingText();

            float[] vector = null;
            String vecInfo = "无 embedding 向量";

            if (embeddingClient != null && embeddingClient.isConfigured()) {
                long start = System.currentTimeMillis();
                vector = embeddingClient.embed(summary);
                long elapsed = System.currentTimeMillis() - start;
                vecInfo = "向量维度: " + vector.length + " (耗时 " + elapsed + "ms)";
            }

            index.upsert(skillId, meta.name(), summary, vector);

            String snippet = "名称: " + meta.name()
                    + "\n摘要: " + summary
                    + "\n" + vecInfo
                    + "\n索引总数: " + index.count();

            return ToolResult.ok(name(), List.of(new ToolResult.Item(
                    meta.name(), skillId, snippet, 1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "索引失败: " + e.getMessage());
        }
    }
}
