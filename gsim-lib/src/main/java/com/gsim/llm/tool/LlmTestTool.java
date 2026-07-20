package com.gsim.llm.tool;

import com.gsim.llm.LlmConfigManager;
import com.gsim.llm.LlmProviderRegistry;
import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * gsim_llm_test -- 测试指定 LLM Provider 的连通性。
 *
 * <p>优先使用注册表中已有的 provider 实例进行测试；
 * 若注册表中不存在，临时创建 LlmManager 实例。
 * 返回连接是否成功及详细信息。
 */
public class LlmTestTool implements AgentTool {

    public static final String NAME = "gsim_llm_test";

    private final LlmConfigManager llmConfigManager;
    private final LlmProviderRegistry llmProviderRegistry;

    public LlmTestTool(LlmConfigManager llmConfigManager, LlmProviderRegistry llmProviderRegistry) {
        this.llmConfigManager = llmConfigManager;
        this.llmProviderRegistry = llmProviderRegistry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "测试指定 LLM Provider 的连通性。" + "发送一个简短的测试请求，返回连接结果和详细信息。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return com.gsim.llm.ToolDef.strictSchema(
                Map.of(
                        "id",
                        Map.of(
                                "type", "string",
                                "description", "要测试的 Provider ID")),
                List.of("id"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String id = call.param("id");
        if (id == null || id.isBlank()) {
            return ToolResult.fail(NAME, "id is required");
        }

        try {
            String result = llmConfigManager.testProvider(id, llmProviderRegistry);

            boolean connected = result.startsWith("Connected OK");
            if (connected) {
                return ToolResult.ok(
                        NAME,
                        List.of(new ToolResult.Item(id, NAME, "Provider `" + id + "` 连通性测试通过。\n\n" + result, 1.0)));
            } else {
                return ToolResult.fail(NAME, "Provider `" + id + "` 连通性测试失败：\n\n" + result);
            }
        } catch (Exception e) {
            return ToolResult.fail(NAME, "Failed to test provider: " + e.getMessage());
        }
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
