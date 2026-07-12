package com.gsim.agent.tool;

import com.gsim.llm.LlmProvider;
import com.gsim.llm.LlmProviderRegistry;
import com.gsim.llm.ProviderConfig;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 列出 llms.json 中所有可用的 LLM Provider。
 * 供 Orchestrator 在创建 SubAgent 前了解可选的 LLM 供应。
 */
public class ListLlmProvidersTool implements AgentTool {

    public static final String NAME = "list_llm_providers";

    private final LlmProviderRegistry registry;

    public ListLlmProvidersTool(LlmProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "列出 llms.json 中所有可用的 LLM Provider（ID、名称、模型、Base URL 等）。"
                + "在创建 SubAgent 配置前先调用此工具了解可选 provider。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return com.gsim.llm.ToolDef.strictSchema(
                Map.of(),
                List.of());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            Map<String, LlmProvider> providers = registry.all();
            if (providers.isEmpty()) {
                return ToolResult.ok(NAME, List.of(
                        new ToolResult.Item("info", NAME,
                                "暂无可用 LLM Provider。请检查 llms.json。", 1.0)));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 可用 LLM Provider\n\n");
            sb.append("| ID | Name | Model | Base URL | Temp | Reasoning | 默认 |\n");
            sb.append("|----|------|-------|----------|------|-----------|------|\n");

            String defaultId = registry.getDefaultId();
            for (var entry : providers.entrySet()) {
                String id = entry.getKey();
                ProviderConfig c = entry.getValue().config();
                boolean isDef = id.equals(defaultId);
                sb.append(String.format("| `%s` | %s | `%s` | %s | %.1f | %s | %s |\n",
                        id,
                        c.name(),
                        c.model(),
                        c.baseUrl(),
                        c.temperature(),
                        c.hasNativeReasoning() ? "是" : "否",
                        isDef ? "✅" : ""));
            }

            sb.append("\n");
            sb.append("默认 provider: `").append(defaultId).append("`");
            sb.append("\n\n");
            sb.append("创建 SubAgent 时，`llm_provider` 参数填写 ID 列的值。");

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("providers", NAME, sb.toString(), 1.0)));
        } catch (Exception e) {
            return ToolResult.fail(NAME, "获取 LLM Provider 列表失败: " + e.getMessage());
        }
    }
}
