package com.gsim.agent.tools.ref;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.doc.DocCacheManager;
import com.gsim.core.doc.DocStore;
import com.gsim.core.ref.RefResolver;
import com.gsim.core.ref.RefResolver.ResolvedRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * resolve_ref — 统一 @ 引用解析工具，LLM 可通过 @import:/@world:/@doc: 格式读取任意来源的文档/元素。
 */
public final class ResolveRefTool implements AgentTool {

    private final Path worldsDir;
    private final String activeWorldId;
    private final Path importDir;
    private final DocStore docStore;
    private final DocCacheManager cacheManager;

    /**
     * 构造引用解析工具。
     *
     * @param worldsDir     世界目录根路径
     * @param activeWorldId 当前活跃世界 ID
     * @param importDir     导入文档目录
     * @param docStore      文档存储实例
     * @param cacheManager  文档缓存管理器实例
     */
    public ResolveRefTool(
            Path worldsDir, String activeWorldId, Path importDir, DocStore docStore, DocCacheManager cacheManager) {
        this.worldsDir = worldsDir;
        this.activeWorldId = activeWorldId;
        this.importDir = importDir;
        this.docStore = docStore;
        this.cacheManager = cacheManager;
    }

    @Override
    public String name() {
        return "resolve_ref";
    }

    @Override
    public String description() {
        return """
            用统一的 @ 引用语法读取任意来源的文档或元素。
            ref 格式:
              @import:<documentId>           — 读取导入文档 (import_document_read)
              @world:<nodeId>:<cpId>:<key>  — 读取指定节点的 World 元素
              @world:<cpId>:<key>           — 读取当前活跃节点的 World 元素
              @doc:<docId>                  — 读取 Doc/Board 文档
              @cache:<id>                   — 读取缓存文本 (大段工具输出的短引用)
            """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "ref",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "引用字符串: @import:<id> / @world:<ref> / @doc:<id> / @cache:<id>")),
                "required", List.of("ref"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String ref = call.param("ref", "").trim();
        if (ref.isEmpty()) {
            return ToolResult.fail(name(), "ref is required");
        }

        try {
            // 从 ToolCall 参数获取 worldId，支持活跃 World 动态切换后的解析
            String worldId = call.param("worldId", activeWorldId);
            Path cacheDir = worldsDir.resolveSibling("docs").resolve(".cache");
            ResolvedRef resolved = RefResolver.resolve(ref, worldsDir, worldId, importDir, docStore, cacheDir);

            StringBuilder sb = new StringBuilder();
            sb.append(resolved.content());

            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            resolved.title() + " [" + resolved.source() + "]", resolved.id(), sb.toString(), 1.0)));

        } catch (IllegalArgumentException e) {
            return ToolResult.fail(name(), e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail(name(), "Failed to resolve ref: " + e.getMessage());
        }
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
