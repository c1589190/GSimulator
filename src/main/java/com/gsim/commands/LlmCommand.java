package com.gsim.commands;

import com.gsim.llm.LlmConfigManager;
import com.gsim.llm.LlmProviderRegistry;

import java.util.List;
import java.util.Map;

/**
 * /llm — 查询和修改 LLM provider 配置。
 *
 * <p>子命令：
 * <ul>
 *   <li>/llm list — 列出所有 LLM provider</li>
 *   <li>/llm show &lt;id&gt; — 查看单个 provider 详情</li>
 *   <li>/llm set &lt;id&gt; &lt;field&gt; &lt;value&gt; — 修改 provider 字段</li>
 *   <li>/llm test &lt;id&gt; — 测试 provider 连通性</li>
 * </ul>
 */
public final class LlmCommand {

    private final LlmConfigManager configManager;
    private final LlmProviderRegistry registry;

    public LlmCommand(LlmConfigManager configManager, LlmProviderRegistry registry) {
        this.configManager = configManager;
        this.registry = registry;
    }

    public String execute(List<String> args) {
        if (args.isEmpty()) return usage();
        return switch (args.get(0)) {
            case "list" -> listProviders();
            case "show" -> showProvider(args);
            case "set" -> setProvider(args);
            case "test" -> testProvider(args);
            default -> "Unknown subcommand: " + args.get(0) + "\n" + usage();
        };
    }

    private String usage() {
        return """
            Usage: /llm <subcommand>
              list                      List all LLM providers
              show <id>                 Show provider details
              set <id> <field> <value>  Update provider field
              test <id>                 Test provider connectivity

            Fields: name, baseUrl, apiKey, model, temperature, maxTokens""";
    }

    private String listProviders() {
        var providers = configManager.listProviders();
        if (providers.isEmpty()) return "No LLM providers configured.";
        StringBuilder sb = new StringBuilder("LLM Providers:\n");
        for (var p : providers) {
            sb.append(String.format("  %s %-20s %-20s %s\n",
                    Boolean.TRUE.equals(p.get("isDefault")) ? "*" : " ",
                    p.get("id"),
                    p.get("model"),
                    p.get("name")));
        }
        sb.append("\n  * = default");
        return sb.toString();
    }

    private String showProvider(List<String> args) {
        if (args.size() < 2) return "Usage: /llm show <id>";
        var p = configManager.getProvider(args.get(1));
        if (p == null) return "Provider not found: " + args.get(1);
        StringBuilder sb = new StringBuilder("Provider: " + args.get(1) + "\n");
        for (var entry : p.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    private String setProvider(List<String> args) {
        if (args.size() < 4) return "Usage: /llm set <id> <field> <value>";
        String value = args.get(3);
        // 支持带空格的 value：合并 args[3..]
        if (args.size() > 4) {
            value = String.join(" ", args.subList(3, args.size()));
        }
        var result = configManager.updateProvider(args.get(1), args.get(2), value);
        return result.success() ? "OK: " + result.message() : "ERROR: " + result.message();
    }

    private String testProvider(List<String> args) {
        if (args.size() < 2) return "Usage: /llm test <id>";
        return configManager.testProvider(args.get(1), registry);
    }
}
