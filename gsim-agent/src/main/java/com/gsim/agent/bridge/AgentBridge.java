package com.gsim.agent.bridge;

import com.gsim.agent.tools.doc.DocCreateTool;
import com.gsim.agent.tools.doc.DocCropTool;
import com.gsim.agent.tools.doc.DocDeleteTool;
import com.gsim.agent.tools.doc.DocIndexTool;
import com.gsim.agent.tools.doc.DocListTool;
import com.gsim.agent.tools.doc.DocReadTool;
import com.gsim.agent.tools.doc.DocSearchTool;
import com.gsim.agent.tools.doc.DocTemplateTool;
import com.gsim.agent.tools.doc.DocWriteTool;
import com.gsim.agent.tools.importing.ImportDocumentListTool;
import com.gsim.agent.tools.importing.ImportDocumentReadTool;
import com.gsim.agent.tools.importing.ImportDocumentSearchTool;
import com.gsim.agent.tools.map.GsimapToolRegistrar;
import com.gsim.agent.tools.ref.ResolveRefTool;
import com.gsim.agent.tools.search.GsimSearchDocTool;
import com.gsim.agent.tools.search.GsimSearchTool;
import com.gsim.agent.tools.search.GsimSearchWorldTool;
import com.gsim.agent.tools.search.GsimapSearchHexTool;
import com.gsim.agent.tools.search.GsimapSearchRegionTool;
import com.gsim.agent.tools.search.SearchToolContext;
import com.gsim.agent.tools.text.TextEditTool;
import com.gsim.agent.tools.worldinfo.AttachmentReadTool;
import com.gsim.agent.tools.worldinfo.AttachmentWriteTool;
import com.gsim.agent.tools.worldinfo.CreateCheckpointTool;
import com.gsim.agent.tools.worldinfo.NodeCreateTool;
import com.gsim.agent.tools.worldinfo.NodeListTool;
import com.gsim.agent.tools.worldinfo.NodeStatusTool;
import com.gsim.agent.tools.worldinfo.QueryAddressTool;
import com.gsim.agent.tools.worldinfo.QueryByTagTool;
import com.gsim.agent.tools.worldinfo.QueryCheckpointTool;
import com.gsim.agent.tools.worldinfo.QueryElementTool;
import com.gsim.agent.tools.worldinfo.QueryKeywordTool;
import com.gsim.agent.tools.worldinfo.QueryNodeTool;
import com.gsim.agent.tools.worldinfo.WorldCreateTool;
import com.gsim.agent.tools.worldinfo.WorldListTool;
import com.gsim.agent.tools.worldinfo.WriteElementTool;
import com.gsim.agentlib.mcp.GsimRequestContext;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.WorldManager;
import com.gsim.map.service.MapService;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gsim-agent 桥接注册层（AgentBridge）。
 *
 * <p>外部程序（gsim-app 及未来的第三方宿主）复用 gsim-agent 时，按
 * {@code AgentBridge.registerXxx} 组装：先由宿主构造业务对象（DocStore、
 * WorldInformation 等，来自 gsim-core 接口），再调用本类注册全部工具。
 * 工具实现（AgentTool）全部位于 gsim-agent，宿主不再直接 import 任何工具实现类。
 *
 * <p>四个入口：
 * <ul>
 *   <li>{@link #registerCoreTools} — importing 3 + doc 9 + ref 1 + text 1 = 14 个工具（始终注册）</li>
 *   <li>{@link #registerWorldInfoTools} — worldinfo/node 管理工具（worldInfo 为 null 时仅注册 WorldList/WorldCreate）</li>
 *   <li>{@link #registerMapTools} — 委托 GsimapToolRegistrar 注册全部地图工具</li>
 *   <li>{@link #registerSearchTools} — 领域搜索工具：4 个细化搜索工具 + gsim_search 聚合器（T8 接线）</li>
 * </ul>
 */
public final class AgentBridge {

    private static final Logger log = LoggerFactory.getLogger(AgentBridge.class);

    /**
     * 跨消费方共享的按世界缓存（T8 修复）：非活跃世界（请求 worldId ≠ baseSupplier 当前世界）
     * 的 WorldInformation 统一经此缓存加载。搜索工具（Main 组装的 SearchToolContext.wiSupplier）
     * 与 worldinfo 写入工具（registerWorldInfoTools 的 wiSupplier）必须看到同一实例——
     * 否则同一世界出现两份 WorldInformation，写入侧（write_element 原地 upsert）与读取侧
     * （搜索/链接改写 findLink）各自持有一份，索引/linkIndex 互相不可见（F3 实机复现）。
     */
    private static final ConcurrentHashMap<String, WorldInformation> SHARED_WORLD_INFO_CACHE =
            new ConcurrentHashMap<>();

    private AgentBridge() {}

    /**
     * 注册核心工具（Import/Doc/Ref/TextEdit），始终注册，不依赖 agent 模式。
     *
     * @param toolRegistry 工具注册中心
     * @param ctx          业务对象上下文（由宿主组装）
     */
    public static void registerCoreTools(ToolRegistry toolRegistry, CoreToolContext ctx) {
        // Import doc tools
        toolRegistry.register(new ImportDocumentListTool(ctx.importDocService()));
        toolRegistry.register(new ImportDocumentReadTool(ctx.importDocService()));
        toolRegistry.register(new ImportDocumentSearchTool(ctx.importDocService()));

        // ── 统一文档管理工具（docs 工具组）──
        toolRegistry.register(new DocListTool(ctx.docStore()));
        toolRegistry.register(new DocReadTool(ctx.docStore(), ctx.docCacheManager()));
        toolRegistry.register(new DocCreateTool(ctx.docStore(), ctx.docCacheManager(), ctx.progressSink()));
        toolRegistry.register(new DocWriteTool(ctx.docStore(), ctx.docCacheManager(), ctx.progressSink()));
        toolRegistry.register(new DocSearchTool(ctx.docStore(), ctx.docIndex(), ctx.embeddingClient()));
        toolRegistry.register(new DocIndexTool(ctx.docStore(), ctx.docIndex(), ctx.embeddingClient()));
        toolRegistry.register(new DocCropTool(ctx.docStore(), ctx.docCacheManager()));
        toolRegistry.register(new DocTemplateTool(ctx.docStore(), ctx.docCacheManager()));
        toolRegistry.register(new DocDeleteTool(ctx.docStore()));

        // 统一 @ 引用解析
        toolRegistry.register(new ResolveRefTool(
                ctx.resolverRegistry(),
                ctx.worldsDir(),
                ctx.activeWorldId().get(),
                ctx.importDir(),
                ctx.docStore(),
                ctx.docCacheManager()));

        // 通用文本编辑工具（@cache: ← text_edit → @cache:）
        toolRegistry.register(new TextEditTool(
                ctx.resolverRegistry(),
                ctx.worldsDir(),
                ctx.activeWorldId().get(),
                ctx.importDir(),
                ctx.docStore(),
                ctx.docCacheManager()));

        log.info("Registered import + 9 docs + ref + text_edit core tools (docsDir={})", ctx.docsDir());
    }

    /**
     * 注册 world info + node 管理工具。
     *
     * <p>worldInfoSupplier 返回 null（Bootstrap 未产出）时仅注册 WorldListTool/WorldCreateTool 并告警。
     * wiSupplier 逻辑与迁移前一致：按 MCP 请求的 worldId 解析 WorldInformation，
     * 避免跨 world 共享导致数据污染；供应器每次调用取最新实例，节点创建/世界切换后
     * 重建的 WorldInformation 对工具可见（与迁移前动态字段读语义等价）。
     *
     * @param toolRegistry 工具注册中心
     * @param ctx          业务对象上下文（由宿主组装）
     */
    public static void registerWorldInfoTools(ToolRegistry toolRegistry, WorldInfoToolContext ctx) {
        // World management tools — don't depend on WorldInformation being loaded
        toolRegistry.register(new WorldListTool(ctx.worldsDir(), ctx.activeWorldId()));
        toolRegistry.register(new WorldCreateTool(ctx.worldsDir()));

        WorldInformation wi = ctx.worldInfoSupplier().get();
        if (wi == null) {
            log.warn("WorldInformation not available, skipping world info tool registration");
            return;
        }
        Supplier<WorldInformation> wiSupplier = createWorldInfoSupplier(ctx.worldInfoSupplier(), ctx.worldsDir());

        // Query tools
        toolRegistry.register(new QueryCheckpointTool(wiSupplier, ctx.docStore(), ctx.coreConfig()));
        toolRegistry.register(new QueryKeywordTool(wiSupplier));
        toolRegistry.register(new QueryNodeTool(wiSupplier, ctx.docStore(), ctx.coreConfig()));
        toolRegistry.register(new QueryElementTool(wiSupplier, toolRegistry, ctx.docStore(), ctx.coreConfig()));
        toolRegistry.register(new QueryByTagTool(wiSupplier, ctx.docStore(), ctx.coreConfig()));
        toolRegistry.register(new QueryAddressTool(wiSupplier, toolRegistry, ctx.docStore(), ctx.coreConfig()));

        // Write tools
        toolRegistry.register(new WriteElementTool(
                wiSupplier,
                ctx.worldsDir(),
                ctx.docCacheManager(),
                ctx.docStore(),
                ctx.inlineRefResolver(),
                ctx.coreConfig()));
        toolRegistry.register(new CreateCheckpointTool(wiSupplier, ctx.worldsDir()));
        toolRegistry.register(new AttachmentWriteTool(ctx.worldsDir(), wiSupplier));
        toolRegistry.register(new AttachmentReadTool(ctx.worldsDir(), wiSupplier));

        // Node management tools
        toolRegistry.register(new NodeListTool(wiSupplier));
        toolRegistry.register(new NodeStatusTool(wiSupplier));
        toolRegistry.register(new NodeCreateTool(wiSupplier, ctx.worldsDir(), ctx.onNodeChanged()));

        log.info("Registered 14 world info + node + world mgmt tools");
    }

    /**
     * 创建世界信息供应器（worldinfo 工具与搜索工具共用同一按世界缓存）。
     *
     * <p>语义（迁移前闭包的公开化）：按 {@link GsimRequestContext#worldId()} 解析——
     * 请求世界与 {@code baseSupplier} 当前世界一致 → 返回 base 实例（应用侧可变
     * WorldInformation，write_element 原地更新，节点创建/世界切换后重建对工具可见）；
     * 其他世界 → {@link #SHARED_WORLD_INFO_CACHE} 按世界缓存磁盘加载实例。共享缓存保证
     * 多个消费方（write_element 与搜索/链接读取）对同一非活跃世界持有同一实例，
     * 避免双实例导致索引/linkIndex 互相不可见（F3 实机复现的分裂缺陷）。
     *
     * @param baseSupplier 当前（活跃）世界实例供应器（可为 null——此时仅缓存路径可用）
     * @param worldsDir    世界目录根路径（磁盘加载入口，所有读取统一经 WorldManager）
     * @return 世界信息供应器
     */
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

    /**
     * 注册全部地图工具（委托 GsimapToolRegistrar，共 25 个）。
     *
     * @param toolRegistry 工具注册中心
     * @param mapService   共享的 MapService 实例
     * @param searchCtx    共享的搜索工具上下文（T8 接线传递；GsimapToolRegistrar 当前
     *                     不使用，T9 renameRegion 传播将在此消费）
     */
    public static void registerMapTools(ToolRegistry toolRegistry, MapService mapService, SearchToolContext searchCtx) {
        GsimapToolRegistrar.registerAll(toolRegistry, mapService, searchCtx);
    }

    /**
     * 注册领域搜索工具（T8 独占接线）：T4-T7 的 4 个细化搜索工具 + gsim_search 聚合器。
     *
     * <p>四个细化工具与聚合器共享同一 {@link SearchToolContext}（共享语料源，避免
     * ToolRegistry 往返解析）。仅本入口注册，不并入 registerWorldInfoTools/
     * registerCoreTools。
     *
     * @param toolRegistry 工具注册中心
     * @param ctx          共享的搜索工具上下文（由宿主组装）
     */
    public static void registerSearchTools(ToolRegistry toolRegistry, SearchToolContext ctx) {
        toolRegistry.register(new GsimSearchWorldTool(ctx));
        toolRegistry.register(new GsimapSearchRegionTool(ctx));
        toolRegistry.register(new GsimapSearchHexTool(ctx));
        toolRegistry.register(new GsimSearchDocTool(ctx));
        toolRegistry.register(new GsimSearchTool(ctx));

        log.info("Registered 5 search tools (gsim_search_world, gsimap_search_region, gsimap_search_hex, "
                + "gsim_search_doc, gsim_search)");
    }
}
