package com.gsim.commands;

import com.gsim.agent.config.AgentConfigManager;

import java.util.List;
import java.util.Map;

/**
 * /agent — 查询和修改 Agent 配置。
 *
 * <p>子命令：
 * <ul>
 *   <li>/agent list — 列出所有 agent 配置</li>
 *   <li>/agent show &lt;id&gt; — 查看单个 agent 详情</li>
 *   <li>/agent set &lt;id&gt; &lt;field&gt; &lt;value&gt; — 修改 agent 字段</li>
 *   <li>/agent reload — 重新加载所有 agent 配置</li>
 * </ul>
 */
public final class AgentCommand {

    private final AgentConfigManager configManager;

    public AgentCommand(AgentConfigManager configManager) {
        this.configManager = configManager;
    }

    public String execute(List<String> args) {
        if (args.isEmpty()) return usage();
        return switch (args.get(0)) {
            case "list" -> listAgents();
            case "show" -> showAgent(args);
            case "set" -> setAgent(args);
            case "reload" -> configManager.reload();
            default -> "Unknown subcommand: " + args.get(0) + "\n" + usage();
        };
    }

    private String usage() {
        return """
            Usage: /agent <subcommand>
              list                      List all agent configs
              show <id>                 Show agent details
              set <id> <field> <value>  Update agent field
              reload                    Reload all agent configs from disk

            Fields: llmProvider, temperature, maxTokens, maxToolRounds, toolFilter,
                    staticSystemPrompt""";
    }

    private String listAgents() {
        var agents = configManager.listAgents();
        if (agents.isEmpty()) return "No agent configs loaded.";
        StringBuilder sb = new StringBuilder("Agent Configs:\n");
        for (var a : agents) {
            sb.append(String.format("  %-20s provider=%-10s temp=%.1f rounds=%d tokens=%d filter=%s\n",
                    a.get("agentId"),
                    a.get("llmProvider"),
                    a.get("temperature"),
                    a.get("maxToolRounds"),
                    a.get("maxTokens"),
                    a.get("toolFilterMode")));
        }
        return sb.toString();
    }

    private String showAgent(List<String> args) {
        if (args.size() < 2) return "Usage: /agent show <id>";
        var a = configManager.getAgent(args.get(1));
        if (a == null) return "Agent not found: " + args.get(1);
        StringBuilder sb = new StringBuilder("Agent: " + args.get(1) + "\n");
        for (var entry : a.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Map<?, ?> m) {
                sb.append("  ").append(entry.getKey()).append(":\n");
                for (var inner : m.entrySet()) {
                    sb.append("    ").append(inner.getKey()).append(": ").append(inner.getValue()).append("\n");
                }
            } else {
                sb.append("  ").append(entry.getKey()).append(": ").append(v).append("\n");
            }
        }
        return sb.toString();
    }

    private String setAgent(List<String> args) {
        if (args.size() < 4) return "Usage: /agent set <id> <field> <value>";
        String value = args.get(3);
        if (args.size() > 4) {
            value = String.join(" ", args.subList(3, args.size()));
        }
        var result = configManager.updateAgent(args.get(1), args.get(2), value);
        return result.success() ? "OK: " + result.message() : "ERROR: " + result.message();
    }
}
