package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;

import java.util.Map;

/**
 * /tool 命令 — 调用已注册的 AgentTool。
 *
 * 用法：
 *   /tool wiki_search <关键词>    — 搜索本地 Wiki txt 文件
 *   /tool list                     — 列出所有可用工具
 */
public class ToolCommand implements InteractionCommand {

    private final ToolRegistry registry;

    public ToolCommand(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "tool";
    }

    @Override
    public String description() {
        return "调用工具。支持: wiki_search <关键词>";
    }

    @Override
    public String usage() {
        return "/tool wiki_search <关键词>   — 搜索本地 Wiki txt 文件\n" +
                "/tool list                    — 列出所有可用工具";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args == null || args.length == 0 || (args.length == 1 && args[0].isBlank())) {
            return InteractionResult.fail("Usage: /tool <tool_name> [args...] or /tool list");
        }

        // CommandParser 默认按空格分割参数，将其重新拼合成完整字符串再解析
        String fullArgs = String.join(" ", args);
        String[] tokens = fullArgs.split("\\s+");
        String subCommand = tokens[0];

        if ("list".equals(subCommand)) {
            return listTools();
        }

        if ("wiki_search".equals(subCommand)) {
            String keyword = tokens.length > 1 ? tokens[1] : "";
            // 支持多词关键词
            if (tokens.length > 2) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < tokens.length; i++) {
                    if (i > 1) sb.append(" ");
                    sb.append(tokens[i]);
                }
                keyword = sb.toString();
            }
            return doWikiSearch(keyword);
        }

        return InteractionResult.fail("Unknown tool: " + subCommand +
                ". Use /tool list to see available tools.");
    }

    private InteractionResult listTools() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Available Tools ===\n");
        for (var entry : registry.all().entrySet()) {
            sb.append("  /tool ").append(entry.getKey())
                    .append(" — ").append(entry.getValue().description()).append("\n");
        }
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult doWikiSearch(String keyword) {
        if (keyword.isBlank()) {
            return InteractionResult.fail("wiki_search requires a keyword. Usage: /tool wiki_search <关键词>");
        }

        ToolCall call = new ToolCall("wiki_search", Map.of("keyword", keyword, "max_results", "5"));
        ToolResult result = registry.call(call);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Wiki 搜索结果 ===\n");
        sb.append("关键词: ").append(keyword).append("\n");
        sb.append("结果数: ").append(result.items().size()).append("\n\n");

        for (int i = 0; i < result.items().size(); i++) {
            ToolResult.Item item = result.items().get(i);
            sb.append("【").append(i + 1).append("】 ").append(item.title()).append("\n");
            sb.append("    文件: ").append(item.path()).append("\n");
            sb.append("    得分: ").append(String.format("%.1f", item.score())).append("\n");
            sb.append("    片段: ").append(item.snippet()).append("\n\n");
        }

        if (!result.success()) {
            sb.append("⚠ 错误: ").append(result.error()).append("\n");
        }

        return InteractionResult.ok(result.success() ? "wiki_search: " + keyword : "error",
                sb.toString().trim());
    }
}
