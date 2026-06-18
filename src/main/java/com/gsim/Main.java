package com.gsim;

import com.gsim.app.AppConfig;
import com.gsim.app.GSimulatorApplication;
import com.gsim.config.ConfigLoader;
import com.gsim.config.ConfigDoctor;
import com.gsim.config.ConfigWizard;

import java.nio.file.Path;

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

            // 3. 启动应用
            GSimulatorApplication app = new GSimulatorApplication(config);
            app.start();

        } catch (Exception e) {
            System.err.println("GSimulator failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("GSimulator — 多 Agent 推演工作流引擎");
        System.out.println();
        System.out.println("用法: java -jar GSimulator.jar [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --config <path>    使用指定的配置文件");
        System.out.println("  --init-config      启动配置向导并退出");
        System.out.println("  --doctor           运行配置诊断并退出");
        System.out.println("  --no-wizard        跳过首次运行配置向导");
        System.out.println("  --help             显示此帮助信息");
        System.out.println();
        System.out.println("首次运行:");
        System.out.println("  直接运行 java -jar GSimulator.jar，程序将引导你创建配置文件。");
        System.out.println();
        System.out.println("配置文件优先级:");
        System.out.println("  1. --config <path>");
        System.out.println("  2. GSIM_CONFIG 环境变量");
        System.out.println("  3. ./gsim.properties");
        System.out.println("  4. ./.env");
        System.out.println("  5. ~/.gsimulator/config.properties");
        System.out.println("  6. ~/.gsimulator/.env");
        System.out.println("  7. 系统环境变量 (LLM_BASE_URL, LLM_API_KEY 等)");
        System.out.println("  8. 内置默认值");
    }
}
