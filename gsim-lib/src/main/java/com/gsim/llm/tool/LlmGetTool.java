package com.gsim.llm.tool;

import com.gsim.llm.LlmConfigManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * gsim_llm_get -- 获取指定 LLM Provider 的详细信息。
 *
 * <p>返回 provider 的 ID、名称、Base URL、模型、温度、maxTokens、isDefault 等。
 * API Key 已脱敏显示。
 */
public class LlmGetTool implements AgentTool {

    public static final String NAME = "gsim_llm_get";

    private final LlmConfigManager llmConfigManager;

    public LlmGetTool(LlmConfigManager llmConfigManager) {
        this.llmConfigManager = llmConfigManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "获取指定 LLM Provider 的详细信息（ID、名称、Base URL、模型、温度等）。API Key 已脱敏。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return com.gsim.llm.ToolDef.strictSchema(
                Map.of(
                        "id",
                        Map.of(
                                "type", "string",
                                "description", "Provider ID（如 'base'、'deepseek'）")),
                List.of("id"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String id = call.param("id");
        if (id == null || id.isBlank()) {
            return ToolResult.fail(NAME, "id is required");
        }

        try {
            Map<String, Object> provider = llmConfigManager.getProvider(id);
            if (provider == null) {
                return ToolResult.fail(NAME, "Provider not found: " + id);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## Provider: ").append(id).append("\n\n");
            sb.append("| Field | Value |\n");
            sb.append("|-------|-------|\n");
            for (var entry : provider.entrySet()) {
                sb.append("| `")
                        .append(entry.getKey())
                        .append("` | ")
                        .append(entry.getValue() != null ? entry.getValue() : "")
                        .append(" |\n");
            }

            return ToolResult.ok(NAME, List.of(new ToolResult.Item(id, NAME, sb.toString(), 1.0)));
        } catch (Exception e) {
            return ToolResult.fail(NAME, "Failed to get provider: " + e.getMessage());
        }
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
