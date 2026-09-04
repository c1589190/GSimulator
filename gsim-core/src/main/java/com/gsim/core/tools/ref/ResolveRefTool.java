package com.gsim.core.tools.ref;

import com.gsim.agentsmanager.ref.RefResolver.ResolvedRef;
import com.gsim.agentsmanager.ref.ResolverContext;
import com.gsim.agentsmanager.ref.ResolverRegistry;
import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocCacheManager;
import com.gsim.docslib.doc.DocStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * resolve_ref — 统一 @ 引用解析工具，LLM 可通过 @import:/@world:/@doc:/@cache:/gsimap: 格式读取任意来源的文档/元素。
 */
public final class ResolveRefTool implements AgentTool {

    private final ResolverRegistry registry;
    private final Path worldsDir;
    private final String activeWorldId;
    private final Path importDir;
    private final DocStore docStore;
    private final DocCacheManager cacheManager;

    /**
     * 构造引用解析工具。
     *
     * @param registry      统一引用解析注册中心（内置 @world/@doc/@cache/@import/裸引用 + 应用层 gsimap:）
     * @param worldsDir     世界目录根路径
     * @param activeWorldId 当前活跃世界 ID
     * @param importDir     导入文档目录
     * @param docStore      文档存储实例
     * @param cacheManager  文档缓存管理器实例
     */
    public ResolveRefTool(
            ResolverRegistry registry,
            Path worldsDir,
            String activeWorldId,
            Path importDir,
            DocStore docStore,
            DocCacheManager cacheManager) {
        this.registry = registry;
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
              gsimap:region:<name>          — 读取地图区域
              gsimap:hex:<q>_<r>            — 读取地图单格
              gsimap:city:<name>            — 读取地图城市
              gsimap:terrain:<key>          — 读取地形定义
              <nodeId>:<cpId>:<key>         — 读取指定节点的 World 元素（裸引用）
              <cpId>:<key>                  — 读取当前活跃节点的 World 元素（裸引用）
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
            // 与 DocCacheManager 共用同一缓存目录（由宿主按 docsDir 注入）
            Path cacheDir = cacheManager.cacheDir();
            ResolverContext ctx = ResolverContext.of(worldsDir, worldId, importDir, docStore, cacheDir);
            ResolvedRef resolved = registry.resolve(ref, ctx);

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
