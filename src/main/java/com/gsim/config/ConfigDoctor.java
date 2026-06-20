package com.gsim.config;

import com.gsim.app.AppConfig;
import com.gsim.llm.LlmManager;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResult;
import com.gsim.llm.ProviderConfig;

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
     * 运行诊断并返回报告文本。
     */
    public static String diagnose(AppConfig appConfig) {
        StringBuilder report = new StringBuilder();
        report.append("========== GSimulator 配置诊断 ==========\n\n");

        // 1. Java 版本
        checkJavaVersion(report);

        // 2. 配置文件
        checkConfigFile(report, appConfig.getConfigPath());

        // 3. LLM 配置
        checkLlmConfig(report, appConfig);

        // 4. 目录可写
        checkDirectories(report, appConfig);

        // 5. LLM 连通性
        checkLlmConnectivity(report, appConfig);

        report.append("\n=========================================\n");
        return report.toString();
    }

    /**
     * 快速测试 LLM 连通性。
     */
    public static String testLlmConnectivity(AppConfig appConfig) {
        if (!appConfig.isLlmConfigured()) {
            return "❌ LLM 未配置，无法测试。";
        }
        return testLlmConnectivity(
                appConfig.getLlmBaseUrl(),
                appConfig.getLlmApiKey(),
                appConfig.getLlmModel(),
                appConfig.getLlmTimeoutSeconds());
    }

    /**
     * 用给定参数测试 LLM 连通性，返回结果描述。
     */
    public static String testLlmConnectivity(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        if (baseUrl == null || baseUrl.isBlank()) return "❌ Base URL 为空。";
        if (apiKey == null || apiKey.isBlank()) return "❌ API Key 为空。";
        if (model == null || model.isBlank()) return "❌ Model 为空。";

        // 使用短 timeout
        int testTimeout = Math.min(timeoutSeconds, 10);
        LlmManager llmManager = new LlmManager(ProviderConfig.generic(
                "test", baseUrl, apiKey, model, 0.0, testTimeout));

        if (!llmManager.isAvailable()) {
            llmManager.close();
            return "❌ LLM 客户端不可用（配置不完整）。";
        }

        try {
            LlmRequest req = new LlmRequest(
                    model,
                    List.of(new LlmMessage("user", "Say \"OK\" and nothing else.")),
                    0.0, 10);
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

    private static void checkLlmConfig(StringBuilder report, AppConfig appConfig) {
        report.append("[LLM 配置]\n");
        String baseUrl = appConfig.getLlmBaseUrl();
        String apiKey = appConfig.getLlmApiKey();
        String model = appConfig.getLlmModel();

        report.append("  Base URL: ");
        if (baseUrl != null && !baseUrl.isBlank()) {
            report.append(baseUrl).append(" ✅\n");
        } else {
            report.append("(未配置) ❌\n");
        }

        report.append("  API Key:  ");
        if (apiKey != null && !apiKey.isBlank()) {
            report.append(appConfig.maskedApiKey()).append(" ✅\n");
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
        if (appConfig.isLlmConfigured()) {
            report.append("已配置 ✅\n");
        } else {
            report.append("未配置 ❌ (执行 /config init)\n");
        }
        report.append("\n");
    }

    private static void checkDirectories(StringBuilder report, AppConfig appConfig) {
        report.append("[数据目录]\n");
        checkWritable(report, "data", appConfig.getDataDir());
        checkWritable(report, "import", appConfig.getImportDir());
        checkWritable(report, "output", appConfig.getOutputDir());
        checkWritable(report, "log", appConfig.getLogDir());
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
            report.append("  ").append(label).append(": ").append(dir).append(" ❌ ").append(e.getMessage()).append("\n");
        }
    }

    private static void checkLlmConnectivity(StringBuilder report, AppConfig appConfig) {
        report.append("[LLM 连通性]\n");
        if (!appConfig.isLlmConfigured()) {
            report.append("  跳过（LLM 未配置）\n");
        } else {
            String result = testLlmConnectivity(appConfig);
            report.append("  ").append(result).append("\n");
        }
    }
}
