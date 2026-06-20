package com.gsim.agent.tool;

import com.gsim.agent.ToolGroup;
import com.gsim.agent.ToolGroupManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * activate_tool_groups — 控制流工具，激活指定的工具组以便后续调用组内工具。
 *
 * <p>此工具始终可用（无需激活），调用后立即生效（同一轮内后续工具可见新激活组的工具）。
 * 激活状态在每轮对话开始时重置（不跨用户对话保留）。
 *
 * <p>参数：
 * <ul>
 *   <li>groups — 必填，要激活的工具组 key 列表（JSON 字符串数组格式），
 *       例如 ["player_action","knowledge"]</li>
 * </ul>
 */
public class ActivateToolGroupsTool implements AgentTool {

    public static final String NAME = "activate_tool_groups";

    private final ToolGroupManager groupManager;

    public ActivateToolGroupsTool(ToolGroupManager groupManager) {
        this.groupManager = groupManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder();
        sb.append("激活指定的工具组，以便使用组内工具。激活后立即生效，同轮内后续工具调用可来自新激活的组。");
        sb.append("参数: groups（必填，以 JSON 字符串数组形式传入要激活的组 key 列表，");
        sb.append("例如 \"[\\\"player_action\\\",\\\"knowledge\\\"]\"）。");
        sb.append("可用组 key：");
        var keys = new ArrayList<String>();
        for (var g : ToolGroup.ALL_GROUPS) {
            keys.add(g.key());
        }
        sb.append(String.join(", ", keys));
        sb.append("。每个 key 的含义见系统 prompt 中的工具组目录。");
        return sb.toString();
    }

    @Override
    public java.util.Map<String, Object> getParameters() {
        java.util.Map<String, java.util.Map<String, Object>> props = new java.util.LinkedHashMap<>();
        props.put("groups", java.util.Map.of(
                "type", "string",
                "description", "要激活的工具组 key 列表，JSON 字符串数组格式，如 \"[\\\"player_action\\\","
                        + "\\\"knowledge\\\"]\"。不要传入不存在的组 key。"));
        return com.gsim.llm.ToolDef.strictSchema(props, java.util.List.of("groups"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String groupsRaw = call.param("groups", "");

        if (groupsRaw.isBlank()) {
            return ToolResult.fail(NAME, "groups 不能为空。请提供要激活的工具组 key 列表。");
        }

        // 解析 JSON 字符串数组
        List<String> groupKeys;
        try {
            groupKeys = parseGroupKeys(groupsRaw);
        } catch (Exception e) {
            return ToolResult.fail(NAME,
                    "groups 格式无效：" + e.getMessage()
                            + "。请使用 JSON 字符串数组格式，例如 [\"player_action\",\"knowledge\"]。");
        }

        if (groupKeys.isEmpty()) {
            return ToolResult.fail(NAME, "groups 列表不能为空。请提供至少一个有效的组 key。");
        }

        // 验证并激活
        List<String> activated = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> alreadyActive = new ArrayList<>();

        for (String key : groupKeys) {
            String trimmed = key.trim();
            if (trimmed.isEmpty()) continue;

            ToolGroup g = ToolGroup.findByKey(trimmed);
            if (g == null) {
                unknown.add(trimmed);
                continue;
            }

            if (groupManager.activeGroupKeys().contains(trimmed)) {
                alreadyActive.add(trimmed);
                continue;
            }

            groupManager.activate(trimmed);
            activated.add(trimmed);
        }

        // 构建结果摘要
        StringBuilder resultText = new StringBuilder();
        if (!activated.isEmpty()) {
            resultText.append("已激活组: ").append(String.join(", ", activated));
            int newTools = 0;
            for (String k : activated) {
                ToolGroup g = ToolGroup.findByKey(k);
                if (g != null) newTools += g.memberTools().size();
            }
            resultText.append("（新增 ").append(newTools).append(" 个可用工具）。");
        }
        if (!alreadyActive.isEmpty()) {
            if (!resultText.isEmpty()) resultText.append(" ");
            resultText.append("已处于激活状态: ").append(String.join(", ", alreadyActive)).append("。");
        }
        if (!unknown.isEmpty()) {
            if (!resultText.isEmpty()) resultText.append(" ");
            resultText.append("未知组 key（已忽略）: ").append(String.join(", ", unknown))
                    .append("。可用组 key: ");
            for (var g : ToolGroup.ALL_GROUPS) {
                resultText.append(g.key()).append(" ");
            }
            resultText.append("。");
        }

        // 列出当前所有可用工具
        var allowed = groupManager.computeAllowedTools();
        resultText.append(" 当前可用工具数: ").append(allowed.size()).append("。");

        var items = new ArrayList<ToolResult.Item>();
        items.add(new ToolResult.Item(
                "activate_tool_groups: " + (activated.isEmpty() ? "无变更" : String.join(",", activated)),
                NAME,
                resultText.toString(),
                1.0));

        return ToolResult.ok(NAME, items);
    }

    /**
     * 解析 groups 参数：可以是标准 JSON 数组字符串，或 LLM 可能生成的变体。
     */
    static List<String> parseGroupKeys(String raw) {
        String trimmed = raw.trim();

        // 标准 JSON 数组: ["a","b"]
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            // 去掉方括号
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) return List.of();

            List<String> result = new ArrayList<>();
            // 简单解析：按逗号分割，去掉引号
            String[] parts = inner.split(",");
            for (String part : parts) {
                String cleaned = part.trim();
                // 去掉引号
                if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                    cleaned = cleaned.substring(1, cleaned.length() - 1);
                }
                if (!cleaned.isEmpty()) {
                    result.add(cleaned);
                }
            }
            return result;
        }

        // 逗号分隔的纯文本: a,b,c
        if (trimmed.contains(",")) {
            List<String> result = new ArrayList<>();
            for (String part : trimmed.split(",")) {
                String cleaned = part.trim();
                if (!cleaned.isEmpty()) {
                    result.add(cleaned);
                }
            }
            return result;
        }

        // 单个 key
        return List.of(trimmed);
    }
}
