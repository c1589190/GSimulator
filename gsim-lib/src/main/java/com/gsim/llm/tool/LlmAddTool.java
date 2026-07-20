package com.gsim.llm.tool;

import com.gsim.llm.LlmConfigManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * gsim_llm_add -- 添加新的 LLM Provider 配置。
 *
 * <p>写入 llms.json，自动设为默认（若当前无任何 provider）。
 * 创建后可通过 {@link LlmTestTool} 测试连通性。
 */
public class LlmAddTool implements AgentTool {

    public static final String NAME = "gsim_llm_add";

    private final LlmConfigManager llmConfigManager;

    public LlmAddTool(LlmConfigManager llmConfigManager) {
        this.llmConfigManager = llmConfigManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "添加新的 LLM Provider 配置。需要提供 id、baseUrl、model，可选 name 和 apiKey。" + "若当前无任何 provider，添加的第一个会自动设为默认。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return com.gsim.llm.ToolDef.strictSchema(
                Map.of(
                        "id",
                                Map.of(
                                        "type", "string",
                                        "description", "Provider 唯一标识（如 'deepseek'、'openai'）"),
                        "name",
                                Map.of(
                                        "type", "string",
                                        "description", "显示名称（可选，默认取 id 值）"),
                        "baseUrl",
                                Map.of(
                                        "type", "string",
                                        "description", "API 基础 URL（如 'https://api.deepseek.com/v1'）"),
                        "model",
                                Map.of(
                                        "type", "string",
                                        "description", "模型名称（如 'deepseek-v4-pro'）"),
                        "apiKey",
                                Map.of(
                                        "type", "string",
                                        "description", "API 密钥（可选，可在添加后通过 gsim_llm_update 设置）")),
                List.of("id", "baseUrl", "model"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String id = call.param("id");
        String name = call.param("name");
        String baseUrl = call.param("baseUrl");
        String model = call.param("model");
        String apiKey = call.param("apiKey");

        if (id == null || id.isBlank()) {
            return ToolResult.fail(NAME, "id is required");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return ToolResult.fail(NAME, "baseUrl is required");
        }
        if (model == null || model.isBlank()) {
            return ToolResult.fail(NAME, "model is required");
        }

        try {
            LlmConfigManager.UpdateResult result = llmConfigManager.addProvider(id, name, baseUrl, apiKey, model);

            if (result.success()) {
                return ToolResult.ok(NAME, List.of(new ToolResult.Item(id, NAME, result.message(), 1.0)));
            } else {
                return ToolResult.fail(NAME, result.message());
            }
        } catch (Exception e) {
            return ToolResult.fail(NAME, "Failed to add provider: " + e.getMessage());
        }
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
