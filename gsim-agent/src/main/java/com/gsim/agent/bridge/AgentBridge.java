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
import java.util.Map;
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
 * <p>三个入口：
 * <ul>
 *   <li>{@link #registerCoreTools} — importing 3 + doc 9 + ref 1 + text 1 = 14 个工具（始终注册）</li>
 *   <li>{@link #registerWorldInfoTools} — worldinfo/node 管理工具（worldInfo 为 null 时仅注册 WorldList/WorldCreate）</li>
 *   <li>{@link #registerMapTools} — 委托 GsimapToolRegistrar 注册全部地图工具</li>
 * </ul>
 */
public final class AgentBridge {

    private static final Logger log = LoggerFactory.getLogger(AgentBridge.class);

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
     * wiCache/wiSupplier 逻辑与迁移前一致：按 MCP 请求的 worldId 解析 WorldInformation，
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
        // 按 worldId 解析 WorldInformation，避免跨 world 共享导致数据污染。
        // 所有磁盘读取统一经 WorldManager（WorldInfoBuilder.discover 的唯一入口）。
        WorldManager worldManager = new WorldManager(ctx.worldsDir());
        Map<String, WorldInformation> wiCache = new ConcurrentHashMap<>();
        Supplier<WorldInformation> wiSupplier = () -> {
            String reqWorldId = GsimRequestContext.worldId();
            WorldInformation current = ctx.worldInfoSupplier().get();
            if (reqWorldId != null && current != null && !reqWorldId.equals(current.worldId())) {
                return wiCache.computeIfAbsent(reqWorldId, worldManager::loadWorld);
            }
            return current;
        };

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
     * 注册全部地图工具（委托 GsimapToolRegistrar，共 25 个）。
     *
     * @param toolRegistry 工具注册中心
     * @param mapService   共享的 MapService 实例
     */
    public static void registerMapTools(ToolRegistry toolRegistry, MapService mapService) {
        GsimapToolRegistrar.registerAll(toolRegistry, mapService);
    }
}
