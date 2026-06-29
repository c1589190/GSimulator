package com.gsim;

import com.gsim.app.AppConfig;
import com.gsim.app.Bootstrap;
import com.gsim.app.GSimulatorApplication;
import com.gsim.cache.CacheInfo;
import com.gsim.cache.CachesManager;
import com.gsim.cache.FileSystemCachesManager;
import com.gsim.config.ConfigLoader;
import com.gsim.config.ConfigDoctor;
import com.gsim.config.ConfigWizard;

import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * GSimulator 主入口。
 * 负责解析命令行参数、加载配置、启动应用。
 */
public class Main {

    public static void main(String[] args) {
        try {
            // 0. 解析 CLI 参数
            ConfigLoader loader = new ConfigLoader(args);
            ConfigLoader.CliArgs cliArgs = loader.getCliArgs();

            // --help
            if (cliArgs.help()) {
                printUsage();
                return;
            }

            // 1. 加载配置
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
                // 向导完成后退出（用户可能在向导中选择了离线模式）
                return;
            }

            // 2. 首次运行向导：未配置 LLM + 交互终端 + 未指定 --no-wizard
            if (!config.isLlmConfigured()
                    && ConfigLoader.isInteractiveTerminal()
                    && !cliArgs.noWizard()) {
                System.out.println();
                System.out.println("⚠️  未检测到 LLM 配置。");
                System.out.println();
                Path wizardPath = ConfigWizard.run();
                if (wizardPath != null) {
                    // 重新加载配置
                    configResult = loader.load();
                    config = new AppConfig(configResult);
                }
            }

            // 3. 创建 CachesManager
            CachesManager cachesManager = new FileSystemCachesManager(config.worldsDir());

            // Bootstrap: load worlds, build WorldInformation, init cache
            Path worldsDir = config.worldsDir();
            Path promptsDir = config.promptsDir();
            Bootstrap bootstrap = new Bootstrap(worldsDir, promptsDir, cachesManager);

            // 4. CLI 缓存选择（交互终端下）
            String selectedSessionId = null;
            String targetWorldId = null;
            if (ConfigLoader.isInteractiveTerminal()) {
                var selection = selectOrchestratorCache(cachesManager, worldsDir);
                selectedSessionId = selection.sessionId();
                targetWorldId = selection.worldId();
            }

            Bootstrap.BootstrapResult bootResult = bootstrap.boot(selectedSessionId, targetWorldId);
            System.out.println("World loaded: " + bootResult.worldId()
                + ", active node: " + bootResult.activeNodeId()
                + ", chain length: " + bootResult.worldInfo().branchChain().size());
            if (bootResult.activeCache() != null) {
                System.out.println("Active cache: " + bootResult.activeCache().sessionId()
                    + " (" + bootResult.activeCache().messageCount() + " messages)");
            }

            // 5. 确定启动模式
            // 未指定任何模式标志时，默认启用 CLI + HTTP API + WebUI
            boolean cliMode = cliArgs.cli() || (!cliArgs.http() && !cliArgs.webui());
            boolean httpMode = cliArgs.http() || (!cliArgs.cli() && !cliArgs.webui());
            boolean webuiMode = cliArgs.webui() || (!cliArgs.cli() && !cliArgs.http());

            // 6. 启动应用
            GSimulatorApplication app = new GSimulatorApplication(config, cliMode, httpMode, webuiMode, bootResult);
            app.start();

        } catch (Exception e) {
            System.err.println("GSimulator failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /** 缓存选择结果：sessionId + worldId 配对。 */
    private record CacheSelection(String sessionId, String worldId) {}

    /** CLI 交互：选择 Orchestrator 历史缓存或新建。
     *  列出所有 world 下的缓存（而非仅 "default"），选中后自动使用缓存所属的 world。 */
    private static CacheSelection selectOrchestratorCache(CachesManager cachesManager, Path worldsDir) {
        // 列出所有 world 的 Orchestrator 缓存
        List<CacheInfo> caches = cachesManager.listCaches(null, "orchestrator");
        if (caches.isEmpty()) return new CacheSelection(null, null); // 将自动新建（使用默认 world）

        // 获取可用 world 列表（用于新建会话时选择）
        List<com.gsim.worldinfo.loader.WorldIndexManager.WorldEntry> worlds =
                com.gsim.worldinfo.loader.WorldIndexManager.listWorlds(worldsDir);

        System.out.println();
        System.out.println("══════════════════════════════════════════");
        System.out.println("  选择 Orchestrator 会话缓存");
        System.out.println("══════════════════════════════════════════");
        for (int i = 0; i < caches.size(); i++) {
            CacheInfo ci = caches.get(i);
            String worldLabel = ci.worldId() != null ? ci.worldId() : "?";
            System.out.printf("  [%d] %s  world=%s  (%d messages, %s)%n",
                    i + 1, ci.sessionId(), worldLabel, ci.messageCount(),
                    ci.createdAt().substring(0, Math.min(16, ci.createdAt().length())));
        }
        System.out.println("  [N] 新建会话");
        System.out.print("  选择 (1-" + caches.size() + "/N): ");

        try {
            Scanner scanner = new Scanner(System.in);
            String line = scanner.nextLine().trim();
            if ("n".equalsIgnoreCase(line) || "N".equals(line)) {
                // 新建会话 — 选择 world
                String selectedWorld = selectWorldForNewSession(scanner, worlds);
                System.out.println("  创建新会话 (world=" + selectedWorld + ")...");
                return new CacheSelection(null, selectedWorld);
            }
            int idx = Integer.parseInt(line) - 1;
            if (idx >= 0 && idx < caches.size()) {
                CacheInfo chosen = caches.get(idx);
                System.out.println("  加载缓存: " + chosen.sessionId()
                        + " (world=" + chosen.worldId() + ")");
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
    private static String selectWorldForNewSession(Scanner scanner,
            List<com.gsim.worldinfo.loader.WorldIndexManager.WorldEntry> worlds) {
        if (worlds.isEmpty()) return "default";
        if (worlds.size() == 1) return worlds.get(0).id();

        System.out.println();
        System.out.println("  可用 World:");
        for (int i = 0; i < worlds.size(); i++) {
            System.out.printf("    [%d] %s (%s)%n", i + 1, worlds.get(i).id(), worlds.get(i).name());
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
        System.out.println("默认启动 CLI + HTTP API(8710) + Web GUI(8711) + CLI WS(8712)");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --config <path>    使用指定的配置文件");
        System.out.println("  --init-config      启动配置向导并退出");
        System.out.println("  --doctor           运行配置诊断并退出");
        System.out.println("  --no-wizard        跳过首次运行配置向导");
        System.out.println("  --cli              仅 CLI（兼容旧行为）");
        System.out.println("  --http             仅 HTTP API（兼容旧行为）");
        System.out.println("  --webui            仅 Web GUI（兼容旧行为）");
        System.out.println("  --help             显示此帮助信息");
        System.out.println();
        System.out.println("API 配置环境变量:");
        System.out.println("  API_HOST=127.0.0.1");
        System.out.println("  API_PORT=8710");
        System.out.println("  API_ENABLED=true");
    }
}
