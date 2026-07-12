package com.gsim.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.agent.config.AgentConfigStore;
import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态创建 SubAgent 配置。
 *
 * <p>OrchestratorAgent 可在运行时创建新的 Agent 配置，写入 agents/ 目录，
 * 随后即可通过 dispatch 工具以新的 agentId 派发子代理。
 *
 * <p>创建的配置不包含 userTemplate — 主 Agent dispatch 时提供 user prompt。
 */
public class CreateSubAgentConfigTool implements AgentTool {

    public static final String NAME = "create_sub_agent_config";

    private static final Logger log = LoggerFactory.getLogger(CreateSubAgentConfigTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> BUILTIN_AGENTS = Set.of("orchestrator", "sim", "search");
    private static final Set<String> VALID_TOOL_FILTERS = Set.of("all", "read_only", "custom");

    private final Path agentsDir;
    private final AgentConfigStore configStore;

    public CreateSubAgentConfigTool(Path agentsDir, AgentConfigStore configStore) {
        this.agentsDir = agentsDir;
        this.configStore = configStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在 agents/ 目录创建一个新的 SubAgent 配置。"
                + "创建后该 agent 即可通过 dispatch 工具派发。"
                + "不需要提供 userTemplate — 主 Agent dispatch 时会自带用户指令。"
                + "先调用 list_llm_providers 查看可用的 LLM provider。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "agent_id", Map.of(
                                "type", "string",
                                "description", "新 Agent 的唯一 ID（如 \"analyst\"、\"critic\"）。"
                                        + "不能与已有 agent 重名，内置 agent (orchestrator/sim/search) 不可覆盖。"),
                        "llm_provider", Map.of(
                                "type", "string",
                                "description", "LLM provider ID（对应 llms.json 中的 id）。"
                                        + "默认填 \"base\"，除非想用其他 provider。"),
                        "system_prompt", Map.of(
                                "type", "string",
                                "description", "完整的 System Prompt 文本。定义该 SubAgent 的角色、能力边界、"
                                        + "输出格式、可用工具等。这是 Agent 的核心指令。"),
                        "tool_filter", Map.of(
                                "type", "string",
                                "description", "工具访问模式: \"read_only\"（只读工具）, \"all\"（所有工具）, "
                                        + "\"custom\"（自定义列表）。默认 \"read_only\"。"),
                        "temperature", Map.of(
                                "type", "number",
                                "description", "LLM 温度 (0.0-2.0)。默认 0.3。"),
                        "max_tokens", Map.of(
                                "type", "integer",
                                "description", "最大输出 token 数。默认 2048。"),
                        "max_tool_rounds", Map.of(
                                "type", "integer",
                                "description", "最大工具调用轮数。默认 16。")),
                List.of("agent_id", "llm_provider", "system_prompt"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String agentId = call.param("agent_id", "").trim();
        String llmProvider = call.param("llm_provider", "base").trim();
        String systemPrompt = call.param("system_prompt", "").trim();
        String toolFilter = call.param("tool_filter", "read_only").trim();
        double temperature = parseDouble(call.param("temperature", "0.3"), 0.3);
        int maxTokens = parseInt(call.param("max_tokens", "2048"), 2048);
        int maxToolRounds = parseInt(call.param("max_tool_rounds", "16"), 16);

        // 参数校验
        if (agentId.isEmpty()) {
            return ToolResult.fail(NAME, "agent_id 不能为空");
        }
        if (!agentId.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            return ToolResult.fail(NAME,
                    "agent_id 格式无效: 必须以字母开头，仅含字母、数字、下划线、连字符");
        }
        if (BUILTIN_AGENTS.contains(agentId)) {
            return ToolResult.fail(NAME,
                    "不能覆盖内置 agent '" + agentId + "'。内置 agent: "
                            + String.join(", ", BUILTIN_AGENTS));
        }
        if (llmProvider.isEmpty()) {
            return ToolResult.fail(NAME, "llm_provider 不能为空");
        }
        if (systemPrompt.isEmpty()) {
            return ToolResult.fail(NAME, "system_prompt 不能为空");
        }
        if (!VALID_TOOL_FILTERS.contains(toolFilter)) {
            return ToolResult.fail(NAME,
                    "tool_filter 无效: '" + toolFilter + "'。可选: all, read_only, custom");
        }

        try {
            Files.createDirectories(agentsDir);

            Path configFile = agentsDir.resolve(agentId + ".json");
            if (Files.exists(configFile)) {
                return ToolResult.fail(NAME,
                        "Agent '" + agentId + "' 已存在 (" + configFile.getFileName() + ")。"
                                + "如需覆盖请先手动删除该文件或换一个 agent_id。");
            }

            // 构建 JSON
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("agentId", agentId);
            config.put("llmProvider", llmProvider);
            config.put("staticSystemPrompt", systemPrompt);
            config.put("toolFilter", Map.of("mode", toolFilter));
            config.put("maxToolRounds", maxToolRounds);
            config.put("temperature", temperature);
            config.put("maxTokens", maxTokens);
            // 不包含 systemPromptTemplate, systemPrompt, userTemplate

            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configFile, json);

            log.info("[CreateSubAgentConfigTool] wrote config: {}", configFile);

            // 立即 reload 让 AgentFactory 可发现新 agent
            configStore.reload(agentsDir);

            String summary = String.format(
                    """
                            ✅ SubAgent 配置已创建

                            - **Agent ID**: `%s`
                            - **LLM Provider**: `%s`
                            - **工具模式**: `%s`
                            - **温度**: %.1f
                            - **最大 Token**: %d
                            - **最大工具轮数**: %d

                            现在可以通过 dispatch 工具以 `type="%s"` 派发该 SubAgent。
                            """,
                    agentId, llmProvider, toolFilter,
                    temperature, maxTokens, maxToolRounds, agentId);

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(agentId, configFile.getFileName().toString(),
                            summary, 1.0)));
        } catch (Exception e) {
            log.error("[CreateSubAgentConfigTool] failed: {}", e.getMessage(), e);
            return ToolResult.fail(NAME, "创建配置失败: " + e.getMessage());
        }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return def; }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return def; }
    }
}
