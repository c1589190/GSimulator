package com.gsim.config;

import java.io.BufferedWriter;
import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * 首次运行配置向导。
 * 交互式引导用户创建配置文件。
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
     * Embedding 模型独立配置。
     * @param useSeparateConfig 是否使用独立于 LLM 的配置
     * @param baseUrl           embedding API 地址（仅 useSeparateConfig=true 时有效）
     * @param apiKey            embedding API 密钥（仅 useSeparateConfig=true 时有效）
     * @param model             embedding 模型名（仅 useSeparateConfig=true 时有效）
     */
    public record EmbeddingConfig(boolean useSeparateConfig,
                                  String baseUrl, String apiKey, String model) {
        /** 使用与 LLM 相同的配置（默认）。 */
        public static EmbeddingConfig sameAsLlm() {
            return new EmbeddingConfig(false, "", "", "");
        }

        /** 使用独立的 embedding 配置。 */
        public static EmbeddingConfig separate(String baseUrl, String apiKey, String model) {
            return new EmbeddingConfig(true, baseUrl, apiKey, model);
        }
    }

    /**
     * 运行配置向导，返回创建的配置文件路径（用户可能选择退出返回 null）。
     */
    public static Path run() {
        Console console = System.console();
        if (console == null) {
            // fallback to Scanner
            return runWithScanner();
        }

        console.printf("\n");
        console.printf("╔══════════════════════════════════════════╗\n");
        console.printf("║     GSimulator 首次运行配置向导           ║\n");
        console.printf("╚══════════════════════════════════════════╝\n");
        console.printf("\n");
        console.printf("未找到 LLM 配置。你需要配置 LLM API 才能使用 /chat 和 /sim。\n");
        console.printf("\n");

        // Step 1: 选择配置位置
        Path configPath = chooseLocation(console);
        if (configPath == null) return null;

        // Step 2: 选择模型通道
        ChannelInfo channel = chooseChannel(console);
        if (channel == null) return null;

        // Step 3: 填写 base URL
        String baseUrl = askBaseUrl(console, channel);
        if (baseUrl == null) return null;

        // Step 4: 填写 API Key
        String apiKey = askApiKey(console);
        if (apiKey == null) return null;

        // Step 5: 填写模型名
        String model = askModel(console, channel);
        if (model == null) return null;

        // Step 6: Embedding 模型配置
        EmbeddingConfig embConfig = askEmbeddingConfig(console, baseUrl, apiKey, model);
        if (embConfig == null) return null;

        // Step 7: timeout
        String timeout = askTimeout(console);
        if (timeout == null) return null;

        // Step 8: 写入文件
        writeConfigFile(configPath, baseUrl, apiKey, model, timeout, embConfig);

        console.printf("\n✅ 配置已保存到: %s\n", configPath.toAbsolutePath());
        console.printf("⚠️  API Key 已保存到本地文件，请勿提交到 Git。\n");
        console.printf("\n");

        // Step 9: 询问是否测试连通性
        console.printf("是否测试 LLM 连通性？(y/n) [y]: ");
        String testChoice = console.readLine().trim();
        if (testChoice.isEmpty() || "y".equalsIgnoreCase(testChoice) || "yes".equalsIgnoreCase(testChoice)) {
            console.printf("\n正在测试连通性...\n");
            String result = ConfigDoctor.testLlmConnectivity(baseUrl, apiKey, model, Integer.parseInt(timeout));
            console.printf("%s\n", result);
        }

        return configPath;
    }

    private static Path chooseLocation(Console console) {
        console.printf("请选择配置文件保存位置：\n");
        console.printf("  1. 当前目录 (./gsim.properties)\n");
        console.printf("  2. 用户目录 (~/.gsimulator/config.properties)\n");
        console.printf("  3. 暂不配置，进入离线模式\n");
        console.printf("  4. 退出\n");
        console.printf("\n");

        while (true) {
            console.printf("请选择 [1]: ");
            String choice = console.readLine().trim();
            if (choice.isEmpty()) choice = "1";

            switch (choice) {
                case "1":
                    return Path.of("gsim.properties").toAbsolutePath();
                case "2":
                    Path userDir = ConfigLoader.userConfigDir();
                    try {
                        Files.createDirectories(userDir);
                    } catch (IOException e) {
                        console.printf("无法创建目录 %s: %s\n", userDir, e.getMessage());
                        return null;
                    }
                    return userDir.resolve("config.properties");
                case "3":
                    console.printf("\n进入离线模式。LLM 功能 (/chat, /sim) 将不可用。\n");
                    console.printf("稍后可使用 /config init 重新配置。\n\n");
                    return null;
                case "4":
                    console.printf("已退出。\n");
                    System.exit(0);
                    return null;
                default:
                    console.printf("无效选择，请重试。\n");
            }
        }
    }

    private static ChannelInfo chooseChannel(Console console) {
        console.printf("\n请选择模型通道：\n");
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
            // 确保不以 / 结尾
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

    /**
     * Step 6: 询问 Embedding 模型是否使用独立配置。
     * Embedding 模型可能使用与 LLM 不同的 API 地址、密钥和模型名，
     * 因此需要独立询问而非直接复制 LLM 配置。
     */
    private static EmbeddingConfig askEmbeddingConfig(Console console,
                                                       String llmBaseUrl, String llmApiKey, String llmModel) {
        console.printf("\n--- Embedding 模型配置 ---\n");
        console.printf("Embedding 模型用于知识库的向量化检索（/searchdb）。\n");
        console.printf("它可以与 LLM 使用相同的 API，也可以使用不同的 API。\n");
        console.printf("\n");
        console.printf("是否使用独立的 Embedding API 配置？\n");
        console.printf("  y = 使用独立配置（API 地址/密钥/模型名与 LLM 不同）\n");
        console.printf("  n = 与 LLM 使用相同配置（默认）\n");
        console.printf("\n");

        while (true) {
            console.printf("使用独立 Embedding 配置？(y/n) [n]: ");
            String choice = console.readLine().trim();
            if (choice.isEmpty() || "n".equalsIgnoreCase(choice) || "no".equalsIgnoreCase(choice)) {
                return EmbeddingConfig.sameAsLlm();
            }
            if ("y".equalsIgnoreCase(choice) || "yes".equalsIgnoreCase(choice)) {
                break;
            }
            console.printf("无效选择，请输入 y 或 n。\n");
        }

        // 填写 Embedding Base URL
        String embBaseUrl;
        while (true) {
            console.printf("Embedding Base URL [%s]: ", llmBaseUrl);
            String input = console.readLine().trim();
            if (input.isEmpty()) {
                embBaseUrl = llmBaseUrl;
            } else {
                if (input.endsWith("/")) input = input.substring(0, input.length() - 1);
                embBaseUrl = input;
            }
            if (!embBaseUrl.isBlank()) break;
            console.printf("Embedding Base URL 不能为空。\n");
        }

        // 填写 Embedding API Key
        String embApiKey;
        console.printf("\n");
        while (true) {
            console.printf("Embedding API Key [使用 LLM API Key]: ");
            char[] keyChars = console.readPassword();
            if (keyChars == null || keyChars.length == 0) {
                embApiKey = llmApiKey;
                break;
            }
            embApiKey = new String(keyChars);
            if (!embApiKey.isBlank()) break;
            console.printf("Embedding API Key 不能为空。\n");
        }

        // 填写 Embedding 模型名
        String embModel;
        while (true) {
            console.printf("Embedding 模型名 [%s]: ", llmModel);
            String input = console.readLine().trim();
            if (input.isEmpty()) {
                embModel = llmModel;
            } else {
                embModel = input;
            }
            if (!embModel.isBlank()) break;
            console.printf("Embedding 模型名不能为空。\n");
        }

        return EmbeddingConfig.separate(embBaseUrl, embApiKey, embModel);
    }

    private static String askTimeout(Console console) {
        console.printf("超时秒数 [300]: ");
        String input = console.readLine().trim();
        if (input.isEmpty()) return "300";
        try {
            Integer.parseInt(input);
            return input;
        } catch (NumberFormatException e) {
            console.printf("无效数字，使用默认值 300。\n");
            return "300";
        }
    }

    private static void writeConfigFile(Path path, String baseUrl, String apiKey, String model,
                                        String timeout, EmbeddingConfig embConfig) {
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(path)) {
                w.write("# GSimulator Configuration\n");
                w.write("# Generated by ConfigWizard\n");
                w.write("# Do NOT commit this file to Git.\n");
                w.write("\n");
                w.write("llm.base_url=" + baseUrl + "\n");
                w.write("llm.api_key=" + apiKey + "\n");
                w.write("llm.model=" + model + "\n");
                w.write("llm.temperature=0.3\n");
                w.write("llm.timeout_seconds=" + timeout + "\n");
                w.write("\n");
                w.write("data.dir=data\n");
                w.write("import.dir=import\n");
                w.write("output.dir=data/outputs\n");
                w.write("log.dir=data/logs\n");
                w.write("\n");
                w.write("chroma.enabled=false\n");
                w.write("chroma.base_url=http://localhost:8000\n");
                w.write("\n");
                w.write("web_research.enabled=false\n");
                w.write("web_research.timeout_seconds=30\n");
                w.write("web_research.user_agent=GSimulator/0.1.0\n");
                w.write("\n");
                w.write("# --- Embedding Configuration ---\n");
                w.write("embedding.provider=external\n");
                if (embConfig != null && embConfig.useSeparateConfig()) {
                    w.write("embedding.base_url=" + embConfig.baseUrl() + "\n");
                    w.write("embedding.api_key=" + embConfig.apiKey() + "\n");
                    w.write("embedding.model=" + embConfig.model() + "\n");
                } else {
                    w.write("embedding.base_url=" + baseUrl + "\n");
                    w.write("embedding.api_key=" + apiKey + "\n");
                    w.write("embedding.model=" + model + "\n");
                }
                w.write("embedding.dimensions=1024\n");
                w.write("embedding.model_dir=data/models/local-small\n");
            }
        } catch (IOException e) {
            System.err.println("写入配置文件失败: " + e.getMessage());
        }
    }

    // ---- Scanner fallback (non-interactive terminal) ----

    private static Path runWithScanner() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("\n=== GSimulator 首次运行配置向导 ===\n");
        System.out.println("未找到 LLM 配置。");
        System.out.println("注意：当前终端不支持密码隐藏，API Key 将明文显示。\n");

        System.out.println("选择配置位置 [1=./gsim.properties, 2=~/.gsimulator/config.properties, 3=离线, 4=退出] [1]: ");
        String loc = scanner.nextLine().trim();
        if (loc.isEmpty()) loc = "1";
        if ("3".equals(loc)) { System.out.println("进入离线模式。"); return null; }
        if ("4".equals(loc)) { System.exit(0); return null; }

        Path configPath;
        if ("2".equals(loc)) {
            configPath = ConfigLoader.userConfigDir().resolve("config.properties");
            try { Files.createDirectories(configPath.getParent()); } catch (IOException e) {
                System.err.println("创建目录失败: " + e.getMessage()); return null;
            }
        } else {
            configPath = Path.of("gsim.properties").toAbsolutePath();
        }

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

        System.out.print("超时秒数 [300]: ");
        String timeout = scanner.nextLine().trim();
        if (timeout.isEmpty()) timeout = "300";

        // Embedding 配置
        EmbeddingConfig embConfig = askEmbeddingConfigWithScanner(scanner, baseUrl, apiKey, model);

        writeConfigFile(configPath, baseUrl, apiKey, model, timeout, embConfig);
        System.out.println("\n配置已保存到: " + configPath.toAbsolutePath());
        System.out.println("API Key 已保存到本地文件，请勿提交到 Git。\n");

        return configPath;
    }

    private static EmbeddingConfig askEmbeddingConfigWithScanner(Scanner scanner,
                                                                   String llmBaseUrl, String llmApiKey, String llmModel) {
        System.out.println("\n--- Embedding 模型配置 ---");
        System.out.println("Embedding 模型用于知识库的向量化检索。");
        System.out.println("是否使用独立的 Embedding API 配置？(y/n) [n]: ");
        String choice = scanner.nextLine().trim();
        if (choice.isEmpty() || !"y".equalsIgnoreCase(choice) && !"yes".equalsIgnoreCase(choice)) {
            return EmbeddingConfig.sameAsLlm();
        }

        System.out.printf("Embedding Base URL [%s]: ", llmBaseUrl);
        String embBaseUrl = scanner.nextLine().trim();
        if (embBaseUrl.isEmpty()) embBaseUrl = llmBaseUrl;
        if (embBaseUrl.endsWith("/")) embBaseUrl = embBaseUrl.substring(0, embBaseUrl.length() - 1);

        System.out.print("Embedding API Key [使用 LLM API Key]: ");
        String embApiKey = scanner.nextLine().trim();
        if (embApiKey.isEmpty()) embApiKey = llmApiKey;

        System.out.printf("Embedding 模型名 [%s]: ", llmModel);
        String embModel = scanner.nextLine().trim();
        if (embModel.isEmpty()) embModel = llmModel;

        return EmbeddingConfig.separate(embBaseUrl, embApiKey, embModel);
    }
}
