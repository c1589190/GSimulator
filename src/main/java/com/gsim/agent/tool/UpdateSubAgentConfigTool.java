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
 * 更新已有 SubAgent 配置。
 *
 * <p>OrchestratorAgent 可在运行时修改已有 Agent 的 system prompt、temperature、
 * maxTokens 等字段，写入 agents/ 目录。修改后在下次 dispatch 时生效。
 *
 * <p>仅修改调用中显式提供的字段，未提供的字段保持原值不变。
 */
public class UpdateSubAgentConfigTool implements AgentTool {

    public static final String NAME = "update_sub_agent_config";

    private static final Logger log = LoggerFactory.getLogger(UpdateSubAgentConfigTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_TOOL_FILTERS = Set.of("all", "read_only", "custom");

    private final Path agentsDir;
    private final AgentConfigStore configStore;

    public UpdateSubAgentConfigTool(Path agentsDir, AgentConfigStore configStore) {
        this.agentsDir = agentsDir;
        this.configStore = configStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "更新一个已有 SubAgent 的配置。仅修改调用中显式提供的字段，"
                + "未提供的字段保持原值不变。修改后在下次 dispatch 时生效。"
                + "可更新字段：system_prompt（核心指令文本）、temperature、max_tokens、"
                + "max_tool_rounds、tool_filter、llm_provider。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "agent_id", Map.of(
                                "type", "string",
                                "description", "要更新的 Agent ID（如 \"sim\"、\"search\"、或自定义 agent）。"),
                        "system_prompt", Map.of(
                                "type", "string",
                                "description", "（可选）新的 System Prompt 文本。定义该 Agent 的角色、能力边界、输出格式等。这是 Agent 的核心指令。不提供则不修改。"),
                        "tool_filter", Map.of(
                                "type", "string",
                                "description", "（可选）工具访问模式: \"read_only\"、\"all\"、\"custom\"。不提供则不修改。"),
                        "temperature", Map.of(
                                "type", "number",
                                "description", "（可选）LLM 温度 (0.0-2.0)。不提供则不修改。"),
                        "max_tokens", Map.of(
                                "type", "integer",
                                "description", "（可选）最大输出 token 数。不提供则不修改。"),
                        "max_tool_rounds", Map.of(
                                "type", "integer",
                                "description", "（可选）最大工具调用轮数。不提供则不修改。"),
                        "llm_provider", Map.of(
                                "type", "string",
                                "description", "（可选）LLM provider ID（对应 llms.json 中的 id）。不提供则不修改。")),
                List.of("agent_id"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String agentId = call.param("agent_id", "").trim();
        String systemPrompt = call.param("system_prompt", "").trim();
        String toolFilter = call.param("tool_filter", "").trim();
        String temperatureStr = call.param("temperature", "").trim();
        String maxTokensStr = call.param("max_tokens", "").trim();
        String maxToolRoundsStr = call.param("max_tool_rounds", "").trim();
        String llmProvider = call.param("llm_provider", "").trim();

        // 参数校验
        if (agentId.isEmpty()) {
            return ToolResult.fail(NAME, "agent_id 不能为空");
        }
        if (!agentId.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            return ToolResult.fail(NAME,
                    "agent_id 格式无效: 必须以字母开头，仅含字母、数字、下划线、连字符");
        }

        // 检查目标配置是否存在
        var existing = configStore.get(agentId);
        if (existing == null) {
            return ToolResult.fail(NAME,
                    "Agent '" + agentId + "' 不存在。"
                            + "如需创建新 Agent，请使用 create_sub_agent_config。"
                            + "已存在的 agent: " + configStore.agentIds());
        }

        Path configFile = agentsDir.resolve(agentId + ".json");
        if (!Files.exists(configFile)) {
            return ToolResult.fail(NAME,
                    "配置文件 " + configFile.getFileName()
                            + " 不存在于 " + agentsDir + "。目前只支持修改文件系统上的配置。");
        }

        // 校验可选参数
        if (!toolFilter.isEmpty() && !VALID_TOOL_FILTERS.contains(toolFilter)) {
            return ToolResult.fail(NAME,
                    "tool_filter 无效: '" + toolFilter + "'。可选: all, read_only, custom");
        }
        if (!temperatureStr.isEmpty()) {
            double t = parseDouble(temperatureStr, -1);
            if (t < 0 || t > 2.0) {
                return ToolResult.fail(NAME, "temperature 必须在 0.0-2.0 之间");
            }
        }
        if (!maxTokensStr.isEmpty()) {
            int mt = parseInt(maxTokensStr, -1);
            if (mt < 1) return ToolResult.fail(NAME, "max_tokens 必须 >= 1");
        }
        if (!maxToolRoundsStr.isEmpty()) {
            int mr = parseInt(maxToolRoundsStr, -1);
            if (mr < 1) return ToolResult.fail(NAME, "max_tool_rounds 必须 >= 1");
        }

        // 至少有一个字段被修改
        if (systemPrompt.isEmpty() && toolFilter.isEmpty() && temperatureStr.isEmpty()
                && maxTokensStr.isEmpty() && maxToolRoundsStr.isEmpty() && llmProvider.isEmpty()) {
            return ToolResult.fail(NAME,
                    "至少需要提供一个要更新的字段。可选: system_prompt, tool_filter, temperature, max_tokens, max_tool_rounds, llm_provider");
        }

        try {
            // 读取并解析现有 JSON
            String raw = Files.readString(configFile);
            @SuppressWarnings("unchecked")
            Map<String, Object> config = MAPPER.readValue(raw, Map.class);

            // 计算变更
            var changes = new LinkedHashMap<String, String>();

            if (!systemPrompt.isEmpty()) {
                String oldSp = config.containsKey("staticSystemPrompt")
                        ? String.valueOf(config.get("staticSystemPrompt"))
                        : (config.containsKey("systemPrompt")
                                ? String.valueOf(config.get("systemPrompt"))
                                : "(空)");
                config.put("staticSystemPrompt", systemPrompt);
                // 移除旧字段避免混淆
                config.remove("systemPrompt");
                changes.put("system_prompt", truncateForLog(oldSp) + " → " + truncateForLog(systemPrompt));
            }
            if (!llmProvider.isEmpty()) {
                String oldLp = String.valueOf(config.getOrDefault("llmProvider", "base"));
                config.put("llmProvider", llmProvider);
                changes.put("llm_provider", oldLp + " → " + llmProvider);
            }
            if (!toolFilter.isEmpty()) {
                String oldTf = String.valueOf(config.getOrDefault("toolFilter",
                        Map.of("mode", "read_only")));
                config.put("toolFilter", Map.of("mode", toolFilter));
                changes.put("tool_filter", oldTf + " → {\"mode\":\"" + toolFilter + "\"}");
            }
            if (!temperatureStr.isEmpty()) {
                double newTemp = Double.parseDouble(temperatureStr);
                double oldTemp = config.containsKey("temperature")
                        ? ((Number) config.get("temperature")).doubleValue() : 0.3;
                config.put("temperature", newTemp);
                changes.put("temperature", oldTemp + " → " + newTemp);
            }
            if (!maxTokensStr.isEmpty()) {
                int newMt = Integer.parseInt(maxTokensStr);
                int oldMt = config.containsKey("maxTokens")
                        ? ((Number) config.get("maxTokens")).intValue() : 2048;
                config.put("maxTokens", newMt);
                changes.put("max_tokens", oldMt + " → " + newMt);
            }
            if (!maxToolRoundsStr.isEmpty()) {
                int newMr = Integer.parseInt(maxToolRoundsStr);
                int oldMr = config.containsKey("maxToolRounds")
                        ? ((Number) config.get("maxToolRounds")).intValue() : 16;
                config.put("maxToolRounds", newMr);
                changes.put("max_tool_rounds", oldMr + " → " + newMr);
            }

            // 写回文件
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configFile, json);

            log.info("[UpdateSubAgentConfigTool] updated {}: {}", agentId, changes.keySet());

            // 立即 reload 让 AgentFactory 生效
            configStore.reload(agentsDir);

            StringBuilder summary = new StringBuilder();
            summary.append("✅ SubAgent 配置已更新: `").append(agentId).append("`\n\n");
            summary.append("| 字段 | 变更 |\n");
            summary.append("|------|------|\n");
            for (var entry : changes.entrySet()) {
                summary.append("| ").append(entry.getKey())
                        .append(" | ").append(entry.getValue()).append(" |\n");
            }
            summary.append("\n修改已在下次 dispatch 时生效。");

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item(agentId, configFile.getFileName().toString(),
                            summary.toString(), 1.0)));
        } catch (Exception e) {
            log.error("[UpdateSubAgentConfigTool] failed: {}", e.getMessage(), e);
            return ToolResult.fail(NAME, "更新配置失败: " + e.getMessage());
        }
    }

    private static String truncateForLog(String s) {
        if (s == null) return "(null)";
        if (s.length() <= 60) return s;
        return s.substring(0, 57) + "...";
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
