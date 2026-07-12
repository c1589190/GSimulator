package com.gsim.doc.tool;

import com.gsim.doc.DocStore;
import com.gsim.doc.DocType;
import com.gsim.doc.Document;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 列出文档 — 按 type/tag 过滤，返回摘要列表。
 */
public final class DocListTool implements AgentTool {

    private final DocStore store;

    public DocListTool(DocStore store) {
        this.store = store;
    }

    @Override
    public String name() { return "doc_list"; }

    @Override
    public String description() {
        return "列出所有文档，可按 type 或 tag 过滤。"
                + "参数: type (可选: character/skill/world_state/template/context/rule/other), "
                + "tag (可选标签过滤)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "type", Map.of("type", "string",
                                "description", "按文档类型过滤: character, skill, world_state, template, context, rule"),
                        "tag", Map.of("type", "string",
                                "description", "按标签过滤（可选）")
                ),
                "required", List.of()
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String typeStr = call.param("type", "").trim();
        String tag = call.param("tag", "").trim();

        DocType typeFilter = typeStr.isEmpty() ? null : DocType.fromKey(typeStr);
        List<Document> docs = store.list(typeFilter, tag.isEmpty() ? null : tag);

        if (docs.isEmpty()) {
            return ToolResult.ok(name(), List.of(new ToolResult.Item(
                    "(empty)", "docs", "暂无文档", 0)));
        }

        List<ToolResult.Item> items = new ArrayList<>();
        for (Document doc : docs) {
            String snippet = doc.summary()
                    + " | type=" + doc.type().key()
                    + " | v" + doc.version()
                    + " | tags=" + String.join(",", doc.tags());
            items.add(new ToolResult.Item(doc.title(), doc.id(), snippet, 0));
        }
        return ToolResult.ok(name(), items);
    }
}
