package com.gsim.core.config;

import com.gsim.agentsmanager.llm.LlmMessage;
import com.gsim.agentsmanager.llm.LlmRequest;
import com.gsim.agentsmanager.llm.LlmResult;
import com.gsim.core.llm.LlmManager;
import com.gsim.core.llm.ProviderConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 配置诊断工具。
 * 检查 Java 版本、配置完整性、目录可写性、LLM 连通性。
 */
public class ConfigDoctor {

    // ---- 公共入口 ----

    /**
     * 运行配置诊断并返回完整报告文本。
     * <p>检查项包括：Java 版本、配置文件完整性、LLM 配置、数据目录可写性、LLM 连通性。</p>
     *
     * @param config 配置快照（core 自有输入类型，由调用方从 AppConfig 构造）
     * @return 诊断报告文本，包含各检查项的结果与状态标记（✅/⚠️/❌）
     */
    public static String diagnose(ConfigSnapshot config) {
        StringBuilder report = new StringBuilder();
        report.append("========== GSimulator 配置诊断 ==========\n\n");

        // 1. Java 版本
        checkJavaVersion(report);

        // 2. 配置文件
        checkConfigFile(report, config.configPath());

        // 3. LLM 配置
        checkLlmConfig(report, config);

        // 4. 目录可写
        checkDirectories(report, config);

        // 5. LLM 连通性
        checkLlmConnectivity(report, config);

        report.append("\n=========================================\n");
        return report.toString();
    }

    /**
     * 基于配置快照快速测试 LLM 连通性。
     *
     * @param config 配置快照，需包含 LLM base URL、API Key、model 等字段
     * @return 测试结果描述文本，包含成功标记（✅）或失败原因（❌）
     */
    public static String testLlmConnectivity(ConfigSnapshot config) {
        if (!config.llmConfigured()) {
            return "❌ LLM 未配置，无法测试。";
        }
        return testLlmConnectivity(
                config.llmBaseUrl(), config.llmApiKey(), config.llmModel(), config.llmTimeoutSeconds());
    }

    /**
     * 用给定参数测试 LLM 连通性，返回结果描述。
     * <p>内部创建临时 {@link LlmManager} 发送测试请求，完成后自动关闭。</p>
     *
     * @param baseUrl        LLM API 基础地址
     * @param apiKey         API 密钥
     * @param model          模型名称
     * @param timeoutSeconds 请求超时时间（秒），实际测试时不超过 10 秒
     * @return 测试结果描述文本，包含成功标记（✅）或失败原因（❌）
     */
    public static String testLlmConnectivity(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        if (baseUrl == null || baseUrl.isBlank()) return "❌ Base URL 为空。";
        if (apiKey == null || apiKey.isBlank()) return "❌ API Key 为空。";
        if (model == null || model.isBlank()) return "❌ Model 为空。";

        // 使用短 timeout
        int testTimeout = Math.min(timeoutSeconds, 10);
        LlmManager llmManager =
                new LlmManager(ProviderConfig.generic("test", baseUrl, apiKey, model, 0.0, testTimeout));

        if (!llmManager.isAvailable()) {
            llmManager.close();
            return "❌ LLM 客户端不可用（配置不完整）。";
        }

        try {
            LlmRequest req =
                    new LlmRequest(model, List.of(new LlmMessage("user", "Say \"OK\" and nothing else.")), 0.0, 10);
            LlmResult resp = llmManager.chat(req);

            if (resp.success()) {
                return "✅ LLM 连通正常 (model=" + model + ", tokens=" + resp.tokensUsed() + ")";
            } else {
                return "❌ LLM 请求失败: " + resp.errorMessage();
            }
        } catch (Exception e) {
            return "❌ LLM 连接异常: " + e.getMessage();
        } finally {
            llmManager.close();
        }
    }

    // ---- 内部检查方法 ----

    private static void checkJavaVersion(StringBuilder report) {
        String version = System.getProperty("java.version");
        int major;
        try {
            if (version.startsWith("1.")) {
                major = Integer.parseInt(version.split("\\.")[1]);
            } else {
                String[] parts = version.split("[._-]");
                major = Integer.parseInt(parts[0]);
            }
        } catch (Exception e) {
            major = 0;
        }

        report.append("[Java]\n");
        report.append("  Version: ").append(version);
        if (major >= 21) {
            report.append(" ✅\n");
        } else {
            report.append(" ⚠️ (需要 >= 21)\n");
        }
        report.append("\n");
    }

    private static void checkConfigFile(StringBuilder report, Path configPath) {
        report.append("[配置文件]\n");
        if (configPath != null && Files.isRegularFile(configPath)) {
            report.append("  路径: ").append(configPath.toAbsolutePath()).append(" ✅\n");
        } else {
            report.append("  未找到配置文件 ⚠️\n");
        }
        report.append("\n");
    }

    private static void checkLlmConfig(StringBuilder report, ConfigSnapshot config) {
        report.append("[LLM 配置]\n");
        String baseUrl = config.llmBaseUrl();
        String apiKey = config.llmApiKey();
        String model = config.llmModel();

        report.append("  Base URL: ");
        if (baseUrl != null && !baseUrl.isBlank()) {
            report.append(baseUrl).append(" ✅\n");
        } else {
            report.append("(未配置) ❌\n");
        }

        report.append("  API Key:  ");
        if (apiKey != null && !apiKey.isBlank()) {
            report.append(config.maskedApiKey()).append(" ✅\n");
        } else {
            report.append("(未配置) ❌\n");
        }

        report.append("  Model:    ");
        if (model != null && !model.isBlank()) {
            report.append(model).append(" ✅\n");
        } else {
            report.append("(未配置) ❌\n");
        }

        report.append("  总体:     ");
        if (config.llmConfigured()) {
            report.append("已配置 ✅\n");
        } else {
            report.append("未配置 ❌ (执行 /config init)\n");
        }
        report.append("\n");
    }

    private static void checkDirectories(StringBuilder report, ConfigSnapshot config) {
        report.append("[数据目录]\n");
        checkWritable(report, "data", config.dataDir());
        checkWritable(report, "import", config.importDir());
        checkWritable(report, "output", config.outputDir());
        checkWritable(report, "log", config.logDir());
        report.append("\n");
    }

    private static void checkWritable(StringBuilder report, String label, Path dir) {
        try {
            Files.createDirectories(dir);
            if (Files.isWritable(dir)) {
                report.append("  ").append(label).append(": ").append(dir).append(" ✅\n");
            } else {
                report.append("  ").append(label).append(": ").append(dir).append(" ❌ 不可写\n");
            }
        } catch (IOException e) {
            report.append("  ")
                    .append(label)
                    .append(": ")
                    .append(dir)
                    .append(" ❌ ")
                    .append(e.getMessage())
                    .append("\n");
        }
    }

    private static void checkLlmConnectivity(StringBuilder report, ConfigSnapshot config) {
        report.append("[LLM 连通性]\n");
        if (!config.llmConfigured()) {
            report.append("  跳过（LLM 未配置）\n");
        } else {
            String result = testLlmConnectivity(config);
            report.append("  ").append(result).append("\n");
        }
    }
}
