package com.gsim.doc.tool;

import com.gsim.doc.DocStore;
import com.gsim.doc.Document;
import com.gsim.llm.EmbeddingClient;
import com.gsim.skill.SkillIndex;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 索引文档 — 提取文档摘要，计算 embedding 向量并存入索引。
 * 复用现有 SkillIndex 作为索引引擎。
 */
public final class DocIndexTool implements AgentTool {

    private final DocStore store;
    private final SkillIndex index;
    private final EmbeddingClient embeddingClient;

    public DocIndexTool(DocStore store, SkillIndex index, EmbeddingClient embeddingClient) {
        this.store = store;
        this.index = index;
        this.embeddingClient = embeddingClient;
    }

    @Override
    public String name() { return "doc_index"; }

    @Override
    public String description() {
        return "为指定文档建立语义索引：提取文本摘要，计算 embedding 向量并存入索引数据库。"
                + "参数: docId (文档 ID, 必填)。"
                + (embeddingClient != null && embeddingClient.isConfigured()
                        ? " 当前使用 embedding 向量索引。"
                        : " 当前仅保存文本摘要（无向量），搜索降级为关键词匹配。");
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "docId", Map.of("type", "string", "description", "文档 ID")
                ),
                "required", List.of("docId")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String docId = call.param("docId", "").trim();
        if (docId.isEmpty()) return ToolResult.fail(name(), "docId 不能为空");
        if (!docId.matches("^[a-zA-Z0-9_-]+$")) {
            return ToolResult.fail(name(), "docId 只能包含字母、数字、连字符、下划线");
        }

        Document doc = store.get(docId);
        if (doc == null) return ToolResult.fail(name(), "文档不存在: " + docId);

        try {
            String summary = doc.embeddingText();

            float[] vector = null;
            String vecInfo = "无 embedding 向量";

            if (embeddingClient != null && embeddingClient.isConfigured()) {
                long start = System.currentTimeMillis();
                vector = embeddingClient.embed(summary);
                long elapsed = System.currentTimeMillis() - start;
                vecInfo = "向量维度: " + vector.length + " (耗时 " + elapsed + "ms)";
            }

            index.upsert(docId, doc.title(), summary, vector);

            String snippet = "标题: " + doc.title()
                    + "\n类型: " + doc.type().key()
                    + "\n摘要: " + summary
                    + "\n" + vecInfo
                    + "\n索引总数: " + index.count();

            return ToolResult.ok(name(), List.of(new ToolResult.Item(
                    doc.title(), docId, snippet, 1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "索引失败: " + e.getMessage());
        }
    }
}
