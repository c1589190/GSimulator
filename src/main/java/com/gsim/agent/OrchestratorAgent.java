package com.gsim.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.campaign.PlayerAction;
import com.gsim.chat.ToolPollutionFilter;
import com.gsim.context.session.SessionMessage;
import com.gsim.llm.DefaultLlmStreamCollector;
import com.gsim.llm.LlmClient;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResponse;
import com.gsim.llm.LlmStreamCollector;
import com.gsim.llm.LlmToolCall;
import com.gsim.llm.ToolDef;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrator Agent — 主协调者。
 * 接收玩家行动和主持人指令，驱动 LLM 推演，支持 ToolLoop。
 *
 * ToolLoop 流程：
 * 1. 构建 system prompt（含可用工具说明）+ user prompt（玩家行动）
 * 2. 发送 LLM
 * 3. 解析响应：普通文本 → 最终结果；JSON tool call → 调用工具 → 追加结果 → 回到步骤 2
 * 4. 最多 N 轮 tool 调用（可配置，默认 8），超限后要求 LLM 直接总结
 */
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final AgentProgressSink progressSink;
    private final ToolRoutePolicy routePolicy;
    private final ToolExecutionPolicy executionPolicy;
    private final ToolPermissionGate permissionGate;
    private final ToolPermissionConfig permissionConfig;

    /** Agent ToolLoop 最大工具轮数（默认 32，≥1，可由 setter 注入覆盖）。 */
    private volatile int maxToolRounds = 32;

    /** LLM 流式输出开关（由 AppConfig 注入，默认 false）。 */
    private volatile boolean streamEnabled = false;

    /** LLM 流式状态注册表（每个 stream 独立状态，CLI/WebUI 并发安全）。 */
    private volatile LlmStreamStateRegistry streamRegistry = new LlmStreamStateRegistry();

    /** 对话上下文历史配置（可运行时更新）。 */
    private volatile ContextHistoryConfig historyConfig = ContextHistoryConfig.DEFAULT;

    /** 缓存最近一次 assistant 输出（经 stripFake/guard 后处理），供 turn_settlement_save_last_response 使用。 */
    private final AtomicReference<String> lastAssistantDraft = new AtomicReference<>("");

    public OrchestratorAgent(LlmClient llmClient, ToolRegistry toolRegistry, String model) {
        this(llmClient, toolRegistry, model, AgentProgressSink.NOOP);
    }

    public OrchestratorAgent(LlmClient llmClient, ToolRegistry toolRegistry, String model,
                             AgentProgressSink progressSink) {
        this(llmClient, toolRegistry, model, progressSink, null);
    }

    public OrchestratorAgent(LlmClient llmClient, ToolRegistry toolRegistry, String model,
                             AgentProgressSink progressSink,
                             ToolPermissionGate permissionGate) {
        this(llmClient, toolRegistry, model, progressSink != null ? progressSink : AgentProgressSink.NOOP,
                new ToolRoutePolicy(), new ToolExecutionPolicy(),
                permissionGate, new ToolPermissionConfig());
    }

    OrchestratorAgent(LlmClient llmClient, ToolRegistry toolRegistry, String model,
                       AgentProgressSink progressSink,
                       ToolRoutePolicy routePolicy,
                       ToolExecutionPolicy executionPolicy,
                       ToolPermissionGate permissionGate,
                       ToolPermissionConfig permissionConfig) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.progressSink = progressSink != null ? progressSink : AgentProgressSink.NOOP;
        this.routePolicy = routePolicy != null ? routePolicy : new ToolRoutePolicy();
        this.executionPolicy = executionPolicy != null ? executionPolicy : new ToolExecutionPolicy();
        this.permissionGate = permissionGate;
        this.permissionConfig = permissionConfig != null ? permissionConfig : new ToolPermissionConfig();
    }

    /** 返回最近一次 assistant 输出草稿，用于工具保存。可能为空字符串。 */
    public String getLastAssistantDraft() {
        return lastAssistantDraft.get();
    }

    /** 设置上下文历史配置（由调用方在创建后注入）。 */
    public void setContextHistoryConfig(ContextHistoryConfig config) {
        this.historyConfig = config != null ? config : ContextHistoryConfig.DEFAULT;
    }

    /** 设置最大工具轮数（由调用方从 AppConfig 注入，默认 32，下限 1，无上限）。 */
    public void setMaxToolRounds(int rounds) {
        this.maxToolRounds = Math.max(1, rounds);
        log.debug("[ToolLoop] maxToolRounds={}", this.maxToolRounds);
    }

    /** 获取当前最大工具轮数。 */
    public int getMaxToolRounds() {
        return maxToolRounds;
    }

    /** 设置是否使用 LLM 流式输出（由 AppConfig 注入）。 */
    public void setStreamEnabled(boolean streamEnabled) {
        this.streamEnabled = streamEnabled;
    }

    /** 设置 LLM 流式状态注册表。 */
    public void setStreamRegistry(LlmStreamStateRegistry registry) {
        this.streamRegistry = registry != null ? registry : new LlmStreamStateRegistry();
    }

    /** 获取 LLM 流式状态注册表（供 CliAgentProgressSink 等消费者使用）。 */
    public LlmStreamStateRegistry getStreamRegistry() {
        return streamRegistry;
    }

    /** 获取流式输出开关。 */
    public boolean isStreamEnabled() {
        return streamEnabled;
    }

    /**
     * 调用 LLM — 根据 streamEnabled 配置选择流式或非流式路径。
     *
     * <p>流式路径：通过 {@link LlmClient#stream} 实时接收 delta，
     * 同时将 delta 作为 {@link AgentProgressEvent} 发送给 progressSink（CLI 灰框预览）。
     * 流式结束后组装完整 {@link LlmResponse}，语义与 chat() 完全一致。
     *
     * <p>非流式路径：直接调用 {@link LlmClient#chat}。
     */
    private LlmResponse callLlm(LlmRequest request) {
        int requestTools = request.tools() != null ? request.tools().size() : 0;
        log.debug("[ORCH_STREAM] streamEnabled={} requestTools={}", streamEnabled, requestTools);

        if (!streamEnabled) {
            return llmClient.chat(request);
        }

        // 流式路径：每个 call 一个独立 streamId
        String streamId = java.util.UUID.randomUUID().toString();
        LlmStreamStateRegistry registry = this.streamRegistry;
        registry.start(streamId);

        DefaultLlmStreamCollector collector = new DefaultLlmStreamCollector();

        // delta 事件计数器（用于 [ORCH_STREAM] debug）
        java.util.concurrent.atomic.AtomicInteger contentDeltaEvents = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger reasoningDeltaEvents = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger toolCallDeltaEvents = new java.util.concurrent.atomic.AtomicInteger(0);

        // 包装 collector：delta 同时写入内部 collector、registry 和 progressSink
        LlmStreamCollector wrapped = new LlmStreamCollector() {
            private boolean started = false;

            @Override
            public void onStart() {
                collector.onStart();
                if (!started) {
                    progressSink.onProgress(AgentProgressEvent.llmStreamStarted(streamId));
                    started = true;
                }
            }

            @Override
            public void onContentDelta(String text) {
                collector.onContentDelta(text);
                contentDeltaEvents.incrementAndGet();
                registry.appendContent(streamId, text);
                progressSink.onProgress(AgentProgressEvent.llmContentDelta(streamId, text));
            }

            @Override
            public void onReasoningDelta(String text) {
                collector.onReasoningDelta(text);
                reasoningDeltaEvents.incrementAndGet();
                registry.appendReasoning(streamId, text);
                progressSink.onProgress(AgentProgressEvent.llmReasoningDelta(streamId, text));
            }

            @Override
            public void onToolCallDelta(String text) {
                collector.onToolCallDelta(text);
                toolCallDeltaEvents.incrementAndGet();
                registry.incrementToolCallDelta(streamId);
                progressSink.onProgress(AgentProgressEvent.llmToolCallDelta(streamId));
            }

            @Override
            public void onError(Throwable error) {
                collector.onError(error);
                String errMsg = error != null ? error.getMessage() : "未知错误";
                registry.fail(streamId, errMsg);
                progressSink.onProgress(AgentProgressEvent.llmStreamFailed(streamId, errMsg));
            }

            @Override
            public void onComplete() {
                collector.onComplete();
            }

            @Override
            public void setFinalResponse(LlmResponse response) {
                collector.setFinalResponse(response);
            }

            @Override
            public LlmResponse getFinalResponse() {
                return collector.getFinalResponse();
            }

            @Override
            public void setReasoning(String reasoning) {
                collector.setReasoning(reasoning);
            }

            @Override
            public String getReasoning() {
                return collector.getReasoning();
            }

            @Override
            public void setToolCalls(List<LlmToolCall> toolCalls) {
                collector.setToolCalls(toolCalls);
            }

            @Override
            public List<LlmToolCall> getToolCalls() {
                return collector.getToolCalls();
            }

            @Override
            public String getFullContent() {
                return collector.getFullContent();
            }
        };

        try {
            llmClient.stream(request, wrapped);

            // stream() 完成后，从 collector 获取最终响应
            LlmResponse finalResponse = wrapped.getFinalResponse();
            registry.complete(streamId);
            progressSink.onProgress(AgentProgressEvent.llmStreamCompleted(streamId));

            // [ORCH_STREAM] 汇总日志
            LlmStreamSnapshot snap = registry.snapshot(streamId);
            int responseToolCallCount = 0;
            boolean responseHasToolCalls = false;
            int finalContentChars = 0;
            if (finalResponse != null) {
                responseHasToolCalls = finalResponse.hasApiToolCalls();
                responseToolCallCount = responseHasToolCalls ? finalResponse.toolCalls().size() : 0;
                finalContentChars = finalResponse.content() != null ? finalResponse.content().length() : 0;
            }
            log.debug("[ORCH_STREAM] streamId={} responseHasToolCalls={} responseToolCallCount={}"
                            + " contentDeltaEvents={} reasoningDeltaEvents={} toolCallDeltaEvents={}"
                            + " finalContentChars={} registryContentChars={} registryReasoningChars={}"
                            + " finalParsedTools={}",
                    streamId, responseHasToolCalls, responseToolCallCount,
                    contentDeltaEvents.get(), reasoningDeltaEvents.get(), toolCallDeltaEvents.get(),
                    finalContentChars, snap.content().length(), snap.reasoning().length(),
                    responseHasToolCalls ? finalResponse.toolCalls().stream()
                            .map(LlmToolCall::name).toList() : List.of());

            if (finalResponse != null) {
                return finalResponse;
            }

            // fallback：如果 collector 没收到最终响应（兼容未设置 setFinalResponse 的场景），
            // 从 collector 手动组装
            String content = wrapped.getFullContent();
            List<LlmToolCall> toolCalls = wrapped.getToolCalls();

            if (!toolCalls.isEmpty()) {
                log.debug("[ORCH_STREAM] streamId={} fallbackToolCalls={}", streamId,
                        toolCalls.stream().map(LlmToolCall::name).toList());
                return LlmResponse.successWithToolCalls(toolCalls, model, 0);
            }
            if (content != null && !content.isEmpty()) {
                log.debug("[ORCH_STREAM] streamId={} fallbackContentChars={}", streamId, content.length());
                return LlmResponse.success(content, model, 0);
            }
            return LlmResponse.success("", model, 0);
        } catch (Exception e) {
            log.error("LLM stream call failed: {}", e.getMessage(), e);
            registry.fail(streamId, e.getMessage());
            progressSink.onProgress(AgentProgressEvent.llmStreamFailed(streamId, e.getMessage()));
            return LlmResponse.failure(e.getMessage());
        }
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
        List<ToolDef> toolDefs = buildToolDefs();

        // 3. ToolLoop
        while (toolRound < maxToolRounds) {
            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048, toolDefs);
            LlmResponse response = callLlm(request);

            if (!response.success()) {
                log.error("LLM call failed: {}", response.errorMessage());
                return new RunResult("LLM 调用失败: " + response.errorMessage(), toolCalls);
            }

            String content = response.content();
            messages.add(LlmMessage.assistant(content != null ? content : ""));

            // 4. 解析 tool call（API tool_calls 优先）
            List<ParsedToolCall> allParsed = new ArrayList<>();
            if (response.hasApiToolCalls()) {
                allParsed.addAll(fromApiToolCalls(response.toolCalls()));
            }
            if (allParsed.isEmpty()) {
                ParsedToolCall parsed = tryParseToolCall(content);
                if (parsed != null) allParsed.add(parsed);
            }

            if (!allParsed.isEmpty()) {
                for (ParsedToolCall parsed : allParsed) {
                    log.info("Tool call detected: {} with args: {}", parsed.tool, parsed.args);
                    ToolCall call = new ToolCall(parsed.tool, parsed.args);
                    ToolResult result = toolRegistry.call(call);
                    toolCalls.add(new ToolCallRecord(parsed.tool, parsed.args, result));

                    // 将 tool 结果追加到上下文
                    String toolResultText = formatToolResult(result);
                    messages.add(LlmMessage.user(toolResultText));
                }
                toolRound++;
                continue;
            }

            // 5. 普通文本 → 最终结果
            finalText = content;
            break;
        }

        // 6. 超过最大轮数，要求总结
        if (finalText == null) {
            log.warn("Max tool rounds ({}) reached, forcing summary", maxToolRounds);
            messages.add(LlmMessage.user(
                    "你已经完成了多轮工具调用。请基于以上所有查询结果，" +
                    "写一段完整的推演总结。必须引用信息来源的路径（如 import/web/prts.wiki/...）。" +
                    "不要调用更多工具，直接输出推演结果。"));
            LlmRequest finalRequest = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048, toolDefs);
            LlmResponse finalResponse = callLlm(finalRequest);
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
        sb.append("如果已经足够，调用 finish_action 结束本轮。不要直接输出最终回答而不调用 finish_action。");
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

    /**
     * 将 assistant 输出转化为干净的草稿（供 turn_settlement_save_last_response 使用）。
     * 剥离 fenced JSON、裸 tool call JSON、[工具结果] 标记和伪造的 {key=value} 块。
     */
    static String toCleanDraft(String text) {
        if (text == null || text.isBlank()) return text;
        String result = stripRawToolJson(text);
        result = stripFakeBracketToolResult(result);
        return result.trim();
    }

    // ---- finish_action 验证 ----

    /**
     * 验证 finish_action.message 不包含工具内部标记（占位符、raw JSON、伪造结果）。
     * 返回 null 表示通过，返回 String 表示错误消息。
     */
    static String validateFinishActionMessage(String message) {
        if (message == null || message.isBlank()) {
            return "finish_action message 不能为空";
        }
        if (message.contains("[工具调用已执行]")) {
            return "finish_action.message 包含 [工具调用已执行] 占位符，"
                    + "请用自然语言描述操作结果，重新调用 finish_action。";
        }
        if (message.contains("[工具结果]") || message.contains("[TOOL_RESULT]")) {
            return "finish_action.message 包含伪造的 [工具结果] / [TOOL_RESULT] 标记，"
                    + "请移除所有工具内部标记，只输出给用户的自然语言回复，重新调用 finish_action。";
        }
        if (message.contains("```json") || (message.contains("```") && message.contains("\"tool\""))) {
            return "finish_action.message 包含 fenced JSON tool call，"
                    + "请移除所有 ``` 代码块，用自然语言重写，重新调用 finish_action。";
        }
        if (message.matches("(?s).*\\{\"tool\"\\s*:\\s*\"[^\"]+\".*\\}.*")) {
            return "finish_action.message 包含裸 JSON tool call，"
                    + "请移除所有 {\"tool\":...} 内容，用自然语言重写，重新调用 finish_action。";
        }
        // 检测裸 JSON（非 tool call，含 { 但不含 "tool": → 可能是 raw tool output 泄露）
        String trimmed = message.strip();
        if (trimmed.startsWith("{") && !trimmed.contains("\"tool\"")) {
            return "finish_action.message 包含裸 JSON 工具输出（不含 tool 字段），"
                    + "请用自然语言重写，重新调用 finish_action。";
        }
        // 检测伪造的 {key=value} 模式（model 幻觉生成的结果）
        if (message.matches("(?s).*\\{(branchId|mode|status|title|createdBranchId|activeBranchId)\\s*=\\s*[^}]+\\}.*")) {
            return "finish_action.message 包含伪造的 key=value 模式（疑似模型幻觉），"
                    + "请移除所有 {key=value} 内容，用自然语言重写，重新调用 finish_action。";
        }
        return null;
    }

    /**
     * 验证 finish_action.message 中的成功宣称是否有对应的工具执行记录支撑。
     * 返回 null 表示通过，返回 String 表示错误消息。
     */
    static String validateFinishActionClaims(String message, List<ToolCallRecord> toolCalls) {
        if (message == null || message.isBlank()) return null;

        if (toolCalls.isEmpty()) {
            String[] claims = {"已保存", "已创建", "已切换", "已进入",
                    "已入库", "已写入", "已更新", "已完成", "已记录"};
            for (String c : claims) {
                if (message.contains(c)) {
                    return "finish_action 声称操作成功（'" + c + "'），"
                            + "但本轮没有执行任何工具。请先调用工具完成操作，再调用 finish_action。";
                }
            }
            return null;
        }

        java.util.Set<String> successTools = new java.util.HashSet<>();
        for (ToolCallRecord tc : toolCalls) {
            if (tc.result().success()) {
                successTools.add(tc.tool());
            }
        }

        if (message.contains("已保存") && !hasAnyToolStartingWith(successTools, "turn_settlement_save")) {
            return "finish_action 声称'已保存'，但没有 turn_settlement_save* 成功执行记录。"
                    + "请先调用 turn_settlement_save_last_response，再调用 finish_action。";
        }
        if ((message.contains("已进入") || message.contains("已切换") || message.contains("切换到"))
                && !successTools.contains("branch_next_turn")
                && !successTools.contains("branch_switch")) {
            return "finish_action 声称'已进入/已切换'，但没有 branch_next_turn 或 branch_switch 成功执行记录。"
                    + "请先调用 branch_next_turn，再调用 finish_action。";
        }
        if (message.contains("已创建")
                && !hasAnyToolStartingWith(successTools, "branch_create_child")
                && !successTools.contains("branch_next_turn")
                && !successTools.contains("knowledge_upsert")
                && !successTools.contains("player_action_append")
                && !successTools.contains("turn_settlement_save_last_response")) {
            return "finish_action 声称'已创建'，但没有对应的创建工具成功执行记录。"
                    + "请先调用 branch_create_child / knowledge_upsert / player_action_append 等创建工具，再调用 finish_action。";
        }
        if (message.contains("已写入") && !successTools.contains("knowledge_upsert")) {
            return "finish_action 声称'已写入'，但没有 knowledge_upsert 成功执行记录。"
                    + "请先调用 knowledge_upsert，再调用 finish_action。";
        }
        if (message.contains("已记录") && !successTools.contains("player_action_append")) {
            return "finish_action 声称'已记录'，但没有 player_action_append 成功执行记录。"
                    + "请先调用 player_action_append，再调用 finish_action。";
        }
        return null;
    }

    private static boolean hasAnyToolStartingWith(java.util.Set<String> tools, String prefix) {
        for (String t : tools) {
            if (t.startsWith(prefix)) return true;
        }
        return false;
    }

    // ---- ToolLoop debug helpers ----

    /** 从 validation error 消息中提取简短的 reject reason code。 */
    private static String reasonFromMessageError(String error) {
        if (error == null) return null;
        if (error.contains("[工具调用已执行]")) return "BANNED_CONTENT_PLACEHOLDER_TOOL_EXECUTED";
        if (error.contains("[工具结果]") || error.contains("[TOOL_RESULT]")) return "BANNED_CONTENT_FAKE_TOOL_RESULT";
        if (error.contains("fenced JSON") || error.contains("fenced code block")) return "BANNED_CONTENT_FENCED_JSON";
        if (error.contains("裸 JSON")) return "BANNED_CONTENT_RAW_JSON";
        if (error.contains("key=value") || error.contains("疑似模型幻觉")) return "BANNED_CONTENT_FAKE_KV";
        if (error.contains("不合法的 finish_action")) return "BANNED_CONTENT_ILLEGAL_TOOL_CALL";
        return "BANNED_CONTENT_UNKNOWN";
    }

    /** 从 claim validation error 中提取具体的 claim 关键词。 */
    private static String extractClaimFromError(String error) {
        if (error == null) return null;
        if (error.contains("已保存")) return "已保存";
        if (error.contains("已进入") || error.contains("已切换")) return "已进入/已切换";
        if (error.contains("已创建")) return "已创建";
        if (error.contains("已写入")) return "已写入";
        if (error.contains("已记录")) return "已记录";
        return "unknown";
    }

    /** 提取本轮成功工具名称集合（供 debug 日志使用）。 */
    private static java.util.Set<String> successToolNames(List<ToolCallRecord> toolCalls) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ToolCallRecord tc : toolCalls) {
            if (tc.result().success()) names.add(tc.tool());
        }
        return names;
    }

    /** 从消息列表中提取最后一条 user 消息（用于意图检测）。 */
    private static String lastUserMessage(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return "";
    }

    /** 工具结果摘要（供 debug 日志）。 */
    private static String summarizeToolResult(ToolResult result) {
        if (result == null || result.items().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ToolResult.Item item : result.items()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item.title()).append(": ");
            String s = item.snippet();
            if (s != null) {
                if (s.length() > 200) s = s.substring(0, 197) + "...";
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /** 从 ToolRegistry 构建传给 API 的工具定义列表。 */
    private List<ToolDef> buildToolDefs() {
        List<ToolDef> defs = new ArrayList<>();
        for (var tool : toolRegistry.all().values()) {
            var params = tool.getParameters();
            defs.add(params != null
                    ? new ToolDef(tool.name(), tool.description(), params)
                    : new ToolDef(tool.name(), tool.description()));
        }
        return defs;
    }

    /** 只构建 finish_action 的 ToolDef（用于 forced finish_action 阶段）。 */
    private ToolDef buildFinishActionToolDef() {
        var tool = toolRegistry.all().get("finish_action");
        if (tool != null) {
            var params = tool.getParameters();
            return params != null
                    ? new ToolDef(tool.name(), tool.description(), params)
                    : new ToolDef(tool.name(), tool.description());
        }
        return new ToolDef("finish_action",
                "结束当前工具调用循环并返回最终回复。参数: message（必填）");
    }

    /** 构建 forced tool_choice object 强制模型只调用指定工具。 */
    static Object forceToolChoice(String toolName) {
        return java.util.Map.of(
                "type", "function",
                "function", java.util.Map.of("name", toolName));
    }

    /** 将 API tool_calls 转为内部 ParsedToolCall 列表。 */
    private static List<ParsedToolCall> fromApiToolCalls(List<LlmToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return List.of();
        List<ParsedToolCall> result = new ArrayList<>();
        for (LlmToolCall tc : toolCalls) {
            result.add(new ParsedToolCall(tc.name(),
                    tc.arguments() != null ? tc.arguments() : Map.of()));
        }
        return result;
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
        List<ToolDef> toolDefs = buildToolDefs();

        while (toolRound < maxToolRounds) {
            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048, toolDefs);
            LlmResponse response = callLlm(request);

            if (!response.success()) {
                String err = "LLM 调用失败: " + response.errorMessage();
                trace.add(new MessageTrace("system", "error", err));
                return new SimResult(err, toolCalls, trace);
            }

            String content = response.content();
            messages.add(LlmMessage.assistant(content != null ? content : ""));

            // API tool_calls 优先
            List<ParsedToolCall> allParsed = new ArrayList<>();
            if (response.hasApiToolCalls()) {
                allParsed.addAll(fromApiToolCalls(response.toolCalls()));
            }
            if (allParsed.isEmpty()) {
                ParsedToolCall parsed = tryParseToolCall(content);
                if (parsed != null) allParsed.add(parsed);
            }

            if (!allParsed.isEmpty()) {
                for (ParsedToolCall parsed : allParsed) {
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
                }
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
            LlmResponse finalResp = callLlm(new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048, toolDefs));
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
        List<ToolDef> toolDefs = buildToolDefs();

        while (toolRound < maxToolRounds) {
            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048, toolDefs);
            LlmResponse response = callLlm(request);

            if (!response.success()) {
                return new ChatResult(false, "", toolCalls, trace, response.errorMessage());
            }

            String content = response.content();
            messages.add(LlmMessage.assistant(content != null ? content : ""));

            // API tool_calls 优先
            List<ParsedToolCall> allParsed = new ArrayList<>();
            if (response.hasApiToolCalls()) {
                allParsed.addAll(fromApiToolCalls(response.toolCalls()));
            }
            if (allParsed.isEmpty()) {
                ParsedToolCall parsed = tryParseToolCall(content);
                if (parsed != null) allParsed.add(parsed);
            }

            if (!allParsed.isEmpty()) {
                for (ParsedToolCall parsed : allParsed) {
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
                }
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
            LlmResponse fr = callLlm(new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048, toolDefs));
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
        return chatWithContextSession(baseContextMarkdown, sessionMessages, userText,
                AgentContextMeta.empty());
    }

    public ChatResult chatWithContextSession(String baseContextMarkdown,
                                              List<SessionMessage> sessionMessages,
                                              String userText,
                                              AgentContextMeta contextMeta) {
        ContextHistoryConfig cfg = this.historyConfig;
        List<SessionMessage> filtered = filterByTurns(sessionMessages, cfg.historyTurns());

        if (log.isDebugEnabled()) {
            log.debug("[ContextSession] historyTurns={}, originalMessages={}, filteredMessages={},"
                            + " messageMaxChars={}",
                    cfg.historyTurns(), sessionMessages != null ? sessionMessages.size() : 0,
                    filtered.size(), cfg.messageMaxChars());
        }

        List<MessageTrace> trace = new ArrayList<>();
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        List<LlmMessage> messages = new ArrayList<>();

        // 1. system: orchestrator-system.md + ToolCatalog + BaseContextSnapshot
        messages.add(LlmMessage.system(buildFullSystemPrompt(baseContextMarkdown)));

        // 2. history: 当前 ContextSession 内 SessionMessage（按 turn 过滤 + 单条截断）
        int renderedChars = 0;
        for (SessionMessage sm : filtered) {
            String content = sm.content();
            if (content.length() > cfg.messageMaxChars()) {
                content = content.substring(0, cfg.messageMaxChars() - 3) + "...";
            }
            renderedChars += content.length();
            switch (sm.role()) {
                case "user" -> messages.add(LlmMessage.user(content));
                case "assistant" -> messages.add(LlmMessage.assistant(content));
                case "tool" -> messages.add(LlmMessage.user("[工具结果] " + content));
                default -> messages.add(LlmMessage.user(content));
            }
        }

        log.debug("[ContextSession] renderedChars={}", renderedChars);

        // 3. user: 当前输入
        messages.add(LlmMessage.user(userText));
        trace.add(new MessageTrace("user", "chat_user", userText));

        return runToolLoop(messages, trace, toolCalls, false, contextMeta, userText);
    }

    /**
     * 基于 ContextSession 的推演模式。
     * LLM messages = system(orchestrator-system.md + ToolCatalog + BaseContext) + sessionMessages + user(sim prompt)
     */
    public SimResult runWithContextSession(String baseContextMarkdown,
                                            List<SessionMessage> sessionMessages,
                                            String simNote) {
        ContextHistoryConfig cfg = this.historyConfig;
        List<SessionMessage> filtered = filterByTurns(sessionMessages, cfg.historyTurns());

        if (log.isDebugEnabled()) {
            log.debug("[ContextSession] historyTurns={}, originalMessages={}, filteredMessages={},"
                            + " messageMaxChars={}",
                    cfg.historyTurns(), sessionMessages != null ? sessionMessages.size() : 0,
                    filtered.size(), cfg.messageMaxChars());
        }

        List<MessageTrace> trace = new ArrayList<>();
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        List<LlmMessage> messages = new ArrayList<>();

        // 1. system: orchestrator-system.md + ToolCatalog + BaseContextSnapshot
        messages.add(LlmMessage.system(buildFullSystemPrompt(baseContextMarkdown)));

        // 2. history: 当前 ContextSession 内 SessionMessage（按 turn 过滤 + 单条截断）
        int renderedChars = 0;
        for (SessionMessage sm : filtered) {
            String content = sm.content();
            if (content.length() > cfg.messageMaxChars()) {
                content = content.substring(0, cfg.messageMaxChars() - 3) + "...";
            }
            renderedChars += content.length();
            switch (sm.role()) {
                case "user" -> messages.add(LlmMessage.user(content));
                case "assistant" -> messages.add(LlmMessage.assistant(content));
                case "tool" -> messages.add(LlmMessage.user("[工具结果] " + content));
                default -> messages.add(LlmMessage.user(content));
            }
        }

        log.debug("[ContextSession] renderedChars={}", renderedChars);

        // 3. user: 推演提示
        String userMsg = "请基于以上上下文进行推演。";
        if (simNote != null && !simNote.isBlank()) {
            userMsg += "\n\n推演备注: " + simNote;
        }
        messages.add(LlmMessage.user(userMsg));
        trace.add(new MessageTrace("user", "sim_input", userMsg));

        SimResult result = runSimToolLoop(messages, trace, toolCalls, AgentContextMeta.empty(), simNote);
        return result;
    }

    /** 公共 tool-loop（chat 模式）。 */
    private ChatResult runToolLoop(List<LlmMessage> messages,
                                    List<MessageTrace> trace,
                                    List<ToolCallRecord> toolCalls,
                                    boolean isSim,
                                    AgentContextMeta contextMeta,
                                    String userText) {
        String finalText = null;
        int toolRound = 1;
        int consecutiveNoToolRounds = 0;
        int consecutiveInvalidToolIntent = 0;
        boolean allowAllMutations = false;
        boolean forcedFinishAction = false;
        String lastPlainContent = null;
        UserIntent userIntent = UserIntent.infer(userText);
        ExpectedNextStep expectedNextStep = ExpectedNextStep.CALL_TOOL;
        List<ToolDef> allToolDefs = buildToolDefs();

        while (toolRound <= maxToolRounds) {
            // context load debug
            ToolLoopDebug.logContextLoad(log, "runToolLoop", toolRound, messages, allToolDefs, contextMeta);

            // task brief
            String lastToolName = !toolCalls.isEmpty()
                    ? toolCalls.get(toolCalls.size() - 1).tool() : null;
            boolean lastToolSuccess = !toolCalls.isEmpty()
                    && toolCalls.get(toolCalls.size() - 1).result().success();
            boolean hasToolResult = !toolCalls.isEmpty();
            java.util.List<String> expectedTools = forcedFinishAction
                    ? java.util.List.of("finish_action")
                    : (toolRound == 1 ? java.util.List.of() : java.util.List.of("finish_action"));
            ToolLoopDebug.logTaskBrief(log, "runToolLoop", toolRound,
                    userIntent, expectedNextStep,
                    lastToolName, lastToolSuccess, expectedTools);

            // 每轮动态构造 tools：forced finish_action 阶段只给 finish_action
            List<ToolDef> roundToolDefs;
            Object roundToolChoice;
            if (forcedFinishAction) {
                roundToolDefs = List.of(buildFinishActionToolDef());
                roundToolChoice = forceToolChoice("finish_action");
            } else {
                roundToolDefs = allToolDefs;
                roundToolChoice = "auto";
            }
            log.debug("[TOOL_LOOP_TRACE] engine=runToolLoop round={} requestTools={} toolChoice={} forcedFinishAction={}",
                    toolRound, roundToolDefs.size(),
                    roundToolChoice instanceof String ? roundToolChoice : "forced:" + ((java.util.Map<?,?>)roundToolChoice).get("function"),
                    forcedFinishAction);

            // progress: 上下文加载完毕
            int totalMessageChars = 0;
            for (var m : messages) { if (m.content() != null) totalMessageChars += m.content().length(); }
            int toolsJsonChars = ToolLoopDebug.estimateToolsJsonChars(roundToolDefs);
            progressSink.onProgress(AgentProgressEvent.contextLoaded(toolRound, maxToolRounds,
                    totalMessageChars + toolsJsonChars, roundToolDefs.size()));

            // progress: 等待 LLM
            progressSink.onProgress(AgentProgressEvent.waitingLlm(toolRound, maxToolRounds));

            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048, roundToolDefs, roundToolChoice);
            LlmResponse response = callLlm(request);

            if (!response.success()) {
                return new ChatResult(false, "", toolCalls, trace, response.errorMessage());
            }

            String content = response.content();
            int apiToolCallCount = response.hasApiToolCalls() ? response.toolCalls().size() : 0;
            String finishReason = response.finishReason();

            messages.add(LlmMessage.assistant(content != null ? content : ""));

            // DEBUG: LLM response preview
            ToolLoopDebug.logLlmResponse(log, "runToolLoop", toolRound, content,
                    apiToolCallCount, finishReason);

            // 缓存 draft
            String strippedForDraft = toCleanDraft(content);
            if (strippedForDraft != null && !strippedForDraft.isBlank()) {
                lastAssistantDraft.set(strippedForDraft);
            }
            ToolLoopDebug.logCleanedDraft(log, "runToolLoop", toolRound, strippedForDraft);

            // === 优先级 1: API 原生 tool_calls（主路径）===
            List<ParsedToolCall> allParsed;
            ToolLoopDebug.ToolCallSource toolSource;
            int textFallbackCount = 0;

            if (response.hasApiToolCalls()) {
                allParsed = fromApiToolCalls(response.toolCalls());
                toolSource = ToolLoopDebug.ToolCallSource.API_TOOL_CALLS;
            } else {
                // === 优先级 2: 文本 JSON fallback ===
                allParsed = ToolCallExtractor.extractAllToolCalls(content);
                if (!allParsed.isEmpty()) {
                    toolSource = ToolLoopDebug.ToolCallSource.TEXT_FALLBACK;
                    textFallbackCount = allParsed.size();
                } else if (ToolLoopDebug.isInvalidToolIntent(content)) {
                    // === 优先级 3: 非法工具意图 → 打回重写 ===
                    toolSource = ToolLoopDebug.ToolCallSource.INVALID_TOOL_INTENT;
                    allParsed = List.of();
                } else {
                    toolSource = ToolLoopDebug.ToolCallSource.NONE;
                }
            }

            ToolLoopDebug.logToolExtraction(log, "runToolLoop", toolRound, allParsed,
                    apiToolCallCount, textFallbackCount, toolSource, content);

            // === Auto-wrap: forced finish_action 阶段 LLM 仍返回普通文本 → 包裹为 finish_action ===
            if (forcedFinishAction && allParsed.isEmpty() && content != null && !content.isBlank()) {
                log.debug("[TOOL_LOOP_TRACE] forced finish_action returned plain content; auto-wrapping round={} chars={}",
                        toolRound, content.length());
                progressSink.onProgress(AgentProgressEvent.plainAnswerWithoutFinish(
                        toolRound, maxToolRounds));
                ParsedToolCall autoWrapped = new ParsedToolCall("finish_action",
                        java.util.Map.of("message", content.strip(), "status", "success"));
                allParsed = List.of(autoWrapped);
                toolSource = ToolLoopDebug.ToolCallSource.TEXT_FALLBACK;
            }

            // === 工具路由决策 ===
            ToolRouteDecision routeDecision = routePolicy.decide(
                    userIntent, expectedNextStep, permissionConfig.defaultEnabledTools());
            ToolLoopDebug.logToolRouteDecision(log, "runToolLoop", toolRound,
                    userIntent, expectedNextStep, routeDecision, allowAllMutations);

            if (!allParsed.isEmpty()) {
                // progress: LLM 选择了工具
                for (ParsedToolCall parsed : allParsed) {
                    progressSink.onProgress(AgentProgressEvent.toolSelected(
                            toolRound, maxToolRounds, parsed.tool));
                }

                // === 检查 finish_action 是否与其他工具同轮混用 ===
                boolean hasFinishAction = allParsed.stream().anyMatch(p -> "finish_action".equals(p.tool));
                boolean hasOtherTool = allParsed.stream().anyMatch(p -> !"finish_action".equals(p.tool));
                if (hasFinishAction && hasOtherTool) {
                    // 不执行任何工具，直接打回模型重写
                    String toolsList = allParsed.stream()
                            .map(ParsedToolCall::tool)
                            .collect(java.util.stream.Collectors.joining(", "));
                    progressSink.onProgress(AgentProgressEvent.finishRejected(
                            toolRound, maxToolRounds, "FINISH_ACTION_WITH_OTHER_TOOLS"));
                    String rejection = "[系统] finish_action 必须是本轮唯一工具调用。"
                            + "检测到同时包含 finish_action 和其他工具（" + toolsList + "）。"
                            + "请先单独执行非 finish_action 工具，收到工具结果后，再在下一轮单独调用 finish_action。"
                            + "\n禁止: [tool_a, finish_action]"
                            + "\n正确: 第一轮 [tool_a] → 第二轮 [finish_action]";
                    messages.add(LlmMessage.system(rejection));
                    trace.add(new MessageTrace("system", "finish_rejected",
                            "FINISH_ACTION_WITH_OTHER_TOOLS"));
                    ToolLoopDebug.logFinishActionWithOtherToolsRejected(log,
                            "runToolLoop", toolRound, allParsed);
                    toolRound++;
                    continue;
                }

                consecutiveNoToolRounds = 0;
                consecutiveInvalidToolIntent = 0;
                int toolsBefore = toolCalls.size();
                boolean finishActionSeen = false;
                String finishMessage = null;
                String finishStatus = null;

                for (ParsedToolCall parsed : allParsed) {
                    String callSource = toolSource == ToolLoopDebug.ToolCallSource.API_TOOL_CALLS
                            ? "API_TOOL_CALLS" : "TEXT_FALLBACK";
                    trace.add(new MessageTrace("tool", "tool_call", parsed.tool + " " + parsed.args));
                    ToolLoopDebug.logToolCall(log, "runToolLoop", toolRound,
                            parsed.tool, parsed.args.toString(), callSource);

                    // progress: 正在执行工具
                    progressSink.onProgress(AgentProgressEvent.toolExecuting(
                            toolRound, maxToolRounds, parsed.tool));

                    // === 执行前门禁：路由 + 分类 + 确认 ===
                    ToolExecutionDecision execDecision = executionPolicy.validateBeforeExecute(
                            parsed.tool, parsed.args, routeDecision,
                            expectedNextStep, allowAllMutations);
                    ToolLoopDebug.logToolExecutionPolicy(log, "runToolLoop", toolRound,
                            parsed.tool, execDecision);

                    if (execDecision.decision() == ToolExecutionDecisionType.REJECT) {
                        String reprompt = executionPolicy.buildRejectionReprompt(
                                parsed.tool, execDecision, routeDecision);
                        messages.add(LlmMessage.user(reprompt));
                        trace.add(new MessageTrace("system", "tool_rejected",
                                execDecision.reason()));
                        progressSink.onProgress(AgentProgressEvent.toolFailed(
                                toolRound, maxToolRounds, parsed.tool,
                                "REJECTED: " + execDecision.reason()));
                        continue;
                    }

                    if (execDecision.decision() == ToolExecutionDecisionType.NEED_CONFIRMATION) {
                        if (permissionGate != null) {
                            ToolConfirmationRequest confirmReq = new ToolConfirmationRequest(
                                    parsed.tool, execDecision.category(),
                                    execDecision.reason(), parsed.args,
                                    contextMeta != null ? contextMeta.activeBranch() : null);
                            ConfirmationChoice choice = permissionGate.askConfirmation(confirmReq);
                            ToolLoopDebug.logToolPermissionDecision(log, "runToolLoop",
                                    toolRound, parsed.tool, choice);

                            if (choice == ConfirmationChoice.DENY) {
                                String denyMsg = executionPolicy.buildDenyStopMessage(parsed.tool);
                                messages.add(LlmMessage.user(denyMsg));
                                trace.add(new MessageTrace("system", "tool_denied",
                                        "tool=" + parsed.tool + " choice=DENY"));
                                finalText = denyMsg;
                                break;
                            }
                            if (choice == ConfirmationChoice.ALLOW_ALL_THIS_TURN) {
                                allowAllMutations = true;
                            }
                        }
                    }

                    ToolCall call = new ToolCall(parsed.tool, parsed.args);
                    ToolResult result = toolRegistry.call(call);
                    toolCalls.add(new ToolCallRecord(parsed.tool, parsed.args, result));

                    // progress: 工具结果
                    if (result.success()) {
                        progressSink.onProgress(AgentProgressEvent.toolSuccess(
                                toolRound, maxToolRounds, parsed.tool));
                    } else {
                        progressSink.onProgress(AgentProgressEvent.toolFailed(
                                toolRound, maxToolRounds, parsed.tool, result.error()));
                    }

                    messages.add(LlmMessage.user(buildToolResultFeedback(parsed.tool, result)));
                    trace.add(new MessageTrace("tool", "tool_result",
                            "tool=" + parsed.tool + " success=" + result.success()));
                    ToolLoopDebug.logToolResult(log, "runToolLoop", toolRound,
                            parsed.tool, result.success(), result.error(),
                            result.success() ? summarizeToolResult(result) : null,
                            null);

                    if ("finish_action".equals(parsed.tool) && result.success()) {
                        finishActionSeen = true;
                        for (ToolResult.Item item : result.items()) {
                            finishMessage = item.snippet();
                            finishStatus = item.title();
                            break;
                        }
                    }
                }

                // 用户拒绝了确认 → finalText 已设置，直接结束 while 循环
                if (finalText != null) {
                    break;
                }

                if (finishActionSeen && finishMessage != null) {
                    ToolLoopDebug.logFinishAction(log, "runToolLoop", toolRound,
                            finishStatus, finishMessage,
                            finishMessage != null ? finishMessage.length() : 0);

                    String validationError = validateFinishActionMessage(finishMessage);
                    if (validationError != null) {
                        // progress: finish_action 被拒绝
                        progressSink.onProgress(AgentProgressEvent.finishRejected(
                                toolRound, maxToolRounds,
                                reasonFromMessageError(validationError)));
                        messages.add(LlmMessage.user(validationError));
                        trace.add(new MessageTrace("system", "finish_rejected", validationError));
                        ToolLoopDebug.logFinishAccepted(log, "runToolLoop", toolRound,
                                false, reasonFromMessageError(validationError),
                                null, null, null);
                        toolRound++;
                        continue;
                    }
                    String claimError = validateFinishActionClaims(finishMessage, toolCalls);
                    if (claimError != null) {
                        // progress: finish_action 声明被拒绝
                        progressSink.onProgress(AgentProgressEvent.finishRejected(
                                toolRound, maxToolRounds, "CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT"));
                        messages.add(LlmMessage.user(claimError));
                        trace.add(new MessageTrace("system", "finish_rejected", claimError));
                        ToolLoopDebug.logFinishAccepted(log, "runToolLoop", toolRound,
                                false, "CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT",
                                extractClaimFromError(claimError),
                                null,
                                successToolNames(toolCalls));
                        toolRound++;
                        continue;
                    }
                    finalText = finishMessage;
                    trace.add(new MessageTrace("assistant", "chat_response", finalText));
                    ToolLoopDebug.logFinishAccepted(log, "runToolLoop", toolRound,
                            true, null, null, null, null);
                    ToolLoopDebug.logFinalText(log, "runToolLoop", "finish_action", finalText);
                    lastAssistantDraft.set(finalText);
                    return new ChatResult(true, finalText, toolCalls, trace, null);
                }

                if (toolCalls.size() > toolsBefore) {
                    toolRound++;
                    continue;
                }
            }

            // === 无工具调用处理 ===
            if (toolSource == ToolLoopDebug.ToolCallSource.INVALID_TOOL_INTENT) {
                // progress: 非法工具调用格式
                progressSink.onProgress(AgentProgressEvent.invalidBracketIntent(
                        toolRound, maxToolRounds));
                // 非法工具意图：打回重写，不计入连续无工具轮
                consecutiveInvalidToolIntent++;
                ToolLoopDebug.logInvalidToolIntent(log, "runToolLoop", toolRound,
                        consecutiveInvalidToolIntent, content);

                if (consecutiveInvalidToolIntent >= 2) {
                    String errMsg = ToolLoopDebug.invalidToolIntentAbortError(
                            consecutiveInvalidToolIntent);
                    trace.add(new MessageTrace("system", "error", errMsg));
                    ToolLoopDebug.logNoToolAbort(log, "runToolLoop", toolRound,
                            "consecutive_invalid_tool_intent_exceeded",
                            consecutiveInvalidToolIntent);
                    ToolLoopDebug.logFinalText(log, "runToolLoop", "invalid_tool_intent_abort", errMsg);
                    return new ChatResult(false, errMsg, toolCalls, trace,
                            "Agent produced invalid tool intent for "
                                    + consecutiveInvalidToolIntent + " consecutive rounds");
                }

                String reprompt = ToolLoopDebug.buildInvalidToolIntentReprompt();
                messages.add(LlmMessage.user(reprompt));
                trace.add(new MessageTrace("system", "invalid_tool_intent",
                        "reprompting with correction"));
                toolRound++;
                continue;
            }

            // 普通无工具轮
            consecutiveInvalidToolIntent = 0;

            // === 普通无工具轮（forced finish_action 已在上面 auto-wrap 处理）===
            {
                consecutiveNoToolRounds++;
                ToolLoopDebug.logNoToolRound(log, "runToolLoop", toolRound, consecutiveNoToolRounds);

                // finish intent detection: 模型是否想结束但没合法 finish_action?
                var finishIntent = ToolLoopDebug.detectFinishIntent(
                        apiToolCallCount, textFallbackCount, false,
                        strippedForDraft, false);
                if (finishIntent == ToolLoopDebug.FinishIntent.PLAIN_ANSWER_WITHOUT_FINISH) {
                    progressSink.onProgress(AgentProgressEvent.plainAnswerWithoutFinish(
                            toolRound, maxToolRounds));
                }

                // 第一轮无工具 → 触发 forced finish_action
                if (!forcedFinishAction && consecutiveNoToolRounds == 1 && content != null && !content.isBlank()) {
                    forcedFinishAction = true;
                    lastPlainContent = content.strip();
                    log.debug("[TOOL_LOOP_TRACE] round={} plain text detected, enabling forced finish_action nextRound={}",
                            toolRound, toolRound + 1);

                    String reminder = "你刚才生成了普通答复，但没有调用 finish_action，因此该答复没有展示给用户。\n"
                            + "下一轮系统只允许你调用 finish_action。\n"
                            + "必须把完整最终答复放入 finish_action.message。\n"
                            + "禁止使用“以上”“如上”“刚才”“已生成”“前文”等引用不可见内容的词语。\n"
                            + "上一轮的答复内容如下，请改写为自包含的 finish_action.message：\n\n"
                            + lastPlainContent;
                    messages.add(LlmMessage.user(reminder));
                    trace.add(new MessageTrace("system", "forced_finish_action",
                            "plain text detected, forcing finish_action next round"));
                    toolRound++;
                    continue;
                }

                if (consecutiveNoToolRounds >= 2) {
                    String errMsg = ToolLoopDebug.noToolAbortError(consecutiveNoToolRounds);
                    trace.add(new MessageTrace("system", "error", errMsg));
                    ToolLoopDebug.logNoToolAbort(log, "runToolLoop", toolRound,
                            "consecutive_no_tool_rounds_exceeded", consecutiveNoToolRounds);
                    ToolLoopDebug.logFinalText(log, "runToolLoop", "no_tool_abort", errMsg);
                    return new ChatResult(false, errMsg, toolCalls, trace,
                            "Agent produced no tool calls for " + consecutiveNoToolRounds
                                    + " consecutive rounds");
                }

                String reminder = ToolLoopDebug.buildNoToolReminder(
                        lastUserMessage(messages));
                messages.add(LlmMessage.user(reminder));
                trace.add(new MessageTrace("system", "finish_required",
                        "no tool call found, reminding to use finish_action"));
                toolRound++;
                continue;
            }
        }

        if (finalText == null) {
            String errMsg = "[系统错误] Agent 未调用 finish_action，本轮未能正常结束。"
                    + "请重试或检查 prompt/tool loop。";
            trace.add(new MessageTrace("system", "error", errMsg));
            ToolLoopDebug.logFinalText(log, "runToolLoop", "max_rounds_error", errMsg);
            return new ChatResult(false, errMsg, toolCalls, trace,
                    "Agent did not call finish_action within " + maxToolRounds + " rounds");
        }

        if (hasFakeBracketToolResult(finalText, toolCalls)) {
            log.warn("MODEL_FAKE_TOOL_RESULT detected in chat — stripping fabricated tool output");
            finalText = "[系统提示] 检测到模型伪造的工具结果（未经过真实工具执行），以下内容已过滤。\n\n"
                    + stripFakeBracketToolResult(finalText);
        }
        finalText = stripRawToolJson(finalText);
        finalText = guardSuccessClaimWithoutToolBacking(finalText, toolCalls, toolRound);

        lastAssistantDraft.set(finalText);

        ToolLoopDebug.logFinalText(log, "runToolLoop", "finish_action", finalText);
        return new ChatResult(true, finalText, toolCalls, trace, null);
    }

    /** 公共 tool-loop（sim 模式）。 */
    private SimResult runSimToolLoop(List<LlmMessage> messages,
                                      List<MessageTrace> trace,
                                      List<ToolCallRecord> toolCalls,
                                      AgentContextMeta contextMeta,
                                      String userText) {
        String finalText = null;
        int toolRound = 1;
        int consecutiveNoToolRounds = 0;
        int consecutiveInvalidToolIntent = 0;
        List<ToolDef> toolDefs = buildToolDefs();

        while (toolRound <= maxToolRounds) {
            // context load debug
            ToolLoopDebug.logContextLoad(log, "runSimToolLoop", toolRound, messages, toolDefs, contextMeta);

            // task brief
            String lastToolName = !toolCalls.isEmpty()
                    ? toolCalls.get(toolCalls.size() - 1).tool() : null;
            boolean lastToolSuccess = !toolCalls.isEmpty()
                    && toolCalls.get(toolCalls.size() - 1).result().success();
            boolean hasToolResult = !toolCalls.isEmpty();
            java.util.List<String> expectedTools = toolRound == 1
                    ? java.util.List.of() : java.util.List.of("finish_action");
            ToolLoopDebug.logTaskBrief(log, "runSimToolLoop", toolRound, userText,
                    lastToolName, lastToolSuccess, hasToolResult, expectedTools);

            // progress: 上下文加载完毕
            int totalMessageChars = 0;
            for (var m : messages) { if (m.content() != null) totalMessageChars += m.content().length(); }
            int toolsJsonChars = ToolLoopDebug.estimateToolsJsonChars(toolDefs);
            progressSink.onProgress(AgentProgressEvent.contextLoaded(toolRound, maxToolRounds,
                    totalMessageChars + toolsJsonChars, toolDefs.size()));

            // progress: 等待 LLM
            progressSink.onProgress(AgentProgressEvent.waitingLlm(toolRound, maxToolRounds));

            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048, toolDefs);
            LlmResponse response = callLlm(request);

            if (!response.success()) {
                String err = "LLM 调用失败: " + response.errorMessage();
                trace.add(new MessageTrace("system", "error", err));
                return new SimResult(err, toolCalls, trace);
            }

            String content = response.content();
            int apiToolCallCount = response.hasApiToolCalls() ? response.toolCalls().size() : 0;
            String finishReason = response.finishReason();

            messages.add(LlmMessage.assistant(content != null ? content : ""));

            ToolLoopDebug.logLlmResponse(log, "runSimToolLoop", toolRound, content,
                    apiToolCallCount, finishReason);

            // 缓存 draft
            String strippedForDraft = toCleanDraft(content);
            if (strippedForDraft != null && !strippedForDraft.isBlank()) {
                lastAssistantDraft.set(strippedForDraft);
            }
            ToolLoopDebug.logCleanedDraft(log, "runSimToolLoop", toolRound, strippedForDraft);

            // === 优先级 1: API 原生 tool_calls ===
            List<ParsedToolCall> allParsed;
            ToolLoopDebug.ToolCallSource toolSource;
            int textFallbackCount = 0;

            if (response.hasApiToolCalls()) {
                allParsed = fromApiToolCalls(response.toolCalls());
                toolSource = ToolLoopDebug.ToolCallSource.API_TOOL_CALLS;
            } else {
                allParsed = ToolCallExtractor.extractAllToolCalls(content);
                if (!allParsed.isEmpty()) {
                    toolSource = ToolLoopDebug.ToolCallSource.TEXT_FALLBACK;
                    textFallbackCount = allParsed.size();
                } else if (ToolLoopDebug.isInvalidToolIntent(content)) {
                    toolSource = ToolLoopDebug.ToolCallSource.INVALID_TOOL_INTENT;
                    allParsed = List.of();
                } else {
                    toolSource = ToolLoopDebug.ToolCallSource.NONE;
                }
            }

            ToolLoopDebug.logToolExtraction(log, "runSimToolLoop", toolRound, allParsed,
                    apiToolCallCount, textFallbackCount, toolSource, content);

            if (!allParsed.isEmpty()) {
                // progress: LLM 选择了工具
                for (ParsedToolCall parsed : allParsed) {
                    progressSink.onProgress(AgentProgressEvent.toolSelected(
                            toolRound, maxToolRounds, parsed.tool));
                }

                // === 检查 finish_action 是否与其他工具同轮混用 ===
                boolean hasFinishActionSim = allParsed.stream().anyMatch(p -> "finish_action".equals(p.tool));
                boolean hasOtherToolSim = allParsed.stream().anyMatch(p -> !"finish_action".equals(p.tool));
                if (hasFinishActionSim && hasOtherToolSim) {
                    String toolsListSim = allParsed.stream()
                            .map(ParsedToolCall::tool)
                            .collect(java.util.stream.Collectors.joining(", "));
                    progressSink.onProgress(AgentProgressEvent.finishRejected(
                            toolRound, maxToolRounds, "FINISH_ACTION_WITH_OTHER_TOOLS"));
                    String rejectionSim = "[系统] finish_action 必须是本轮唯一工具调用。"
                            + "检测到同时包含 finish_action 和其他工具（" + toolsListSim + "）。"
                            + "请先单独执行非 finish_action 工具，收到工具结果后，再在下一轮单独调用 finish_action。"
                            + "\n禁止: [tool_a, finish_action]"
                            + "\n正确: 第一轮 [tool_a] → 第二轮 [finish_action]";
                    messages.add(LlmMessage.system(rejectionSim));
                    trace.add(new MessageTrace("system", "finish_rejected",
                            "FINISH_ACTION_WITH_OTHER_TOOLS"));
                    ToolLoopDebug.logFinishActionWithOtherToolsRejected(log,
                            "runSimToolLoop", toolRound, allParsed);
                    toolRound++;
                    continue;
                }

                consecutiveNoToolRounds = 0;
                consecutiveInvalidToolIntent = 0;
                int toolsBefore = toolCalls.size();
                boolean finishActionSeen = false;
                String finishMessage = null;
                String finishStatus = null;

                for (ParsedToolCall parsed : allParsed) {
                    String callSource = toolSource == ToolLoopDebug.ToolCallSource.API_TOOL_CALLS
                            ? "API_TOOL_CALLS" : "TEXT_FALLBACK";
                    trace.add(new MessageTrace("tool", "tool_call", parsed.tool + " " + parsed.args));
                    ToolLoopDebug.logToolCall(log, "runSimToolLoop", toolRound,
                            parsed.tool, parsed.args.toString(), callSource);

                    // progress: 正在执行工具
                    progressSink.onProgress(AgentProgressEvent.toolExecuting(
                            toolRound, maxToolRounds, parsed.tool));

                    ToolCall call = new ToolCall(parsed.tool, parsed.args);
                    ToolResult result = toolRegistry.call(call);
                    toolCalls.add(new ToolCallRecord(parsed.tool, parsed.args, result));

                    // progress: 工具结果
                    if (result.success()) {
                        progressSink.onProgress(AgentProgressEvent.toolSuccess(
                                toolRound, maxToolRounds, parsed.tool));
                    } else {
                        progressSink.onProgress(AgentProgressEvent.toolFailed(
                                toolRound, maxToolRounds, parsed.tool, result.error()));
                    }

                    messages.add(LlmMessage.user(buildToolResultFeedback(parsed.tool, result)));
                    trace.add(new MessageTrace("tool", "tool_result",
                            "tool=" + parsed.tool + " success=" + result.success()));
                    ToolLoopDebug.logToolResult(log, "runSimToolLoop", toolRound,
                            parsed.tool, result.success(), result.error(),
                            result.success() ? summarizeToolResult(result) : null,
                            null);

                    if ("finish_action".equals(parsed.tool) && result.success()) {
                        finishActionSeen = true;
                        for (ToolResult.Item item : result.items()) {
                            finishMessage = item.snippet();
                            finishStatus = item.title();
                            break;
                        }
                    }
                }

                if (finishActionSeen && finishMessage != null) {
                    ToolLoopDebug.logFinishAction(log, "runSimToolLoop", toolRound,
                            finishStatus, finishMessage,
                            finishMessage != null ? finishMessage.length() : 0);

                    String validationError = validateFinishActionMessage(finishMessage);
                    if (validationError != null) {
                        // progress: finish_action 被拒绝
                        progressSink.onProgress(AgentProgressEvent.finishRejected(
                                toolRound, maxToolRounds, reasonFromMessageError(validationError)));
                        messages.add(LlmMessage.user(validationError));
                        trace.add(new MessageTrace("system", "finish_rejected", validationError));
                        ToolLoopDebug.logFinishAccepted(log, "runSimToolLoop", toolRound,
                                false, reasonFromMessageError(validationError),
                                null, null, null);
                        toolRound++;
                        continue;
                    }
                    String claimError = validateFinishActionClaims(finishMessage, toolCalls);
                    if (claimError != null) {
                        // progress: finish_action 声明被拒绝
                        progressSink.onProgress(AgentProgressEvent.finishRejected(
                                toolRound, maxToolRounds, "CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT"));
                        messages.add(LlmMessage.user(claimError));
                        trace.add(new MessageTrace("system", "finish_rejected", claimError));
                        ToolLoopDebug.logFinishAccepted(log, "runSimToolLoop", toolRound,
                                false, "CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT",
                                extractClaimFromError(claimError),
                                null,
                                successToolNames(toolCalls));
                        toolRound++;
                        continue;
                    }
                    finalText = finishMessage;
                    trace.add(new MessageTrace("assistant", "sim_output", finalText));
                    ToolLoopDebug.logFinishAccepted(log, "runSimToolLoop", toolRound,
                            true, null, null, null, null);
                    ToolLoopDebug.logFinalText(log, "runSimToolLoop", "finish_action", finalText);
                    lastAssistantDraft.set(finalText);
                    return new SimResult(finalText, toolCalls, trace);
                }

                if (toolCalls.size() > toolsBefore) {
                    toolRound++;
                    continue;
                }
            }

            // === 无工具调用处理 ===
            if (toolSource == ToolLoopDebug.ToolCallSource.INVALID_TOOL_INTENT) {
                consecutiveInvalidToolIntent++;
                ToolLoopDebug.logInvalidToolIntent(log, "runSimToolLoop", toolRound,
                        consecutiveInvalidToolIntent, content);

                if (consecutiveInvalidToolIntent >= 2) {
                    String errMsg = ToolLoopDebug.invalidToolIntentAbortError(
                            consecutiveInvalidToolIntent);
                    trace.add(new MessageTrace("system", "error", errMsg));
                    ToolLoopDebug.logNoToolAbort(log, "runSimToolLoop", toolRound,
                            "consecutive_invalid_tool_intent_exceeded",
                            consecutiveInvalidToolIntent);
                    ToolLoopDebug.logFinalText(log, "runSimToolLoop",
                            "invalid_tool_intent_abort", errMsg);
                    return new SimResult(errMsg, toolCalls, trace);
                }

                String reprompt = ToolLoopDebug.buildInvalidToolIntentReprompt();
                messages.add(LlmMessage.user(reprompt));
                trace.add(new MessageTrace("system", "invalid_tool_intent",
                        "reprompting with correction"));
                toolRound++;
                continue;
            }

            // 普通无工具轮
            consecutiveNoToolRounds++;
            consecutiveInvalidToolIntent = 0;
            ToolLoopDebug.logNoToolRound(log, "runSimToolLoop", toolRound, consecutiveNoToolRounds);

            if (consecutiveNoToolRounds >= 2) {
                String errMsg = ToolLoopDebug.noToolAbortError(consecutiveNoToolRounds);
                trace.add(new MessageTrace("system", "error", errMsg));
                ToolLoopDebug.logNoToolAbort(log, "runSimToolLoop", toolRound,
                        "consecutive_no_tool_rounds_exceeded", consecutiveNoToolRounds);
                ToolLoopDebug.logFinalText(log, "runSimToolLoop", "no_tool_abort", errMsg);
                return new SimResult(errMsg, toolCalls, trace);
            }

            String reminder = ToolLoopDebug.buildNoToolReminder(
                    lastUserMessage(messages));
            messages.add(LlmMessage.user(reminder));
            trace.add(new MessageTrace("system", "finish_required",
                    "no tool call found, reminding to use finish_action"));
            toolRound++;
            continue;
        }

        if (finalText == null) {
            String errMsg = "[系统错误] Agent 未调用 finish_action，本轮未能正常结束。"
                    + "请重试或检查 prompt/tool loop。";
            trace.add(new MessageTrace("system", "error", errMsg));
            ToolLoopDebug.logFinalText(log, "runSimToolLoop", "max_rounds_error", errMsg);
            return new SimResult(errMsg, toolCalls, trace);
        }

        // 后处理（防御性：对已验证的 finish_action message 是 no-op）
        if (hasFakeBracketToolResult(finalText, toolCalls)) {
            log.warn("MODEL_FAKE_TOOL_RESULT detected in sim — stripping fabricated tool output");
            finalText = "[系统提示] 检测到模型伪造的工具结果（未经过真实工具执行），以下内容已过滤。\n\n"
                    + stripFakeBracketToolResult(finalText);
        }
        finalText = stripRawToolJson(finalText);
        finalText = guardSuccessClaimWithoutToolBacking(finalText, toolCalls, toolRound);

        // 缓存草稿，供 turn_settlement_save_last_response 使用
        lastAssistantDraft.set(finalText);

        ToolLoopDebug.logFinalText(log, "runSimToolLoop", "finish_action", finalText);
        return new SimResult(finalText, toolCalls, trace);
    }

    // ---- Context History 配置 ----

    /**
     * 对话上下文历史配置。
     * @param historyTurns  最近保留轮数（1..50）
     * @param messageMaxChars 单条消息最大字符数（500..20000）
     */
    public record ContextHistoryConfig(int historyTurns, int messageMaxChars) {
        public static final ContextHistoryConfig DEFAULT = new ContextHistoryConfig(12, 4000);
    }

    /**
     * 按轮数过滤 sessionMessage 列表，保留最近 N 轮。
     * 一轮按 user/chat_user 消息切分；每个 user 消息到下一 user 消息之间为一轮。
     * 保留完整轮内容（含 assistant / tool_call / tool_result），不截断轮内 tool 链。
     */
    public static List<SessionMessage> filterByTurns(List<SessionMessage> messages, int maxTurns) {
        if (messages == null || messages.isEmpty()) return List.of();
        if (maxTurns <= 0) return List.of();

        // 从后向前扫描 user 消息，找到第 maxTurns 个
        int userCount = 0;
        int cutoffIdx = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            SessionMessage msg = messages.get(i);
            if ("user".equals(msg.role())) {
                userCount++;
                if (userCount >= maxTurns) {
                    cutoffIdx = i;
                    break;
                }
            }
        }

        return messages.subList(cutoffIdx, messages.size());
    }
}
