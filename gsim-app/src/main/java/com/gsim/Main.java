package com.gsim;

import com.gsim.app.AppConfig;
import com.gsim.app.Bootstrap;
import com.gsim.app.GSimulatorApplication;
import com.gsim.cache.CacheInfo;
import com.gsim.cache.CachesManager;
import com.gsim.cache.FileSystemCachesManager;
import com.gsim.config.ConfigDoctor;
import com.gsim.config.ConfigLoader;
import com.gsim.config.ConfigWizard;
import com.gsim.mcp.McpHttpServer;
import com.gsim.tool.ToolRegistry;
import com.gsimap.http.GsimapHttpServer;
import com.gsimap.service.MapService;
import com.gsimap.tool.GsimapToolRegistrar;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * GSimulator 主入口类。
 *
 * <p>启动模式：
 * <ul>
 *   <li><b>默认</b> — CLI REPL + Web GUI(8710) + Map UI(8711) + CLI WS(8712)</li>
 *   <li><b>--no-cli</b> — MCP stdio + Web GUI(8710) + Map UI(8711) + CLI WS(8712)</li>
 * </ul>
 *
 * <p>启动流程（三阶段）：
 * <ol>
 *   <li>Phase 1: 配置加载 — CLI 参数 → ConfigLoader → AppConfig → Bootstrap</li>
 *   <li>Phase 2: 应用组装 — GSimulatorApplication + MapService + HTTP 服务器</li>
 *   <li>Phase 3: 传输启动 — CLI REPL 或 MCP stdio（阻塞主线程）</li>
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

            // 加载配置
            ConfigLoader.ConfigResult configResult = loader.load();
            AppConfig config = new AppConfig(configResult);

            // --doctor
            if (cliArgs.doctor()) {
                String report = ConfigDoctor.diagnose(config);
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
            MapService mapService = new MapService(config.worldsDir());
            ToolRegistry toolRegistry = app.getContext().getToolRegistry();
            GsimapToolRegistrar.registerAll(toolRegistry, mapService);

            // Map HTTP 服务器 (port 8711) — 始终启动
            int gsimapPort = Integer.parseInt(
                    System.getProperty("gsimap.port", System.getenv().getOrDefault("GSIMAP_PORT", "8711")));
            GsimapHttpServer gsimapServer = new GsimapHttpServer(gsimapPort, mapService);
            gsimapServer.start();
            System.err.println("[BOOT] Map UI: http://127.0.0.1:" + gsimapPort);

            // WebUI + WebSocket 服务器 — 始终启动
            app.startHttpServers();
            System.err.println("[BOOT] Web UI: http://127.0.0.1:" + config.getWebUiPort());

            // ── Phase 3: 传输启动 ─────────────────────────────

            if (noCli) {
                // MCP HTTP mode: start Streamable HTTP MCP server (port 8720)
                int mcpPort = Integer.parseInt(
                        System.getProperty("mcp.http.port", System.getenv().getOrDefault("MCP_HTTP_PORT", "8720")));
                McpHttpServer mcpHttpServer = new McpHttpServer(toolRegistry, mcpPort, worldsDir);
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

    /** 缓存选择结果：sessionId + worldId 配对。 */
    private record CacheSelection(String sessionId, String worldId) {}

    /** CLI 交互：选择 Orchestrator 历史缓存或新建。 */
    private static CacheSelection selectOrchestratorCache(CachesManager cachesManager, Path worldsDir) {
        List<CacheInfo> caches = cachesManager.listCaches(null, "orchestrator");
        if (caches.isEmpty()) return new CacheSelection(null, null);

        List<com.gsim.worldinfo.loader.WorldIndexManager.WorldEntry> worlds =
                com.gsim.worldinfo.loader.WorldIndexManager.listWorlds(worldsDir);

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
            Scanner scanner, List<com.gsim.worldinfo.loader.WorldIndexManager.WorldEntry> worlds) {
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
        System.out.println("用法: java -jar GSimulator.jar [选项]");
        System.out.println();
        System.out.println("默认启动 CLI REPL + Web GUI(8710) + Map UI(8711)");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --config <path>    使用指定的配置文件");
        System.out.println("  --no-cli           无 CLI 模式：MCP stdio + Web GUI(8710) + Map(8711)");
        System.out.println("  --init-config      启动配置向导并退出");
        System.out.println("  --doctor           运行配置诊断并退出");
        System.out.println("  --no-wizard        跳过首次运行配置向导");
        System.out.println("  --help             显示此帮助信息");
        System.out.println();
        System.out.println("API 配置环境变量:");
        System.out.println("  API_HOST=127.0.0.1");
        System.out.println("  API_PORT=8710");
        System.out.println("  API_ENABLED=true");
    }
}
