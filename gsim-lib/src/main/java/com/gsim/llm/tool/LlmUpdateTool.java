package com.gsim.llm.tool;

import com.gsim.llm.LlmConfigManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * gsim_llm_update -- 更新 LLM Provider 的单个字段。
 *
 * <p>支持字段：name, baseUrl, apiKey, model, temperature, maxTokens。
 * 原子写入 llms.json，无需手动 reload。
 */
public class LlmUpdateTool implements AgentTool {

    public static final String NAME = "gsim_llm_update";

    private final LlmConfigManager llmConfigManager;

    public LlmUpdateTool(LlmConfigManager llmConfigManager) {
        this.llmConfigManager = llmConfigManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "更新指定 LLM Provider 的单个字段。" + "支持的字段：name, baseUrl, apiKey, model, temperature, maxTokens。" + "修改后即时生效。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return com.gsim.llm.ToolDef.strictSchema(
                Map.of(
                        "id",
                                Map.of(
                                        "type", "string",
                                        "description", "要更新的 Provider ID"),
                        "field",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "要更新的字段：name, baseUrl, apiKey, model, temperature, maxTokens"),
                        "value",
                                Map.of(
                                        "type", "string",
                                        "description", "字段的新值")),
                List.of("id", "field", "value"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String id = call.param("id");
        String field = call.param("field");
        String value = call.param("value");

        if (id == null || id.isBlank()) {
            return ToolResult.fail(NAME, "id is required");
        }
        if (field == null || field.isBlank()) {
            return ToolResult.fail(NAME, "field is required");
        }
        if (value == null) {
            return ToolResult.fail(NAME, "value is required");
        }

        try {
            LlmConfigManager.UpdateResult result = llmConfigManager.updateProvider(id, field, value);

            if (result.success()) {
                return ToolResult.ok(NAME, List.of(new ToolResult.Item(id, NAME, result.message(), 1.0)));
            } else {
                return ToolResult.fail(NAME, result.message());
            }
        } catch (Exception e) {
            return ToolResult.fail(NAME, "Failed to update provider: " + e.getMessage());
        }
    }
}
