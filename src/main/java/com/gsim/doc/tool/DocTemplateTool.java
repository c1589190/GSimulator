package com.gsim.doc.tool;

import com.gsim.doc.DocStore;
import com.gsim.doc.DocType;
import com.gsim.doc.Document;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 从模板创建文档 — 读取 resources/gsim/templates/ 下的模板文件，
 * 替换 {{变量}} 后创建新文档。
 */
public final class DocTemplateTool implements AgentTool {

    private static final String TEMPLATE_BASE = "gsim/templates/";
    private final DocStore store;

    public DocTemplateTool(DocStore store) {
        this.store = store;
    }

    @Override
    public String name() { return "doc_template"; }

    @Override
    public String description() {
        return "从模板创建文档。参数: docId (新文档 ID, 必填), "
                + "template (模板文件名, 必填, 如 world-template.md), "
                + "type (文档类型, 默认 other), title (标题, 可选, 默认使用 docId), "
                + "vars (JSON 格式的 {{变量}} 替换表, 可选, 如 {\"name\":\"曹操\",\"era\":\"三国\"})。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "docId", Map.of("type", "string", "description", "新文档 ID"),
                        "template", Map.of("type", "string",
                                "description", "模板文件名，如 world-template.md, input-template.md"),
                        "type", Map.of("type", "string",
                                "description", "文档类型: character/skill/world_state/template/context/rule"),
                        "title", Map.of("type", "string", "description", "文档标题（默认使用 docId）"),
                        "vars", Map.of("type", "string",
                                "description", "JSON 格式变量表，如 {\"key\":\"value\"}")
                ),
                "required", List.of("docId", "template")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String docId = call.param("docId", "").trim();
        String templateName = call.param("template", "").trim();
        String typeStr = call.param("type", "other").trim();
        String title = call.param("title", "").trim();
        String varsJson = call.param("vars", "").trim();

        if (docId.isEmpty()) return ToolResult.fail(name(), "docId 不能为空");
        if (!docId.matches("^[a-zA-Z0-9_-]+$")) {
            return ToolResult.fail(name(), "docId 只能包含字母、数字、连字符、下划线");
        }
        if (templateName.isEmpty()) return ToolResult.fail(name(), "template 不能为空");

        // 安全校验：template 不能包含路径遍历
        if (templateName.contains("/") || templateName.contains("\\") || templateName.contains("..")) {
            return ToolResult.fail(name(), "template 不能包含路径分隔符");
        }

        // 加载模板
        String resourcePath = TEMPLATE_BASE + templateName;
        String templateText;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return ToolResult.fail(name(), "模板不存在: " + templateName
                        + "。可用模板在 resources/gsim/templates/ 下。");
            }
            templateText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.fail(name(), "读取模板失败: " + e.getMessage());
        }

        // 替换变量
        if (!varsJson.isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, String> vars = mapper.readValue(varsJson, Map.class);
                for (var entry : vars.entrySet()) {
                    templateText = templateText.replace(
                            "{{" + entry.getKey() + "}}",
                            entry.getValue() != null ? entry.getValue() : "");
                }
            } catch (Exception e) {
                return ToolResult.fail(name(), "vars JSON 解析失败: " + e.getMessage());
            }
        }

        DocType type = typeStr.isEmpty() ? DocType.OTHER : DocType.fromKey(typeStr);
        String finalTitle = title.isEmpty() ? docId : title;

        try {
            Document doc = store.create(docId, type, finalTitle, templateText, List.of("template"));
            if (doc == null) {
                return ToolResult.fail(name(), "文档已存在: " + docId);
            }
            return ToolResult.ok(name(), List.of(new ToolResult.Item(
                    finalTitle, docId,
                    "从模板 " + templateName + " 创建: type=" + type.key()
                            + " v" + doc.version(),
                    1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "创建失败: " + e.getMessage());
        }
    }
}
