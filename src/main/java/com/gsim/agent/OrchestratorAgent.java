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
        sb.append("工具执行后你会收到 [TOOL_RESULT]...[/TOOL_RESULT] 格式的反馈，");
        sb.append("这是内部数据不是最终输出，你必须基于它继续推理并用自然语言回答用户。");
        sb.append("不要把工具结果原文回显。\n");
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
     * 委托给 ToolCallExtractor 处理混合文本 + JSON 情况。
     */
    static ParsedToolCall tryParseToolCall(String text) {
        return ToolCallExtractor.extractFirstToolCall(text);
    }

    // ---- tool result 格式化 ----

    /**
     * 构建工具结果反馈文本，使用 [TOOL_RESULT] 包裹明确告知 LLM 这是内部数据。
     * 必须包含"继续完成用户请求"的指令，防止 LLM 把工具结果原样回显。
     */
    static String buildToolResultFeedback(String toolName, ToolResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TOOL_RESULT]\n");
        sb.append("tool: ").append(toolName).append("\n");
        sb.append("success: ").append(result.success()).append("\n");
        sb.append("content:\n");
        if (!result.success()) {
            sb.append("error: ").append(result.error()).append("\n");
        } else {
            for (int i = 0; i < result.items().size(); i++) {
                ToolResult.Item item = result.items().get(i);
                sb.append("[").append(i + 1).append("] ").append(item.title()).append("\n");
                sb.append("    path: ").append(item.path()).append("\n");
                String snippet = item.snippet();
                if (snippet != null) {
                    if (snippet.length() > 500) snippet = snippet.substring(0, 500) + "...";
                    sb.append("    snippet: ").append(snippet).append("\n");
                }
            }
        }
        sb.append("[/TOOL_RESULT]\n\n");
        sb.append("请基于以上工具结果继续完成用户请求；如果还需要工具，继续输出 JSON 工具调用；");
        sb.append("如果已经足够，输出最终自然语言回答。");
        return sb.toString();
    }

    /**
     * 检测文本是否看起来像 raw tool output 而非自然语言。
     * 触发条件：以 [TOOL_RESULT] 开头、是纯 JSON 对象（无 "tool" 字段的 JSON 可能是工具结果回显）。
     */
    static boolean isRawToolOutput(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        // 以 [TOOL_RESULT] 或 [工具 开头 → 工具结果泄漏
        if (trimmed.startsWith("[TOOL_RESULT]") || trimmed.startsWith("[工具")) {
            return true;
        }
        // 纯 JSON 对象且不含 "tool" 字段 → 可能是 raw tool result 回显
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                JsonNode node = MAPPER.readTree(trimmed);
                if (!node.has("tool")) return true;
            } catch (Exception ignored) {
                // 不是合法 JSON，不过滤
            }
        }
        return false;
    }

    // ---- raw JSON 防泄露 ----

    /**
     * 从最终回复文本中移除 raw tool JSON 和 fenced code block。
     * 防止 ```json...``` 或 {"tool":"..."} 原样泄露给用户。
     */
    static String stripRawToolJson(String text) {
        if (text == null || text.isBlank()) return text;
        String result = text;

        // 移除 fenced code blocks (```json ... ``` 或 ``` ... ```)
        result = result.replaceAll(
                "```[a-z]*\\s*\\n?\\{\"tool\"[^`]*```\\s*",
                "[工具调用已执行]");
        // 移除内联 fenced blocks
        result = result.replaceAll(
                "```[a-z]*\\s*\\{\"tool\"[^`]*```\\s*",
                "[工具调用已执行]");

        // 移除裸露的 {"tool":"..."} JSON（不在 fence 中但仍是 raw JSON）
        result = result.replaceAll(
                "\\{\"tool\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"args\"\\s*:\\s*\\{[^}]*\\}\\}",
                "[工具调用已执行]");

        return result;
    }

    /**
     * 保守规则：如果 finalText 包含"已保存/已创建/已切换/已入库/已写入"等成功宣称，
     * 但整个 ToolLoop 中没有执行任何工具，则追加警告。
     * 这防止 assistant 在没有 tool_result 支撑的情况下声称操作成功。
     */
    static String guardSuccessClaimWithoutToolBacking(String finalText,
                                                       List<ToolCallRecord> toolCalls,
                                                       int toolRound) {
        if (finalText == null || finalText.isBlank()) return finalText;
        if (!toolCalls.isEmpty()) return finalText; // 有工具执行，允许

        // 检查是否包含成功宣称关键词
        String[] claims = {"已保存", "已创建", "已切换", "已进入", "已入库", "已写入", "已更新", "已完成"};
        boolean hasClaim = false;
        for (String c : claims) {
            if (finalText.contains(c)) {
                hasClaim = true;
                break;
            }
        }
        if (!hasClaim) return finalText;

        // 没有工具执行却有成功宣称 → 追加警告
        return finalText + "\n\n⚠️ [系统提示] 以上回复包含操作成功宣称，"
                + "但在本轮对话中未检测到对应的工具执行记录。"
                + "如确有操作需要执行，请重新输入指令。";
    }

    // ---- fake [工具结果] 检测 ----

    /**
     * 检测 assistant 是否伪造了 [工具结果] 输出。
     * 当 LLM 在本轮没有执行工具，却在回复中使用 [工具结果] / {mode=...} / {branchId=...} 等格式
     * 模仿工具输出时，判定为 MODEL_FAKE_TOOL_RESULT。
     */
    static boolean hasFakeBracketToolResult(String text, List<ToolCallRecord> recentToolCalls) {
        if (text == null || text.isBlank()) return false;

        // 检查是否包含伪造的工具结果标记
        boolean hasBracketResult = text.contains("[工具结果]");
        // 检查是否包含伪造的 {key=value} 工具输出模式
        boolean hasFakeKvBlock = text.matches(
                "(?s).*\\{(?:mode|branchId|activeBranch|createdBranchId|parentBranchId|switched|turn|status)\\s*[=:].*\\}.*");

        if (!hasBracketResult && !hasFakeKvBlock) return false;

        // 如果最近有真实的工具执行，这些标记可能是合理的（工具结果可能包含这些 key）
        if (recentToolCalls != null && !recentToolCalls.isEmpty()) return false;

        return true;
    }

    /**
     * 从 finalText 中剥离伪造的 [工具结果] 和 {key=value} 块。
     */
    static String stripFakeBracketToolResult(String text) {
        if (text == null || text.isBlank()) return text;

        // 移除 [工具结果] ... [工具结果] 块（含内容）
        String result = text.replaceAll(
                "(?s)\\[工具结果\\][^\\[]*?(?=\\[工具结果\\]|\\n\\n|$)", "");
        // 移除孤立的 [工具结果] 标记
        result = result.replace("[工具结果]", "");
        // 移除伪造的 {mode=tree} / {branchId=b0002} 等孤立块
        result = result.replaceAll(
                "\\{(?:mode|branchId|activeBranch|createdBranchId|parentBranchId|switched|turn|status)\\s*[=:]\\s*[^}]*\\}",
                "");

        return result.trim();
    }

    /** 旧版 tool result 格式化（保留给旧 run() 路径使用）。 */
    private String formatToolResult(ToolResult result) {
        return buildToolResultFeedback(result.toolName(), result);
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

            // 提取所有 tool call（支持一次回复中包含多个工具调用）
            List<ParsedToolCall> allParsed = ToolCallExtractor.extractAllToolCalls(content);
            if (!allParsed.isEmpty()) {
                int toolsBefore = toolCalls.size();
                for (ParsedToolCall parsed : allParsed) {
                    trace.add(new MessageTrace("tool", "tool_call", parsed.tool + " " + parsed.args));
                    ToolCall call = new ToolCall(parsed.tool, parsed.args);
                    ToolResult result = toolRegistry.call(call);
                    toolCalls.add(new ToolCallRecord(parsed.tool, parsed.args, result));

                    messages.add(LlmMessage.user(buildToolResultFeedback(parsed.tool, result)));
                    trace.add(new MessageTrace("tool", "tool_result",
                            "tool=" + parsed.tool + " success=" + result.success()));
                }
                toolRound++;
                // 如果这条回复中成功提取并执行了工具，继续 ToolLoop
                if (toolCalls.size() > toolsBefore) {
                    continue;
                }
            }

            finalText = content;
            // 守卫：如果 LLM 输出看起来像 raw tool result（而非自然语言），追加一轮纠正
            if (isRawToolOutput(finalText) && toolRound < MAX_TOOL_ROUNDS) {
                messages.add(LlmMessage.user(
                        "请将以上结果转化为自然语言回答。不要输出原始 JSON 或 [TOOL_RESULT] 标记。"));
                trace.add(new MessageTrace("system", "guard_retry",
                        "finalText appears to be raw tool output, requesting natural language"));
                toolRound++;
                continue;
            }
            if (ToolPollutionFilter.isPolluted(finalText)) {
                trace.add(new MessageTrace("assistant", "chat_response",
                        "[filtered, length=" + finalText.length() + "]"));
            } else {
                trace.add(new MessageTrace("assistant", "chat_response", finalText));
            }
            break;
        }

        if (finalText == null) {
            messages.add(LlmMessage.user("请基于以上信息给出自然语言回答，不要调用更多工具，不要输出原始 JSON。"));
            LlmResponse fr = llmClient.chat(new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048));
            if (fr.success()) {
                finalText = fr.content();
                trace.add(new MessageTrace("assistant", "chat_response", finalText));
            } else {
                return new ChatResult(false, "", toolCalls, trace, fr.errorMessage());
            }
        }

        // 后处理：strip raw JSON、检测 MODEL_FAKE_TOOL_RESULT、检查无 tool_result 的成功宣称
        if (hasFakeBracketToolResult(finalText, toolCalls)) {
            log.warn("MODEL_FAKE_TOOL_RESULT detected in chat — stripping fabricated tool output");
            finalText = "[系统提示] 检测到模型伪造的工具结果（未经过真实工具执行），以下内容已过滤。\n\n"
                    + stripFakeBracketToolResult(finalText);
        }
        finalText = stripRawToolJson(finalText);
        finalText = guardSuccessClaimWithoutToolBacking(finalText, toolCalls, toolRound);

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

            // 提取所有 tool call（支持一次回复中包含多个工具调用）
            List<ParsedToolCall> allParsed = ToolCallExtractor.extractAllToolCalls(content);
            if (!allParsed.isEmpty()) {
                int toolsBefore = toolCalls.size();
                for (ParsedToolCall parsed : allParsed) {
                    trace.add(new MessageTrace("tool", "tool_call", parsed.tool + " " + parsed.args));
                    ToolCall call = new ToolCall(parsed.tool, parsed.args);
                    ToolResult result = toolRegistry.call(call);
                    toolCalls.add(new ToolCallRecord(parsed.tool, parsed.args, result));

                    messages.add(LlmMessage.user(buildToolResultFeedback(parsed.tool, result)));
                    trace.add(new MessageTrace("tool", "tool_result",
                            "tool=" + parsed.tool + " success=" + result.success()));
                }
                toolRound++;
                // 如果这条回复中成功提取并执行了工具，继续 ToolLoop
                if (toolCalls.size() > toolsBefore) {
                    continue;
                }
            }

            finalText = content;
            // 守卫：如果 LLM 输出看起来像 raw tool result，追加一轮纠正
            if (isRawToolOutput(finalText) && toolRound < MAX_TOOL_ROUNDS) {
                messages.add(LlmMessage.user(
                        "请将以上结果转化为自然语言推演输出。不要输出原始 JSON 或 [TOOL_RESULT] 标记。"));
                trace.add(new MessageTrace("system", "guard_retry",
                        "finalText appears to be raw tool output, requesting natural language"));
                toolRound++;
                continue;
            }
            if (ToolPollutionFilter.isPolluted(finalText)) {
                trace.add(new MessageTrace("assistant", "sim_output",
                        "[filtered, length=" + finalText.length() + "]"));
            } else {
                trace.add(new MessageTrace("assistant", "sim_output", finalText));
            }
            break;
        }

        if (finalText == null) {
            messages.add(LlmMessage.user("已进行多轮工具调用。请基于以上所有结果写一段推演总结，不要输出原始 JSON。不要调用更多工具。"));
            LlmResponse fr = llmClient.chat(new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048));
            if (fr.success()) {
                finalText = fr.content();
                trace.add(new MessageTrace("assistant", "sim_output", finalText));
            } else {
                finalText = "推演总结生成失败: " + fr.errorMessage();
            }
        }

        // 后处理：strip raw JSON、检测 MODEL_FAKE_TOOL_RESULT、检查无 tool_result 的成功宣称
        if (hasFakeBracketToolResult(finalText, toolCalls)) {
            log.warn("MODEL_FAKE_TOOL_RESULT detected in sim — stripping fabricated tool output");
            finalText = "[系统提示] 检测到模型伪造的工具结果（未经过真实工具执行），以下内容已过滤。\n\n"
                    + stripFakeBracketToolResult(finalText);
        }
        finalText = stripRawToolJson(finalText);
        finalText = guardSuccessClaimWithoutToolBacking(finalText, toolCalls, toolRound);

        return new SimResult(finalText, toolCalls, trace);
    }
}
