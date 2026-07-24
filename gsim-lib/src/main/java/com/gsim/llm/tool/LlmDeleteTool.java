package com.gsim.llm.tool;

import com.gsim.llm.LlmConfigManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * gsim_llm_delete -- 删除指定的 LLM Provider 配置。
 *
 * <p>从 llms.json 中移除。不允许删除最后一个 provider。
 * 若删除的是默认 provider，自动将剩余第一个设为默认。
 */
public class LlmDeleteTool implements AgentTool {

    public static final String NAME = "llm_delete";

    private final LlmConfigManager llmConfigManager;

    public LlmDeleteTool(LlmConfigManager llmConfigManager) {
        this.llmConfigManager = llmConfigManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "删除指定的 LLM Provider 配置。不允许删除最后一个 provider。" + "若删除的是默认 provider，自动将剩余第一个设为默认。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return com.gsim.llm.ToolDef.strictSchema(
                Map.of(
                        "id",
                        Map.of(
                                "type", "string",
                                "description", "要删除的 Provider ID")),
                List.of("id"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String id = call.param("id");
        if (id == null || id.isBlank()) {
            return ToolResult.fail(NAME, "id is required");
        }

        try {
            LlmConfigManager.UpdateResult result = llmConfigManager.removeProvider(id);

            if (result.success()) {
                return ToolResult.ok(NAME, List.of(new ToolResult.Item(id, NAME, result.message(), 1.0)));
            } else {
                return ToolResult.fail(NAME, result.message());
            }
        } catch (Exception e) {
            return ToolResult.fail(NAME, "Failed to delete provider: " + e.getMessage());
        }
    }

    @Override
    public Permission permission() {
        return Permission.SYSTEM;
    }
}
