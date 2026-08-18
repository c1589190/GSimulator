package com.gsim;

import com.gsim.agent.bridge.AgentBridge;
import com.gsim.agent.tools.search.SearchToolContext;
import com.gsim.agentlib.mcp.GsimRequestContext;
import com.gsim.agentlib.mcp.McpHttpServer;
import com.gsim.agentlib.mcp.McpResponseConfig;
import com.gsim.agentlib.mcp.ToolResultOverflowHandler;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.app.AppConfig;
import com.gsim.app.Bootstrap;
import com.gsim.app.GSimulatorApplication;
import com.gsim.app.mcp.DocStagingOverflowHandler;
import com.gsim.core.cache.CacheInfo;
import com.gsim.core.cache.CachesManager;
import com.gsim.core.cache.FileSystemCachesManager;
import com.gsim.core.config.ConfigDoctor;
import com.gsim.core.config.ConfigLoader;
import com.gsim.core.config.ConfigSnapshot;
import com.gsim.core.config.ConfigWizard;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.WorldManager;
import com.gsim.map.http.GsimapHttpServer;
import com.gsim.map.service.MapService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * GSimulator 主入口类。
 *
 * <p>启动模式：
 * <ul>
 *   <li><b>默认</b> — CLI REPL + Web GUI + Map UI + CLI WS</li>
 *   <li><b>--no-cli</b> — MCP HTTP + Web GUI + Map UI + CLI WS</li>
 * </ul>
 *
 * <p>服务端口统一由 gsim.properties 配置（webui.port / map.port /
 * cli.ws.port / mcp.http.port），默认分别为 8710 / 8711 / 8712 / 37201。
 *
 * <p>启动流程（三阶段）：
 * <ol>
 *   <li>Phase 1: 配置加载 — CLI 参数 → ConfigLoader → AppConfig → Bootstrap</li>
 *   <li>Phase 2: 应用组装 — GSimulatorApplication + MapService + HTTP 服务器</li>
 *   <li>Phase 3: 传输启动 — CLI REPL 或 MCP HTTP（阻塞主线程）</li>
 * </ol>
 */
public class Main {

    /**
     * 程序入口点。
     */
    public static void main(String[] args) {
        try {
            // ── Phase 1: 配置加载 ─────────────────────────────

            ConfigLoader loader = new ConfigLoader(args);
            ConfigLoader.CliArgs cliArgs = loader.getCliArgs();
            boolean noCli = cliArgs.noCli();

            // --help
            if (cliArgs.help()) {
                printUsage();
                return;
            }

            // 加载配置；首次运行（无任何配置文件）时自动生成 gsim.properties 模板，
            // 端口等配置随后可直接在文件中编辑。
            ConfigLoader.ConfigResult configResult = loader.load();
            if (configResult.configPath() == null) {
                ensureConfigTemplate(Path.of("gsim.properties").toAbsolutePath());
            }
            AppConfig config = new AppConfig(configResult);

            // --doctor
            if (cliArgs.doctor()) {
                String report = ConfigDoctor.diagnose(new ConfigSnapshot(
                        config.getConfigPath(),
                        config.isLlmConfigured(),
                        config.getLlmBaseUrl(),
                        config.getLlmApiKey(),
                        config.getLlmModel(),
                        config.getLlmTimeoutSeconds(),
                        config.maskedApiKey(),
                        config.getDataDir(),
                        config.getImportDir(),
                        config.getOutputDir(),
                        config.getLogDir()));
                System.out.println(report);
                return;
            }

            // --init-config
            if (cliArgs.initConfig()) {
                if (!ConfigLoader.isInteractiveTerminal()) {
                    System.err.println("配置向导需要交互式终端。");
                    System.err.println("请手动创建 gsim.properties 文件，或设置环境变量。");
                    System.exit(1);
                }
                ConfigWizard.run();
                return;
            }

            // 首次运行向导
            if (!config.isLlmConfigured() && ConfigLoader.isInteractiveTerminal() && !cliArgs.noWizard()) {
                System.out.println();
                System.out.println("⚠️  未检测到 LLM 配置。");
                System.out.println();
                Path wizardPath = ConfigWizard.run();
                if (wizardPath != null) {
                    configResult = loader.load();
                    config = new AppConfig(configResult);
                }
            }

            // Bootstrap
            CachesManager cachesManager = new FileSystemCachesManager(config.worldsDir());
            Path worldsDir = config.worldsDir();
            Path promptsDir = config.promptsDir();
            Bootstrap bootstrap = new Bootstrap(worldsDir, promptsDir, cachesManager);

            // 缓存选择（仅交互终端，--no-cli 下 stdin 用于 MCP 协议）
            String selectedSessionId = null;
            String targetWorldId = null;
            if (!noCli && ConfigLoader.isInteractiveTerminal()) {
                var selection = selectOrchestratorCache(cachesManager, worldsDir);
                selectedSessionId = selection.sessionId();
                targetWorldId = selection.worldId();
            }

            Bootstrap.BootstrapResult bootResult = bootstrap.boot(selectedSessionId, targetWorldId);
            if (!noCli) {
                System.out.println("World loaded: " + bootResult.worldId()
                        + ", active node: " + bootResult.activeNodeId()
                        + ", chain length: "
                        + bootResult.worldInfo().branchChain().size());
                if (bootResult.activeCache() != null) {
                    System.out.println(
                            "Active cache: " + bootResult.activeCache().sessionId() + " ("
                                    + bootResult.activeCache().messageCount() + " messages)");
                }
            }

            // ── Phase 2: 应用组装 ─────────────────────────────

            // 创建应用（interactive = !noCli）
            GSimulatorApplication app = new GSimulatorApplication(config, bootResult, !noCli);

            // 地图服务 + 工具注册
            MapService mapService = new MapService(config.worldsDir(), config.mapConfig());
            ToolRegistry toolRegistry = app.getContext().getToolRegistry();

            // 领域搜索上下文（T8 接线）—— wiSupplier 沿用 AgentBridge.registerWorldInfoTools
            // 闭包语义：按 GsimRequestContext.worldId() 解析 WorldInformation（当前世界取应用侧
            // 最新实例，其他世界经 WorldManager 加载并按世界缓存），而非 Bootstrap 世界单例。
            // registry 与 T2 装配的 ResolverRegistry 同一实例（GsimapResolver 注册后即可解析 gsimap:）。
            Map<String, WorldInformation> wiCache = new ConcurrentHashMap<>();
            WorldManager worldManager = new WorldManager(config.worldsDir());
            Supplier<WorldInformation> wiSupplier = () -> {
                String reqWorldId = GsimRequestContext.worldId();
                WorldInformation current = app.getWorldInfoSupplier().get();
                if (reqWorldId != null && current != null && !reqWorldId.equals(current.worldId())) {
                    return wiCache.computeIfAbsent(reqWorldId, worldManager::loadWorld);
                }
                return current;
            };
            SearchToolContext searchCtx = new SearchToolContext(
                    wiSupplier,
                    mapService,
                    app.getContext().getDocStore(config.docsDir()),
                    app.getContext().getResolverRegistry(),
                    config.worldsDir(),
                    config.getImportDir(),
                    config.cachesDir());

            AgentBridge.registerMapTools(toolRegistry, mapService, searchCtx);

            // gsimap: 前缀引用解析器（依赖 MapService）注册进统一 ResolverRegistry（resolve_ref/text_edit 即刻可用）
            app.getContext().getResolverRegistry().register(new com.gsim.agent.tools.map.GsimapResolver(mapService));

            // 领域搜索工具（T8 独占接线）：4 个细化搜索工具 + gsim_search 聚合器
            AgentBridge.registerSearchTools(toolRegistry, searchCtx);

            // Map HTTP 服务器（map.port，默认 8711）— 始终启动
            int gsimapPort = Integer.getInteger("gsimap.port", config.getMapPort());
            GsimapHttpServer gsimapServer = new GsimapHttpServer(gsimapPort, mapService, config.mapConfig());
            gsimapServer.start();
            System.err.println("[BOOT] Map UI: http://127.0.0.1:" + gsimapPort);

            // WebUI + WebSocket 服务器 — 始终启动
            app.startHttpServers();
            System.err.println("[BOOT] Web UI: http://127.0.0.1:" + config.getWebUiPort());

            // ── Phase 3: 传输启动 ─────────────────────────────

            if (noCli) {
                // MCP HTTP mode: start Streamable HTTP MCP server（mcp.http.port，默认 37201）
                int mcpPort = Integer.getInteger("mcp.http.port", config.getMcpHttpPort());
                McpResponseConfig mcpResponseConfig = new McpResponseConfig(
                        config.mcpResponseDefaultPageSize(),
                        config.mcpResponseMaxPageSize(),
                        config.mcpResponseMaxJsonBytes(),
                        config.mcpResponseSnippetMaxChars(),
                        config.mcpResponseOverflowStagingEnabled());
                // 溢出暂存 handler：超限 snippet 暂存为 docs/tmp 文档并返回 docId；未启用时传 null（走截断）
                ToolResultOverflowHandler overflowHandler = config.mcpResponseOverflowStagingEnabled()
                        ? new DocStagingOverflowHandler(
                                app.getContext().getDocStore(config.docsDir()), config.stagingThreshold(), "mcp_")
                        : null;
                McpHttpServer mcpHttpServer =
                        new McpHttpServer(toolRegistry, mcpPort, mcpResponseConfig, overflowHandler);
                mcpHttpServer.start();

                Runtime.getRuntime()
                        .addShutdownHook(new Thread(
                                () -> {
                                    System.err.println("[MCP] Shutting down...");
                                    mcpHttpServer.stop();
                                    gsimapServer.stop();
                                    app.stop();
                                },
                                "mcp-shutdown"));

                System.err.println("[MCP-HTTP] READY — http://127.0.0.1:" + mcpPort + "/mcp");
                System.err.println("[MCP-HTTP] Health: http://127.0.0.1:" + mcpPort + "/health");

                // Block until shutdown (wait for server socket to close)
                Thread.currentThread().join();
            } else {
                // CLI mode: start interactive REPL (blocks until exit)
                app.startCliRepl();
            }

        } catch (Exception e) {
            System.err.println("GSimulator failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /** 首次运行时写入 gsim.properties 模板（已存在不覆盖）。 */
    private static void ensureConfigTemplate(Path target) throws java.io.IOException {
        if (java.nio.file.Files.exists(target)) return;
        try (java.io.InputStream in = Main.class.getResourceAsStream("/gsim/config/gsim.properties.template")) {
            if (in == null) {
                throw new java.io.IOException("classpath 模板缺失: /gsim/config/gsim.properties.template");
            }
            java.nio.file.Files.copy(in, target);
        }
        System.err.println("[BOOT] 已生成配置模板: " + target);
    }

    /** 缓存选择结果：sessionId + worldId 配对。 */
    private record CacheSelection(String sessionId, String worldId) {}

    /** CLI 交互：选择 Orchestrator 历史缓存或新建。 */
    private static CacheSelection selectOrchestratorCache(CachesManager cachesManager, Path worldsDir) {
        List<CacheInfo> caches = cachesManager.listCaches(null, "orchestrator");
        if (caches.isEmpty()) return new CacheSelection(null, null);

        List<com.gsim.core.worldinfo.loader.WorldIndexManager.WorldEntry> worlds =
                new WorldManager(worldsDir).listWorlds();

        System.out.println();
        System.out.println("══════════════════════════════════════════");
        System.out.println("  选择 Orchestrator 会话缓存");
        System.out.println("══════════════════════════════════════════");
        for (int i = 0; i < caches.size(); i++) {
            CacheInfo ci = caches.get(i);
            String worldLabel = ci.worldId() != null ? ci.worldId() : "?";
            System.out.printf(
                    "  [%d] %s  world=%s  (%d messages, %s)%n",
                    i + 1,
                    ci.sessionId(),
                    worldLabel,
                    ci.messageCount(),
                    ci.createdAt().substring(0, Math.min(16, ci.createdAt().length())));
        }
        System.out.println("  [N] 新建会话");
        System.out.print("  选择 (1-" + caches.size() + "/N): ");

        try {
            Scanner scanner = new Scanner(System.in);
            String line = scanner.nextLine().trim();
            if ("n".equalsIgnoreCase(line) || "N".equals(line)) {
                String selectedWorld = selectWorldForNewSession(scanner, worlds);
                System.out.println("  创建新会话 (world=" + selectedWorld + ")...");
                return new CacheSelection(null, selectedWorld);
            }
            int idx = Integer.parseInt(line) - 1;
            if (idx >= 0 && idx < caches.size()) {
                CacheInfo chosen = caches.get(idx);
                System.out.println("  加载缓存: " + chosen.sessionId() + " (world=" + chosen.worldId() + ")");
                return new CacheSelection(chosen.sessionId(), chosen.worldId());
            }
        } catch (Exception e) {
            // fall through
        }
        CacheInfo fallback = caches.get(0);
        System.out.println("  输入无效，使用最新缓存 (world=" + fallback.worldId() + ")。");
        return new CacheSelection(fallback.sessionId(), fallback.worldId());
    }

    /** 选择 world（用于新建会话时）。 */
    private static String selectWorldForNewSession(
            Scanner scanner, List<com.gsim.core.worldinfo.loader.WorldIndexManager.WorldEntry> worlds) {
        if (worlds.isEmpty()) return "default";
        if (worlds.size() == 1) return worlds.get(0).id();

        System.out.println();
        System.out.println("  可用 World:");
        for (int i = 0; i < worlds.size(); i++) {
            System.out.printf(
                    "    [%d] %s (%s)%n",
                    i + 1, worlds.get(i).id(), worlds.get(i).name());
        }
        System.out.print("  选择 World (1-" + worlds.size() + ", 回车=首个): ");
        try {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) return worlds.get(0).id();
            int idx = Integer.parseInt(line) - 1;
            if (idx >= 0 && idx < worlds.size()) return worlds.get(idx).id();
        } catch (Exception e) {
            // fall through
        }
        return worlds.get(0).id();
    }

    private static void printUsage() {
        System.out.println("GSimulator — 多 Agent 推演工作流引擎");
        System.out.println();
        System.out.println("用法: java -jar gsim-app/target/gsim-app-*.jar [选项]");
        System.out.println();
        System.out.println("默认启动 CLI REPL + Web GUI + Map UI + CLI WS");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --config <path>    使用指定的配置文件");
        System.out.println("  --no-cli           无 CLI 模式：MCP HTTP + Web GUI + Map UI + CLI WS");
        System.out.println("  --init-config      启动配置向导并退出");
        System.out.println("  --doctor           运行配置诊断并退出");
        System.out.println("  --no-wizard        跳过首次运行配置向导");
        System.out.println("  --help             显示此帮助信息");
        System.out.println();
        System.out.println("服务端口在 gsim.properties 中配置：");
        System.out.println("  webui.port=8710     Web UI");
        System.out.println("  map.port=8711       Map UI");
        System.out.println("  cli.ws.port=8712    CLI WebSocket");
        System.out.println("  mcp.http.port=37201  MCP HTTP (JSON-RPC 2.0)");
    }
}
