package com.gsim.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.campaign.PlayerAction;
import com.gsim.llm.LlmClient;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResponse;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrator Agent — 主协调者。
 * 接收玩家行动和主持人指令，驱动 LLM 推演，支持 ToolLoop。
 *
 * ToolLoop 流程：
 * 1. 构建 system prompt（含可用工具说明）+ user prompt（玩家行动）
 * 2. 发送 LLM
 * 3. 解析响应：普通文本 → 最终结果；JSON tool call → 调用工具 → 追加结果 → 回到步骤 2
 * 4. 最多 5 轮 tool 调用，超限后要求 LLM 直接总结
 */
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);
    private static final int MAX_TOOL_ROUNDS = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final String model;

    public OrchestratorAgent(LlmClient llmClient, ToolRegistry toolRegistry, String model) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.model = model;
    }

    /**
     * 执行推演运行。
     *
     * @param playerActions 当前回合的玩家行动
     * @param instruction   主持人指令（可选，来自 /run 命令行）
     * @param turnInfo      回合信息（campaign/turn ID）
     * @return Run 结果，包含最终文本和 tool 调用记录
     */
    public RunResult run(List<PlayerAction> playerActions, String instruction, String turnInfo) {
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        List<LlmMessage> messages = new ArrayList<>();

        // 1. 构建 system prompt
        messages.add(LlmMessage.system(buildSystemPrompt()));

        // 2. 构建 user prompt
        messages.add(LlmMessage.user(buildUserPrompt(playerActions, instruction, turnInfo)));

        String finalText = null;
        int toolRound = 0;

        // 3. ToolLoop
        while (toolRound < MAX_TOOL_ROUNDS) {
            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048);
            LlmResponse response = llmClient.chat(request);

            if (!response.success()) {
                log.error("LLM call failed: {}", response.errorMessage());
                return new RunResult("LLM 调用失败: " + response.errorMessage(), toolCalls);
            }

            String content = response.content();
            messages.add(LlmMessage.assistant(content));

            // 4. 尝试解析为 tool call
            ParsedToolCall parsed = tryParseToolCall(content);
            if (parsed != null) {
                log.info("Tool call detected: {} with args: {}", parsed.tool, parsed.args);

                ToolCall call = new ToolCall(parsed.tool, parsed.args);
                ToolResult result = toolRegistry.call(call);

                toolCalls.add(new ToolCallRecord(parsed.tool, parsed.args, result));

                // 将 tool 结果追加到上下文
                String toolResultText = formatToolResult(result);
                messages.add(LlmMessage.user(toolResultText));

                toolRound++;
                continue;
            }

            // 5. 普通文本 → 最终结果
            finalText = content;
            break;
        }

        // 6. 超过最大轮数，要求总结
        if (finalText == null) {
            log.warn("Max tool rounds ({}) reached, forcing summary", MAX_TOOL_ROUNDS);
            messages.add(LlmMessage.user(
                    "你已经完成了多轮工具调用。请基于以上所有查询结果，" +
                    "写一段完整的推演总结。必须引用信息来源的路径（如 import/web/prts.wiki/...）。" +
                    "不要调用更多工具，直接输出推演结果。"));
            LlmRequest finalRequest = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048);
            LlmResponse finalResponse = llmClient.chat(finalRequest);
            if (finalResponse.success()) {
                finalText = finalResponse.content();
                messages.add(LlmMessage.assistant(finalText));
            } else {
                finalText = "达到最大工具调用轮数后，LLM 总结也失败了: " + finalResponse.errorMessage();
            }
        }

        return new RunResult(finalText, toolCalls);
    }

    // ---- prompt 构建 ----

    private String buildSystemPrompt() {
        return """
                你是一个架空历史推演助手，负责分析玩家行动并产出推演结果。

                ## 可用工具

                你可以使用以下工具来获取额外信息。调用工具时，输出 JSON：

                ```json
                {"tool":"wiki_search","args":{"query":"搜索关键词","limit":5}}
                ```

                - wiki_search: 搜索本地 PRTS Wiki 文本文件，返回页面标题、文件路径、内容片段。
                  args: query (必填，搜索关键词), limit (可选，默认 5，最大 10)

                ## 规则

                1. 如果你需要查询 Wiki 知识库，输出上述 JSON 格式的工具调用。
                2. 不要输出其他文本，只输出 JSON 工具调用。
                3. 如果你已经有足够信息做出推演，直接输出推演结果文本。
                4. 推演结果必须：
                   - 分析每位玩家的行动及其可能影响
                   - 引用 Wiki 查询结果的来源路径（如 import/web/prts.wiki/xxx.txt）
                   - 区分 facts（已知事实）、inferences（推断）、hypotheses（假设）
                   - 使用 Markdown 格式，对关键实体使用 **粗体**
                5. 如果玩家行动涉及特定人物、势力、事件，优先查询 Wiki。
                """;
    }

    private String buildUserPrompt(List<PlayerAction> playerActions, String instruction, String turnInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 回合信息\n");
        sb.append(turnInfo).append("\n\n");

        if (instruction != null && !instruction.isBlank()) {
            sb.append("## 主持人指令\n");
            sb.append(instruction).append("\n\n");
        }

        sb.append("## 玩家行动\n");
        if (playerActions.isEmpty()) {
            sb.append("(本回合无玩家行动)\n");
        } else {
            for (int i = 0; i < playerActions.size(); i++) {
                PlayerAction a = playerActions.get(i);
                sb.append(i + 1).append(". **").append(a.playerName()).append("**: ")
                        .append(a.content()).append("\n");
            }
        }

        sb.append("\n请分析玩家行动。如果需要查询 Wiki，请输出 JSON 工具调用。");
        sb.append("如果已有足够信息，请直接输出推演结果。");
        return sb.toString();
    }

    // ---- tool call 解析 ----

    /**
     * 尝试从 LLM 响应中解析 tool call JSON。
     * 支持格式：
     * - 纯 JSON: {"tool":"...","args":{...}}
     * - code-fenced: ```json\n{"tool":...}\n```
     */
    static ParsedToolCall tryParseToolCall(String text) {
        if (text == null || text.isBlank()) return null;

        String trimmed = text.trim();

        // 去除 markdown code fences
        if (trimmed.startsWith("```")) {
            int end = trimmed.indexOf("\n");
            if (end < 0) return null;
            String afterFence = trimmed.substring(end + 1).trim();
            if (afterFence.endsWith("```")) {
                afterFence = afterFence.substring(0, afterFence.length() - 3).trim();
            }
            trimmed = afterFence;
        }

        if (!trimmed.startsWith("{")) return null;

        try {
            JsonNode root = MAPPER.readTree(trimmed);
            if (!root.has("tool")) return null;

            String tool = root.get("tool").asText();
            if (tool == null || tool.isBlank()) return null;

            JsonNode argsNode = root.get("args");
            Map<String, String> args = new java.util.HashMap<>();
            if (argsNode != null && argsNode.isObject()) {
                var iter = argsNode.fields();
                while (iter.hasNext()) {
                    var entry = iter.next();
                    args.put(entry.getKey(), entry.getValue().asText());
                }
            }

            return new ParsedToolCall(tool, args);
        } catch (Exception e) {
            log.debug("Failed to parse tool call from: {}...", trimmed.substring(0, Math.min(80, trimmed.length())));
            return null;
        }
    }

    // ---- tool result 格式化 ----

    private String formatToolResult(ToolResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具调用结果: ").append(result.toolName()).append("\n");
        if (!result.success()) {
            sb.append("错误: ").append(result.error()).append("\n");
            return sb.toString();
        }
        sb.append("找到 ").append(result.items().size()).append(" 条结果：\n\n");
        for (int i = 0; i < result.items().size(); i++) {
            ToolResult.Item item = result.items().get(i);
            sb.append("[").append(i + 1).append("] ").append(item.title()).append("\n");
            sb.append("    路径: ").append(item.path()).append("\n");
            sb.append("    片段: ").append(item.snippet()).append("\n");
        }
        return sb.toString();
    }

    // ---- result types ----

    /**
     * 解析出的工具调用。
     */
    record ParsedToolCall(String tool, Map<String, String> args) {}

    /**
     * 一次工具调用记录（含结果）。
     */
    public record ToolCallRecord(String tool, Map<String, String> args, ToolResult result) {}

    /**
     * Run 的完整结果。
     */
    public record RunResult(String finalText, List<ToolCallRecord> toolCalls) {}
}
