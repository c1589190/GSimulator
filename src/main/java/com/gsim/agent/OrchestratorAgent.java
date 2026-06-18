package com.gsim.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.campaign.PlayerAction;
import com.gsim.chat.ToolPollutionFilter;
import com.gsim.context.session.SessionMessage;
import com.gsim.llm.LlmClient;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResponse;
import com.gsim.resource.PromptResourceManager;
import com.gsim.resource.ResourceManager;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
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
        try {
            return PromptResourceManager.getOrchestratorSystemPrompt();
        } catch (IOException e) {
            throw new UncheckedIOException("Orchestrator system prompt not found on classpath", e);
        }
    }

    /**
     * 构建完整 system prompt：orchestrator-system.md + ToolRegistry 工具目录 + BaseContext。
     * 用于 ContextSession 和 RenderedContext 路径。
     */
    private String buildFullSystemPrompt(String contextMarkdown) {
        StringBuilder sb = new StringBuilder();
        // 1. orchestrator-system.md（身份、工具调用规则、详细工具说明）
        sb.append(buildSystemPrompt());
        sb.append("\n\n---\n\n");
        // 2. ToolRegistry 生成的工具目录（确保与已注册工具一致）
        sb.append(generateToolCatalog());
        sb.append("\n\n---\n\n");
        // 3. BaseContextSnapshot 或渲染上下文
        sb.append(contextMarkdown);
        return sb.toString();
    }

    /**
     * 从 ToolRegistry 生成当前可用工具目录。
     * 包含所有已注册工具的 name 和 description。
     */
    private String generateToolCatalog() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 已注册工具 (Registered Tools)\n\n");
        for (var entry : toolRegistry.all().entrySet()) {
            sb.append("- **").append(entry.getKey()).append("**: ")
                    .append(entry.getValue().description()).append("\n");
        }
        sb.append("\n调用格式：{\"tool\":\"<工具名>\",\"args\":{\"<参数名>\":\"<参数值>\"}}。");
        sb.append("工具执行后系统会返回结果，你再自然语言总结。\n");
        return sb.toString();
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
            log.warn("Failed to parse tool call from: {}...", trimmed.substring(0, Math.min(80, trimmed.length())));
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

    /**
     * 消息追踪记录。
     */
    public record MessageTrace(String role, String type, String content) {}

    /**
     * 基于已渲染的上下文执行推演。
     * @param contextMarkdown BranchContextRenderer 输出的完整 markdown
     * @param simNote 本轮 /sim 的推演备注
     */
    public SimResult runWithRenderedContext(String contextMarkdown, String simNote) {
        List<MessageTrace> trace = new ArrayList<>();
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        List<LlmMessage> messages = new ArrayList<>();

        messages.add(LlmMessage.system(buildFullSystemPrompt(contextMarkdown)));

        String userMsg = "请基于以上上下文进行推演。";
        if (simNote != null && !simNote.isBlank()) {
            userMsg += "\n\n推演备注: " + simNote;
        }
        messages.add(LlmMessage.user(userMsg));
        trace.add(new MessageTrace("user", "sim_input", userMsg));

        String finalText = null;
        int toolRound = 0;

        while (toolRound < MAX_TOOL_ROUNDS) {
            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048);
            LlmResponse response = llmClient.chat(request);

            if (!response.success()) {
                String err = "LLM 调用失败: " + response.errorMessage();
                trace.add(new MessageTrace("system", "error", err));
                return new SimResult(err, toolCalls, trace);
            }

            String content = response.content();
            messages.add(LlmMessage.assistant(content));

            ParsedToolCall parsed = tryParseToolCall(content);
            if (parsed != null) {
                trace.add(new MessageTrace("tool", "tool_call", parsed.tool + " " + parsed.args));
                ToolCall call = new ToolCall(parsed.tool, parsed.args);
                ToolResult result = toolRegistry.call(call);
                toolCalls.add(new ToolCallRecord(parsed.tool, parsed.args, result));

                StringBuilder toolResultStr = new StringBuilder();
                toolResultStr.append("工具 ").append(parsed.tool).append(" 返回:\n");
                if (result.success()) {
                    for (ToolResult.Item item : result.items()) {
                        toolResultStr.append("- ").append(item.title()).append(" (").append(item.path()).append(")\n");
                        // 只保留前 200 字符的片段摘要，防止完整内容污染上下文
                        String snippet = item.snippet();
                        if (snippet != null && snippet.length() > 200) {
                            snippet = snippet.substring(0, 200) + "...";
                        }
                        toolResultStr.append("  ").append(snippet).append("\n");
                    }
                } else {
                    toolResultStr.append("错误: ").append(result.error()).append("\n");
                }
                trace.add(new MessageTrace("tool", "tool_result", toolResultStr.toString()));
                messages.add(LlmMessage.user(toolResultStr.toString()));
                toolRound++;
                continue;
            }

            finalText = content;
            // 过滤：如果 assistant 输出疑似工具定义污染，记录摘要而非全文
            if (ToolPollutionFilter.isPolluted(finalText)) {
                trace.add(new MessageTrace("assistant", "sim_output",
                        "[filtered — assistant output contained tool definition pollution, length="
                                + finalText.length() + "]"));
            } else {
                trace.add(new MessageTrace("assistant", "sim_output", finalText));
            }
            break;
        }

        if (finalText == null) {
            messages.add(LlmMessage.user("已进行多轮工具调用。请基于以上所有结果写一段推演总结。不要调用更多工具。"));
            LlmResponse finalResp = llmClient.chat(new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048));
            if (finalResp.success()) {
                finalText = finalResp.content();
                trace.add(new MessageTrace("assistant", "sim_output", finalText));
            } else {
                finalText = "推演总结生成失败: " + finalResp.errorMessage();
            }
        }

        return new SimResult(finalText, toolCalls, trace);
    }

    public record SimResult(String finalText, List<ToolCallRecord> toolCalls, List<MessageTrace> trace) {}

    // ---- chat mode ----

    /** 对话模式：不覆盖任何章节，只返回 LLM 回复。 */
    public ChatResult chatWithRenderedContext(String contextMarkdown, String userText) {
        List<MessageTrace> trace = new ArrayList<>();
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        List<LlmMessage> messages = new ArrayList<>();

        messages.add(LlmMessage.system(buildFullSystemPrompt(contextMarkdown)));
        messages.add(LlmMessage.user(userText));
        trace.add(new MessageTrace("user", "chat_user", userText));

        String finalText = null;
        int toolRound = 0;

        while (toolRound < MAX_TOOL_ROUNDS) {
            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048);
            LlmResponse response = llmClient.chat(request);

            if (!response.success()) {
                return new ChatResult(false, "", toolCalls, trace, response.errorMessage());
            }

            String content = response.content();
            messages.add(LlmMessage.assistant(content));

            ParsedToolCall parsed = tryParseToolCall(content);
            if (parsed != null) {
                trace.add(new MessageTrace("tool", "tool_call", parsed.tool + " " + parsed.args));
                ToolCall call = new ToolCall(parsed.tool, parsed.args);
                ToolResult result = toolRegistry.call(call);
                toolCalls.add(new ToolCallRecord(parsed.tool, parsed.args, result));

                StringBuilder tr = new StringBuilder();
                tr.append("工具 ").append(parsed.tool).append(" 返回:\n");
                if (result.success()) {
                    for (ToolResult.Item item : result.items()) {
                        tr.append("- ").append(item.title()).append(" (").append(item.path()).append(")\n");
                        // 只保留前 200 字符的片段摘要
                        String snippet = item.snippet();
                        if (snippet != null && snippet.length() > 200) {
                            snippet = snippet.substring(0, 200) + "...";
                        }
                        tr.append("  ").append(snippet).append("\n");
                    }
                } else tr.append("错误: ").append(result.error());
                trace.add(new MessageTrace("tool", "tool_result", tr.toString()));
                messages.add(LlmMessage.user(tr.toString()));
                toolRound++;
                continue;
            }

            finalText = content;
            // 过滤：如果 assistant 输出疑似工具定义污染，记录摘要而非全文
            if (ToolPollutionFilter.isPolluted(finalText)) {
                trace.add(new MessageTrace("assistant", "chat_response",
                        "[filtered — assistant output contained tool definition pollution, length="
                                + finalText.length() + "]"));
            } else {
                trace.add(new MessageTrace("assistant", "chat_response", finalText));
            }
            break;
        }

        if (finalText == null) {
            messages.add(LlmMessage.user("请基于以上信息给出回答，不要调用更多工具。"));
            LlmResponse fr = llmClient.chat(new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048));
            if (fr.success()) { finalText = fr.content(); trace.add(new MessageTrace("assistant", "chat_response", finalText)); }
            else return new ChatResult(false, "", toolCalls, trace, fr.errorMessage());
        }

        return new ChatResult(true, finalText, toolCalls, trace, null);
    }

    public record ChatResult(boolean success, String finalText, List<ToolCallRecord> toolCalls,
                              List<MessageTrace> trace, String errorMessage) {}

    // ====== ContextSession 模式（新路径） ======

    /**
     * 基于 ContextSession 的对话模式。
     * LLM messages = system(orchestrator-system.md + ToolCatalog + BaseContext) + sessionMessages + user(input)
     */
    public ChatResult chatWithContextSession(String baseContextMarkdown,
                                              List<SessionMessage> sessionMessages,
                                              String userText) {
        List<MessageTrace> trace = new ArrayList<>();
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        List<LlmMessage> messages = new ArrayList<>();

        // 1. system: orchestrator-system.md + ToolCatalog + BaseContextSnapshot
        messages.add(LlmMessage.system(buildFullSystemPrompt(baseContextMarkdown)));

        // 2. history: 当前 ContextSession 内 SessionMessage
        for (SessionMessage sm : sessionMessages) {
            String content = sm.content();
            if (content.length() > 4000) content = content.substring(0, 3997) + "...";
            switch (sm.role()) {
                case "user" -> messages.add(LlmMessage.user(content));
                case "assistant" -> messages.add(LlmMessage.assistant(content));
                case "tool" -> messages.add(LlmMessage.user("[工具结果] " + content));
                default -> messages.add(LlmMessage.user(content));
            }
        }

        // 3. user: 当前输入
        messages.add(LlmMessage.user(userText));
        trace.add(new MessageTrace("user", "chat_user", userText));

        return runToolLoop(messages, trace, toolCalls, false);
    }

    /**
     * 基于 ContextSession 的推演模式。
     * LLM messages = system(orchestrator-system.md + ToolCatalog + BaseContext) + sessionMessages + user(sim prompt)
     */
    public SimResult runWithContextSession(String baseContextMarkdown,
                                            List<SessionMessage> sessionMessages,
                                            String simNote) {
        List<MessageTrace> trace = new ArrayList<>();
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        List<LlmMessage> messages = new ArrayList<>();

        // 1. system: orchestrator-system.md + ToolCatalog + BaseContextSnapshot
        messages.add(LlmMessage.system(buildFullSystemPrompt(baseContextMarkdown)));

        // 2. history: 当前 ContextSession 内 SessionMessage
        for (SessionMessage sm : sessionMessages) {
            String content = sm.content();
            if (content.length() > 4000) content = content.substring(0, 3997) + "...";
            switch (sm.role()) {
                case "user" -> messages.add(LlmMessage.user(content));
                case "assistant" -> messages.add(LlmMessage.assistant(content));
                case "tool" -> messages.add(LlmMessage.user("[工具结果] " + content));
                default -> messages.add(LlmMessage.user(content));
            }
        }

        // 3. user: 推演提示
        String userMsg = "请基于以上上下文进行推演。";
        if (simNote != null && !simNote.isBlank()) {
            userMsg += "\n\n推演备注: " + simNote;
        }
        messages.add(LlmMessage.user(userMsg));
        trace.add(new MessageTrace("user", "sim_input", userMsg));

        SimResult result = runSimToolLoop(messages, trace, toolCalls);
        return result;
    }

    /** 公共 tool-loop（chat 模式）。 */
    private ChatResult runToolLoop(List<LlmMessage> messages,
                                    List<MessageTrace> trace,
                                    List<ToolCallRecord> toolCalls,
                                    boolean isSim) {
        String finalText = null;
        int toolRound = 0;

        while (toolRound < MAX_TOOL_ROUNDS) {
            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048);
            LlmResponse response = llmClient.chat(request);

            if (!response.success()) {
                return new ChatResult(false, "", toolCalls, trace, response.errorMessage());
            }

            String content = response.content();
            messages.add(LlmMessage.assistant(content));

            ParsedToolCall parsed = tryParseToolCall(content);
            if (parsed != null) {
                trace.add(new MessageTrace("tool", "tool_call", parsed.tool + " " + parsed.args));
                ToolCall call = new ToolCall(parsed.tool, parsed.args);
                ToolResult result = toolRegistry.call(call);
                toolCalls.add(new ToolCallRecord(parsed.tool, parsed.args, result));

                StringBuilder tr = new StringBuilder();
                tr.append("工具 ").append(parsed.tool).append(" 返回:\n");
                if (result.success()) {
                    for (ToolResult.Item item : result.items()) {
                        tr.append("- ").append(item.title()).append(" (").append(item.path()).append(")\n");
                        String snippet = item.snippet();
                        if (snippet != null && snippet.length() > 200) {
                            snippet = snippet.substring(0, 200) + "...";
                        }
                        tr.append("  ").append(snippet).append("\n");
                    }
                } else tr.append("错误: ").append(result.error());
                trace.add(new MessageTrace("tool", "tool_result", tr.toString()));
                messages.add(LlmMessage.user(tr.toString()));
                toolRound++;
                continue;
            }

            finalText = content;
            if (ToolPollutionFilter.isPolluted(finalText)) {
                trace.add(new MessageTrace("assistant", "chat_response",
                        "[filtered, length=" + finalText.length() + "]"));
            } else {
                trace.add(new MessageTrace("assistant", "chat_response", finalText));
            }
            break;
        }

        if (finalText == null) {
            messages.add(LlmMessage.user("请基于以上信息给出回答，不要调用更多工具。"));
            LlmResponse fr = llmClient.chat(new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048));
            if (fr.success()) {
                finalText = fr.content();
                trace.add(new MessageTrace("assistant", "chat_response", finalText));
            } else {
                return new ChatResult(false, "", toolCalls, trace, fr.errorMessage());
            }
        }

        return new ChatResult(true, finalText, toolCalls, trace, null);
    }

    /** 公共 tool-loop（sim 模式）。 */
    private SimResult runSimToolLoop(List<LlmMessage> messages,
                                      List<MessageTrace> trace,
                                      List<ToolCallRecord> toolCalls) {
        String finalText = null;
        int toolRound = 0;

        while (toolRound < MAX_TOOL_ROUNDS) {
            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048);
            LlmResponse response = llmClient.chat(request);

            if (!response.success()) {
                String err = "LLM 调用失败: " + response.errorMessage();
                trace.add(new MessageTrace("system", "error", err));
                return new SimResult(err, toolCalls, trace);
            }

            String content = response.content();
            messages.add(LlmMessage.assistant(content));

            ParsedToolCall parsed = tryParseToolCall(content);
            if (parsed != null) {
                trace.add(new MessageTrace("tool", "tool_call", parsed.tool + " " + parsed.args));
                ToolCall call = new ToolCall(parsed.tool, parsed.args);
                ToolResult result = toolRegistry.call(call);
                toolCalls.add(new ToolCallRecord(parsed.tool, parsed.args, result));

                StringBuilder tr = new StringBuilder();
                tr.append("工具 ").append(parsed.tool).append(" 返回:\n");
                if (result.success()) {
                    for (ToolResult.Item item : result.items()) {
                        tr.append("- ").append(item.title()).append(" (").append(item.path()).append(")\n");
                        String snippet = item.snippet();
                        if (snippet != null && snippet.length() > 200) {
                            snippet = snippet.substring(0, 200) + "...";
                        }
                        tr.append("  ").append(snippet).append("\n");
                    }
                } else tr.append("错误: ").append(result.error());
                trace.add(new MessageTrace("tool", "tool_result", tr.toString()));
                messages.add(LlmMessage.user(tr.toString()));
                toolRound++;
                continue;
            }

            finalText = content;
            if (ToolPollutionFilter.isPolluted(finalText)) {
                trace.add(new MessageTrace("assistant", "sim_output",
                        "[filtered, length=" + finalText.length() + "]"));
            } else {
                trace.add(new MessageTrace("assistant", "sim_output", finalText));
            }
            break;
        }

        if (finalText == null) {
            messages.add(LlmMessage.user("已进行多轮工具调用。请基于以上所有结果写一段推演总结。不要调用更多工具。"));
            LlmResponse fr = llmClient.chat(new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048));
            if (fr.success()) {
                finalText = fr.content();
                trace.add(new MessageTrace("assistant", "sim_output", finalText));
            } else {
                finalText = "推演总结生成失败: " + fr.errorMessage();
            }
        }

        return new SimResult(finalText, toolCalls, trace);
    }
}
