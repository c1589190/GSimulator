package com.gsim.ref;

import com.gsim.doc.DocCacheManager;
import com.gsim.doc.DocStore;
import com.gsim.ref.RefResolver.ResolvedRef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * resolve_ref — 统一 @ 引用解析工具，LLM 可通过 @import:/@world:/@doc: 格式读取任意来源的文档/元素。
 *
 * <p>内容超过 200 字符时自动缓存到 @cache: 以节省上下文。
 */
public final class ResolveRefTool implements AgentTool {

    private final Path worldsDir;
    private final String activeWorldId;
    private final Path importDir;
    private final DocStore docStore;
    private final DocCacheManager cacheManager;

    public ResolveRefTool(Path worldsDir, String activeWorldId, Path importDir,
                          DocStore docStore, DocCacheManager cacheManager) {
        this.worldsDir = worldsDir;
        this.activeWorldId = activeWorldId;
        this.importDir = importDir;
        this.docStore = docStore;
        this.cacheManager = cacheManager;
    }

    @Override
    public String name() { return "resolve_ref"; }

    @Override
    public String description() {
        return """
            用统一的 @ 引用语法读取任意来源的文档或元素。
            ref 格式:
              @import:<documentId>           — 读取导入文档 (import_document_read)
              @world:<nodeId>:<cpId>:<key>  — 读取指定节点的 World 元素
              @world:<cpId>:<key>           — 读取当前活跃节点的 World 元素
              @doc:<docId>                  — 读取 Doc/Board 文档
            """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "ref", Map.of("type", "string",
                                "description", "引用字符串: @import:<id> / @world:<ref> / @doc:<id>")
                ),
                "required", List.of("ref")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String ref = call.param("ref", "").trim();
        if (ref.isEmpty()) {
            return ToolResult.fail(name(), "ref is required");
        }

        try {
            ResolvedRef resolved = RefResolver.resolve(ref,
                    worldsDir, activeWorldId, importDir, docStore);

            String cacheId = null;
            if (resolved.content().length() > 200 && cacheManager != null) {
                try {
                    cacheId = cacheManager.put("ref", resolved.content());
                } catch (IOException ignored) {
                }
            }

            StringBuilder sb = new StringBuilder();
            if (cacheId != null) {
                sb.append("[@cache:").append(cacheId).append("]\n");
            }
            sb.append(resolved.content());
            if (cacheId != null) {
                sb.append("\n\n---\n使用 @cache:").append(cacheId)
                        .append(" 在后续工具调用中引用此文本。");
            }

            return ToolResult.ok(name(), List.of(new ToolResult.Item(
                    resolved.title() + " [" + resolved.source() + "]",
                    resolved.id(), sb.toString(), 1.0)));

        } catch (IllegalArgumentException e) {
            return ToolResult.fail(name(), e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail(name(), "Failed to resolve ref: " + e.getMessage());
        }
    }
}
