package com.gsim.config;

import com.gsim.llm.LlmConfig;
import com.gsim.llm.LlmsConfigFile;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * 首次运行配置向导。
 * 交互式引导用户创建 llms.json。
 */
public class ConfigWizard {

    private static final Map<String, ChannelInfo> CHANNELS = new LinkedHashMap<>();

    static {
        CHANNELS.put("1", new ChannelInfo("DeepSeek 官方", "https://api.deepseek.com/v1", "deepseek-chat"));
        CHANNELS.put("2", new ChannelInfo("讯飞星辰 MaaS", "", ""));
        CHANNELS.put("3", new ChannelInfo("OpenAI-compatible", "", ""));
        CHANNELS.put("4", new ChannelInfo("自定义", "", ""));
    }

    public record ChannelInfo(String name, String defaultBaseUrl, String defaultModel) {}

    /**
     * 运行配置向导，创建 llms.json。返回 llms.json 路径（用户可能选择退出返回 null）。
     */
    public static Path run() {
        Console console = System.console();
        if (console == null) {
            return runWithScanner();
        }

        console.printf("\n");
        console.printf("╔══════════════════════════════════════════╗\n");
        console.printf("║     GSimulator 首次运行 — LLM 配置        ║\n");
        console.printf("╚══════════════════════════════════════════╝\n");
        console.printf("\n");
        console.printf("LLM 配置将保存到当前目录的 llms.json 文件中。\n");
        console.printf("你也可以稍后手动编辑该文件添加更多 provider。\n");
        console.printf("\n");

        // 确定 llms.json 路径
        Path llmsPath = Path.of("llms.json").toAbsolutePath();
        if (Files.exists(llmsPath)) {
            console.printf("llms.json 已存在，将覆盖其中的 base provider。\n");
        }

        // Step 1: 选择模型通道
        ChannelInfo channel = chooseChannel(console);
        if (channel == null) return null;

        // Step 2: base URL
        String baseUrl = askBaseUrl(console, channel);
        if (baseUrl == null) return null;

        // Step 3: API Key
        String apiKey = askApiKey(console);
        if (apiKey == null) return null;

        // Step 4: 模型名
        String model = askModel(console, channel);
        if (model == null) return null;

        // Step 5: 写入 llms.json
        writeLlmsJson(llmsPath, baseUrl, apiKey, model);

        console.printf("\n✅ LLM 配置已保存到: %s\n", llmsPath);
        console.printf("   Provider: base\n");
        console.printf("   Base URL: %s\n", baseUrl);
        console.printf("   Model:    %s\n", model);
        console.printf("⚠️  API Key 已保存到本地文件，请勿提交到 Git。\n");
        console.printf("\n");

        // 询问是否测试连通性
        console.printf("是否测试 LLM 连通性？(y/n) [y]: ");
        String testChoice = console.readLine().trim();
        if (testChoice.isEmpty() || "y".equalsIgnoreCase(testChoice) || "yes".equalsIgnoreCase(testChoice)) {
            console.printf("\n正在测试连通性...\n");
            String result = ConfigDoctor.testLlmConnectivity(baseUrl, apiKey, model, 300);
            console.printf("%s\n", result);
        }

        return llmsPath;
    }

    private static ChannelInfo chooseChannel(Console console) {
        console.printf("请选择模型通道：\n");
        for (var entry : CHANNELS.entrySet()) {
            console.printf("  %s. %s\n", entry.getKey(), entry.getValue().name());
        }
        console.printf("\n");

        while (true) {
            console.printf("请选择 [1]: ");
            String choice = console.readLine().trim();
            if (choice.isEmpty()) choice = "1";

            ChannelInfo channel = CHANNELS.get(choice);
            if (channel != null) return channel;
            console.printf("无效选择，请重试。\n");
        }
    }

    private static String askBaseUrl(Console console, ChannelInfo channel) {
        String prompt;
        String defaultVal;
        if (!channel.defaultBaseUrl().isEmpty()) {
            prompt = String.format("Base URL [%s]: ", channel.defaultBaseUrl());
            defaultVal = channel.defaultBaseUrl();
        } else {
            prompt = "Base URL: ";
            defaultVal = "";
        }

        while (true) {
            console.printf(prompt);
            String input = console.readLine().trim();
            if (input.isEmpty()) {
                if (!defaultVal.isEmpty()) return defaultVal;
                console.printf("Base URL 不能为空。\n");
                continue;
            }
            if (input.endsWith("/")) input = input.substring(0, input.length() - 1);
            return input;
        }
    }

    private static String askApiKey(Console console) {
        console.printf("\n");
        while (true) {
            console.printf("API Key: ");
            char[] keyChars = console.readPassword();
            if (keyChars == null || keyChars.length == 0) {
                console.printf("API Key 不能为空。\n");
                continue;
            }
            return new String(keyChars);
        }
    }

    private static String askModel(Console console, ChannelInfo channel) {
        String prompt;
        String defaultVal;
        if (!channel.defaultModel().isEmpty()) {
            prompt = String.format("模型名 [%s]: ", channel.defaultModel());
            defaultVal = channel.defaultModel();
        } else {
            prompt = "模型名 [deepseek-v4-pro]: ";
            defaultVal = "deepseek-v4-pro";
        }

        while (true) {
            console.printf(prompt);
            String input = console.readLine().trim();
            if (input.isEmpty()) return defaultVal;
            return input;
        }
    }

    /** 写入 llms.json，保留已有的其他 provider，更新/创建 base。 */
    private static void writeLlmsJson(Path llmsPath, String baseUrl, String apiKey, String model) {
        try {
            LlmsConfigFile file;
            if (Files.exists(llmsPath)) {
                // 加载已有配置，只更新 base provider
                file = LlmsConfigFile.load(llmsPath);
                LlmConfig existingBase = file.find("base");
                List<LlmConfig> providers = new java.util.ArrayList<>(file.providers());
                if (existingBase != null) {
                    providers.remove(existingBase);
                }
                // 插入新的 base 在最前面
                LlmConfig newBase = new LlmConfig("base", "Base LLM", baseUrl, apiKey, model,
                        0.3, 4096, null, null, true);
                providers.add(0, newBase);
                file = new LlmsConfigFile(file.version(), providers);
            } else {
                // 新建
                LlmConfig baseProvider = new LlmConfig("base", "Base LLM", baseUrl, apiKey, model,
                        0.3, 4096, null, null, true);
                file = new LlmsConfigFile(1, List.of(baseProvider));
            }
            file.save(llmsPath);
        } catch (IOException e) {
            System.err.println("写入 llms.json 失败: " + e.getMessage());
        }
    }

    // ---- Scanner fallback (non-interactive terminal) ----

    private static Path runWithScanner() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("\n=== GSimulator 首次运行 — LLM 配置 ===\n");
        System.out.println("LLM 配置将保存到当前目录的 llms.json。");
        System.out.println("注意：当前终端不支持密码隐藏，API Key 将明文显示。\n");

        Path llmsPath = Path.of("llms.json").toAbsolutePath();

        System.out.println("选择通道 [1=DeepSeek, 2=讯飞星辰, 3=OpenAI, 4=自定义] [1]: ");
        String ch = scanner.nextLine().trim();
        if (ch.isEmpty()) ch = "1";
        ChannelInfo channel = CHANNELS.getOrDefault(ch, CHANNELS.get("1"));

        String baseUrl;
        if (!channel.defaultBaseUrl().isEmpty()) {
            System.out.printf("Base URL [%s]: ", channel.defaultBaseUrl());
            baseUrl = scanner.nextLine().trim();
            if (baseUrl.isEmpty()) baseUrl = channel.defaultBaseUrl();
        } else {
            System.out.print("Base URL: ");
            baseUrl = scanner.nextLine().trim();
        }
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        System.out.print("API Key: ");
        String apiKey = scanner.nextLine().trim();

        String defaultModel = !channel.defaultModel().isEmpty() ? channel.defaultModel() : "deepseek-v4-pro";
        System.out.printf("模型名 [%s]: ", defaultModel);
        String model = scanner.nextLine().trim();
        if (model.isEmpty()) model = defaultModel;

        writeLlmsJson(llmsPath, baseUrl, apiKey, model);
        System.out.println("\n✅ LLM 配置已保存到: " + llmsPath);
        System.out.println("   Provider: base, Model: " + model);
        System.out.println("⚠️  API Key 已保存到本地文件，请勿提交到 Git。\n");

        return llmsPath;
    }
}
