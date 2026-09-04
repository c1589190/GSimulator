package com.gsim.core.tools.bridge;

import com.gsim.agentsmanager.mcp.GsimRequestContext;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.core.tools.doc.DocIndexTool;
import com.gsim.core.tools.doc.DocSearchTool;
import com.gsim.core.tools.importing.ImportDocumentListTool;
import com.gsim.core.tools.importing.ImportDocumentReadTool;
import com.gsim.core.tools.importing.ImportDocumentSearchTool;
import com.gsim.core.tools.ref.ResolveRefTool;
import com.gsim.core.tools.search.GsimSearchDocTool;
import com.gsim.core.tools.search.GsimSearchWorldTool;
import com.gsim.core.tools.search.SearchToolContext;
import com.gsim.core.tools.text.TextEditTool;
import com.gsim.core.tools.worldinfo.AttachmentReadTool;
import com.gsim.core.tools.worldinfo.AttachmentWriteTool;
import com.gsim.core.tools.worldinfo.CreateCheckpointTool;
import com.gsim.core.tools.worldinfo.NodeCreateTool;
import com.gsim.core.tools.worldinfo.NodeListTool;
import com.gsim.core.tools.worldinfo.NodeStatusTool;
import com.gsim.core.tools.worldinfo.QueryAddressTool;
import com.gsim.core.tools.worldinfo.QueryByTagTool;
import com.gsim.core.tools.worldinfo.QueryCheckpointTool;
import com.gsim.core.tools.worldinfo.QueryElementTool;
import com.gsim.core.tools.worldinfo.QueryKeywordTool;
import com.gsim.core.tools.worldinfo.QueryNodeTool;
import com.gsim.core.tools.worldinfo.WorldCreateTool;
import com.gsim.core.tools.worldinfo.WorldListTool;
import com.gsim.core.tools.worldinfo.WriteElementTool;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.WorldManager;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gsim-core 模块工具注册器 — 注册世界信息/引用/文本编辑/导入/知识库工具（模块自注册原则）。
 *
 * <p>doc 工具由 agentsmanager 的 DocModuleTools 注册；地图工具由 gsim-map 的 MapModuleToolProvider 注册；
 * 本类只负责 gsim-core 域。
 */
public final class CoreModuleTools {

    private static final Logger log = LoggerFactory.getLogger(CoreModuleTools.class);

    /** 按世界缓存磁盘加载的 WorldInformation 实例（非活跃世界共享，避免双实例分裂）。 */
    private static final ConcurrentHashMap<String, WorldInformation> SHARED_WORLD_INFO_CACHE =
            new ConcurrentHashMap<>();

    private CoreModuleTools() {}

    /** 注册导入文档/知识库/引用解析/文本编辑工具。 */
    public static void registerCoreTools(ToolRegistry toolRegistry, CoreToolContext ctx) {
        toolRegistry.register(new ImportDocumentListTool(ctx.importDocService()));
        toolRegistry.register(new ImportDocumentReadTool(ctx.importDocService()));
        toolRegistry.register(new ImportDocumentSearchTool(ctx.importDocService()));

        toolRegistry.register(new DocSearchTool(ctx.docStore(), ctx.docIndex(), ctx.embeddingClient()));
        toolRegistry.register(new DocIndexTool(ctx.docStore(), ctx.docIndex(), ctx.embeddingClient()));

        toolRegistry.register(new ResolveRefTool(
                ctx.resolverRegistry(),
                ctx.worldsDir(),
                ctx.activeWorldId().get(),
                ctx.importDir(),
                ctx.docStore(),
                ctx.docCacheManager()));

        toolRegistry.register(new TextEditTool(
                ctx.resolverRegistry(),
                ctx.worldsDir(),
                ctx.activeWorldId().get(),
                ctx.importDir(),
                ctx.docStore(),
                ctx.docCacheManager()));

        log.info("Registered core tools (import + knowledge + ref + text_edit)");
    }

    /** 注册世界信息 + 节点管理工具。 */
    public static void registerWorldInfoTools(ToolRegistry toolRegistry, WorldInfoToolContext ctx) {
        toolRegistry.register(new WorldListTool(ctx.worldsDir(), ctx.activeWorldId()));
        toolRegistry.register(new WorldCreateTool(ctx.worldsDir()));

        WorldInformation wi = ctx.worldInfoSupplier().get();
        if (wi == null) {
            log.warn("WorldInformation not available, skipping world info tool registration");
            return;
        }
        Supplier<WorldInformation> wiSupplier = createWorldInfoSupplier(ctx.worldInfoSupplier(), ctx.worldsDir());

        toolRegistry.register(new QueryCheckpointTool(wiSupplier, ctx.docStore(), ctx.coreConfig()));
        toolRegistry.register(new QueryKeywordTool(wiSupplier));
        toolRegistry.register(new QueryNodeTool(wiSupplier, ctx.docStore(), ctx.coreConfig()));
        toolRegistry.register(new QueryElementTool(wiSupplier, toolRegistry, ctx.docStore(), ctx.coreConfig()));
        toolRegistry.register(new QueryByTagTool(wiSupplier, ctx.docStore(), ctx.coreConfig()));
        toolRegistry.register(new QueryAddressTool(wiSupplier, toolRegistry, ctx.docStore(), ctx.coreConfig()));

        toolRegistry.register(
                new WriteElementTool(wiSupplier, ctx.worldsDir(), ctx.docCacheManager(), ctx.inlineRefResolver()));
        toolRegistry.register(new CreateCheckpointTool(wiSupplier, ctx.worldsDir()));
        toolRegistry.register(new AttachmentWriteTool(ctx.worldsDir(), wiSupplier));
        toolRegistry.register(new AttachmentReadTool(ctx.worldsDir(), wiSupplier));

        toolRegistry.register(new NodeListTool(wiSupplier));
        toolRegistry.register(new NodeStatusTool(wiSupplier));
        toolRegistry.register(new NodeCreateTool(wiSupplier, ctx.worldsDir(), ctx.onNodeChanged()));

        log.info("Registered world info + node + world mgmt tools");
    }

    /** 创建世界信息供应器（worldinfo 工具与搜索工具共用同一按世界缓存）。 */
    public static Supplier<WorldInformation> createWorldInfoSupplier(
            Supplier<WorldInformation> baseSupplier, Path worldsDir) {
        WorldManager worldManager = new WorldManager(worldsDir);
        return () -> {
            String reqWorldId = GsimRequestContext.worldId();
            WorldInformation current = baseSupplier.get();
            if (reqWorldId != null && current != null && !reqWorldId.equals(current.worldId())) {
                return SHARED_WORLD_INFO_CACHE.computeIfAbsent(reqWorldId, worldManager::loadWorld);
            }
            return current;
        };
    }

    /** 注册世界/文档域搜索工具（gsim_search_world / gsim_search_doc）。 */
    public static void registerSearchTools(ToolRegistry toolRegistry, SearchToolContext ctx) {
        toolRegistry.register(new GsimSearchWorldTool(ctx));
        toolRegistry.register(new GsimSearchDocTool(ctx));
        log.info("Registered world/doc search tools");
    }
}
