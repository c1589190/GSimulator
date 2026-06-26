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

        // Step 6: timeout
        String timeout = askTimeout(console);
        if (timeout == null) return null;

        // Step 7: 写入文件
        writeConfigFile(configPath, baseUrl, apiKey, model, timeout);

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
                                        String timeout) {
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
                w.write("worlds.dir=worlds\n");
                w.write("\n");
                w.write("# Tool loop: max LLM rounds per /chat (default 64)\n");
                w.write("agent.tool_loop.max_rounds=64\n");
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

        writeConfigFile(configPath, baseUrl, apiKey, model, timeout);
        System.out.println("\n配置已保存到: " + configPath.toAbsolutePath());
        System.out.println("API Key 已保存到本地文件，请勿提交到 Git。\n");

        return configPath;
    }

}
