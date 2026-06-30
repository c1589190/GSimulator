package com.gsim.doc.tool;

import com.gsim.doc.DocStore;
import com.gsim.doc.DocType;
import com.gsim.doc.Document;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 创建新文档 — 在 docs/ 下创建对应类型的 .md 文件。
 */
public final class DocCreateTool implements AgentTool {

    private final DocStore store;
    private final com.gsim.doc.DocCacheManager cacheManager;
    private final com.gsim.agent.AgentProgressSink progressSink;

    public DocCreateTool(DocStore store, com.gsim.doc.DocCacheManager cacheManager,
                          com.gsim.agent.AgentProgressSink progressSink) {
        this.store = store;
        this.cacheManager = cacheManager;
        this.progressSink = progressSink;
    }

    @Override
    public String name() { return "doc_create"; }

    @Override
    public String description() {
        return "创建新文档。参数: docId (唯一 ID, 必填, 仅字母数字连字符下划线), "
                + "type (character/skill/world_state/template/context/rule, 必填), "
                + "title (标题, 必填), content (Markdown 正文, 可选), tags (逗号分隔标签, 可选)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "docId", Map.of("type", "string",
                                "description", "文档唯一 ID，仅字母数字连字符下划线"),
                        "type", Map.of("type", "string",
                                "description", "文档类型: character, skill, world_state, template, context, rule"),
                        "title", Map.of("type", "string", "description", "文档标题"),
                        "content", Map.of("type", "string", "description", "Markdown 正文（可选）"),
                        "tags", Map.of("type", "string", "description", "标签，逗号分隔（可选）")
                ),
                "required", List.of("docId", "title")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String docId = call.param("docId", "").trim();
        String typeStr = call.param("type", "other").trim();
        String title = call.param("title", "").trim();
        String content = call.param("content", "").trim();
        String tagsStr = call.param("tags", "").trim();

        if (docId.isEmpty()) return ToolResult.fail(name(), "docId 不能为空");
        if (!docId.matches("^[a-zA-Z0-9_-]+$")) {
            return ToolResult.fail(name(), "docId 只能包含字母、数字、连字符、下划线");
        }
        if (title.isEmpty()) return ToolResult.fail(name(), "title 不能为空");

        // 解析 @cache: 引用
        content = cacheManager.resolve(content);

        DocType type = typeStr.isEmpty() ? DocType.OTHER : DocType.fromKey(typeStr);

        List<String> tags = List.of();
        if (!tagsStr.isEmpty()) {
            tags = List.of(tagsStr.split("\\s*,\\s*"));
        }

        try {
            Document doc = store.create(docId, type, title, content, tags);
            if (doc == null) {
                return ToolResult.fail(name(), "文档已存在: " + docId);
            }

            // board 类型 → 自动推送公开消息
            if (type == com.gsim.doc.DocType.BOARD && !content.isEmpty()) {
                progressSink.onProgress(com.gsim.agent.AgentProgressEvent.publicMessage(
                        "\n📋 " + title + "\n" + content));
            }

            return ToolResult.ok(name(), List.of(new ToolResult.Item(
                    title, docId,
                    "已创建文档: type=" + type.key() + " id=" + docId + " v" + doc.version(),
                    1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "创建失败: " + e.getMessage());
        }
    }
}
