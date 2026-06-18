package com.gsim.interaction.commands;

import com.gsim.app.AppConfig;
import com.gsim.config.ConfigDoctor;
import com.gsim.config.ConfigLoader;
import com.gsim.config.ConfigWizard;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * /config — 查看、初始化、测试模型配置。
 *
 * 子命令：
 *   /config status     — 配置来源、LLM 可用性
 *   /config show       — 显示配置（api_key 脱敏）
 *   /config path       — 当前配置文件路径
 *   /config init       — 启动配置向导
 *   /config doctor     — 诊断配置完整性
 *   /config test-llm   — LLM 连通性测试
 *   /config set <k> <v> — 修改 config.properties
 */
public class ConfigCommand implements InteractionCommand {

    @Override public String name() { return "config"; }
    @Override public String description() { return "查看、初始化、测试模型配置"; }
    @Override public String usage() {
        return "/config status|show|path|init|doctor|test-llm|set <key> <value>";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        AppConfig config = session.getConfig();

        if (args.length == 0) {
            return status(config);
        }

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "status" -> status(config);
            case "show" -> show(config);
            case "path" -> path(config);
            case "init" -> init(config);
            case "doctor" -> doctor(config);
            case "test-llm", "test_llm", "testllm" -> testLlm(config);
            case "set" -> set(args, config);
            default -> InteractionResult.fail(
                    "Unknown subcommand: /config " + sub + ". Use /config status|show|path|init|doctor|test-llm|set");
        };
    }

    // ---- 子命令实现 ----

    private InteractionResult status(AppConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 配置状态 ==========\n\n");

        sb.append("配置来源:\n");
        sb.append(config.getConfigSourceSummary()).append("\n\n");

        sb.append("配置文件: ");
        Path cp = config.getConfigPath();
        sb.append(cp != null ? cp.toAbsolutePath() : "(无，使用环境变量/默认值)").append("\n\n");

        sb.append("--- LLM ---\n");
        sb.append("状态:     ");
        if (config.isLlmConfigured()) {
            sb.append("已配置 ✅\n");
        } else {
            sb.append("未配置 ❌ (执行 /config init)\n");
        }
        sb.append("Base URL: ").append(blankToNone(config.getLlmBaseUrl())).append("\n");
        sb.append("API Key:  ").append(config.maskedApiKey()).append("\n");
        sb.append("Model:    ").append(blankToNone(config.getLlmModel())).append("\n");
        sb.append("Timeout:  ").append(config.getLlmTimeoutSeconds()).append("s\n\n");

        sb.append("--- 目录 ---\n");
        sb.append("Data:   ").append(config.getDataDir()).append("\n");
        sb.append("Import: ").append(config.getImportDir()).append("\n");
        sb.append("Output: ").append(config.getOutputDir()).append("\n");
        sb.append("Log:    ").append(config.getLogDir()).append("\n\n");

        sb.append("--- 服务 ---\n");
        sb.append("ChromaDB:    ").append(config.isChromaEnabled() ? "已启用" : "未启用").append("\n");
        sb.append("WebResearch: ").append(config.isWebResearchEnabled() ? "已启用" : "未启用").append("\n");

        sb.append("\n===============================\n");
        return InteractionResult.ok(sb.toString());
    }

    private InteractionResult show(AppConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 当前配置 ==========\n\n");

        sb.append("llm.base_url=").append(config.getLlmBaseUrl()).append("\n");
        sb.append("llm.api_key=").append(config.maskedApiKey()).append("\n");
        sb.append("llm.model=").append(config.getLlmModel()).append("\n");
        sb.append("llm.temperature=").append(config.getLlmTemperature()).append("\n");
        sb.append("llm.timeout_seconds=").append(config.getLlmTimeoutSeconds()).append("\n\n");

        sb.append("data.dir=").append(config.getDataDir()).append("\n");
        sb.append("import.dir=").append(config.getImportDir()).append("\n");
        sb.append("output.dir=").append(config.getOutputDir()).append("\n");
        sb.append("log.dir=").append(config.getLogDir()).append("\n\n");

        sb.append("chroma.enabled=").append(config.isChromaEnabled()).append("\n");
        sb.append("chroma.base_url=").append(config.getChromaBaseUrl()).append("\n\n");

        sb.append("web_research.enabled=").append(config.isWebResearchEnabled()).append("\n");
        sb.append("web_research.timeout_seconds=").append(config.getWebResearchTimeoutSeconds()).append("\n");
        sb.append("web_research.user_agent=").append(config.getWebResearchUserAgent()).append("\n");

        sb.append("\n===============================\n");
        return InteractionResult.ok(sb.toString());
    }

    private InteractionResult path(AppConfig config) {
        Path cp = config.getConfigPath();
        if (cp != null && Files.isRegularFile(cp)) {
            return InteractionResult.ok("配置文件: " + cp.toAbsolutePath());
        } else {
            return InteractionResult.ok("未使用配置文件（当前使用环境变量或默认值）。\n使用 /config init 创建配置文件。");
        }
    }

    private InteractionResult init(AppConfig config) {
        if (!ConfigLoader.isInteractiveTerminal()) {
            return InteractionResult.fail("配置向导需要交互式终端。请手动创建 gsim.properties 文件。");
        }

        Path newPath = ConfigWizard.run();
        if (newPath != null) {
            return InteractionResult.ok(
                    "配置向导完成。\n配置文件: " + newPath.toAbsolutePath() + "\n请重启 GSimulator 以加载新配置。");
        } else {
            return InteractionResult.ok("已跳过配置向导。LLM 功能不可用。");
        }
    }

    private InteractionResult doctor(AppConfig config) {
        String report = ConfigDoctor.diagnose(config);
        return InteractionResult.ok(report);
    }

    private InteractionResult testLlm(AppConfig config) {
        if (!config.isLlmConfigured()) {
            return InteractionResult.fail("LLM 未配置。请先执行 /config init。");
        }
        String result = ConfigDoctor.testLlmConnectivity(config);
        return InteractionResult.ok(result);
    }

    private InteractionResult set(String[] args, AppConfig config) {
        if (args.length < 3) {
            return InteractionResult.fail("用法: /config set <key> <value>");
        }

        String key = args[1];
        String value = args[2];

        Path configPath = config.getConfigPath();
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return InteractionResult.fail(
                    "当前未使用 config.properties 文件。\n"
                            + "无法直接修改环境变量或 .env 配置。\n"
                            + "建议使用 /config init 创建 config.properties 文件。");
        }

        // 安全：不允许通过 set 修改 api_key（避免明文泄露到日志）
        if (key.contains("api_key") || key.contains("apikey")) {
            return InteractionResult.fail(
                    "出于安全考虑，不支持通过 /config set 修改 API Key。\n"
                            + "请直接编辑配置文件: " + configPath.toAbsolutePath());
        }

        try {
            // 读取现有配置
            Properties props = ConfigLoader.loadPropertiesFile(configPath);

            // 更新
            props.setProperty(key, value);

            // 写回
            try (BufferedWriter w = Files.newBufferedWriter(configPath)) {
                // 保留注释头
                w.write("# GSimulator Configuration\n");
                w.write("# Updated via /config set\n");
                w.write("\n");
                for (String name : props.stringPropertyNames()) {
                    w.write(name + "=" + props.getProperty(name) + "\n");
                }
            }

            return InteractionResult.ok(
                    "已更新: " + key + "=" + value + "\n"
                            + "配置文件: " + configPath.toAbsolutePath() + "\n"
                            + "请重启 GSimulator 以加载新配置。");

        } catch (IOException e) {
            return InteractionResult.fail("写入配置文件失败: " + e.getMessage());
        }
    }

    // ---- helpers ----

    private static String blankToNone(String s) {
        return (s == null || s.isBlank()) ? "(未设置)" : s;
    }
}
