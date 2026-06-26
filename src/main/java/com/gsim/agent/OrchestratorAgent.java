package com.gsim.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.compact.ToolResultCompactor;
import com.gsim.llm.LlmCall;
import com.gsim.llm.LlmManager;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResult;
import com.gsim.llm.LlmToolCall;
import com.gsim.llm.StreamPool;
import com.gsim.llm.ToolDef;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.gsim.agent.core.AbstractAgent;
import com.gsim.agent.core.AgentConfig;
import com.gsim.agent.core.AgentFactory;
import com.gsim.agent.core.AgentResult;
import com.gsim.agent.tool.DispatchSubAgentTool;
import com.gsim.agent.tool.CollectSubAgentResultsTool;

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
public class OrchestratorAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmManager llmManager;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final AgentProgressSink progressSink;
    private final ToolRoutePolicy routePolicy;
    private final ToolExecutionPolicy executionPolicy;
    private final ToolPermissionGate permissionGate;
    private final ToolPermissionConfig permissionConfig;
    private final ToolGroupManager groupManager;

    /** Agent ToolLoop 最大工具轮数（默认 32，≥1，可由 setter 注入覆盖）。 */
    private volatile int maxToolRounds = 64;

    /** LLM 流式输出开关（由 AppConfig 注入，默认 false）。 */
    private volatile boolean streamEnabled = false;

    /** 工具结果溢出保护（null = 未启用）。 */
    private volatile ToolResultCompactor toolResultCompactor;
    /** 工具结果压缩阈值（字符数，默认 3000）。 */
    private volatile int toolResultThreshold = 3000;

    /** SubAgent 异步结果收集（agentId → future）。 */
    private final Map<String, CompletableFuture<AgentResult>> runningSubAgents = new ConcurrentHashMap<>();
    /** SubAgent ID 计数器。 */
    private final AtomicInteger subAgentCounter = new AtomicInteger(0);
    /** AgentFactory 引用（用于 ESC 时取消所有子代理）。 */
    private AgentFactory agentFactory;

    /** 返回工具组管理器（供 NodeAgentChatService 和测试使用）。 */
    public ToolGroupManager groupManager() {
        return groupManager;
    }

    /**
     * 注册子代理派发/收集工具到 ToolRegistry。
     * 由 GSimulatorApplication 在构造后调用。
     */
    public void registerSubAgentTools(ToolRegistry registry, AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
        registry.register(new DispatchSubAgentTool(
                llmManager, toolRegistry, model, progressSink,
                runningSubAgents, subAgentCounter, agentFactory,
                agentFactory.store()));
        registry.register(new CollectSubAgentResultsTool(runningSubAgents));
    }

    public OrchestratorAgent(LlmManager llmManager, ToolRegistry toolRegistry, String model) {
        this(llmManager, toolRegistry, model, AgentProgressSink.NOOP);
    }

    public OrchestratorAgent(LlmManager llmManager, ToolRegistry toolRegistry, String model,
                             AgentProgressSink progressSink) {
        this(llmManager, toolRegistry, model, progressSink, null);
    }

    public OrchestratorAgent(LlmManager llmManager, ToolRegistry toolRegistry, String model,
                             AgentProgressSink progressSink,
                             ToolPermissionGate permissionGate) {
        this(llmManager, toolRegistry, model, progressSink != null ? progressSink : AgentProgressSink.NOOP,
                new ToolRoutePolicy(), new ToolExecutionPolicy(),
                permissionGate, new ToolPermissionConfig(),
                ToolGroupManager.createWithAllGroupsActivated());
    }

    public OrchestratorAgent(LlmManager llmManager, ToolRegistry toolRegistry, String model,
                             AgentProgressSink progressSink,
                             ToolPermissionGate permissionGate,
                             ToolGroupManager groupManager) {
        this(llmManager, toolRegistry, model, progressSink != null ? progressSink : AgentProgressSink.NOOP,
                new ToolRoutePolicy(), new ToolExecutionPolicy(),
                permissionGate, new ToolPermissionConfig(),
                groupManager);
    }

    OrchestratorAgent(LlmManager llmManager, ToolRegistry toolRegistry, String model,
                       AgentProgressSink progressSink,
                       ToolRoutePolicy routePolicy,
                       ToolExecutionPolicy executionPolicy,
                       ToolPermissionGate permissionGate,
                       ToolPermissionConfig permissionConfig) {
        this(llmManager, toolRegistry, model, progressSink, routePolicy, executionPolicy,
                permissionGate, permissionConfig, ToolGroupManager.createWithAllGroupsActivated());
    }

    OrchestratorAgent(LlmManager llmManager, ToolRegistry toolRegistry, String model,
                       AgentProgressSink progressSink,
                       ToolRoutePolicy routePolicy,
                       ToolExecutionPolicy executionPolicy,
                       ToolPermissionGate permissionGate,
                       ToolPermissionConfig permissionConfig,
                       ToolGroupManager groupManager) {
        super(AgentConfig.defaultOrchestrator(), llmManager, toolRegistry,
                progressSink != null ? progressSink : AgentProgressSink.NOOP, model);
        this.llmManager = llmManager;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.progressSink = progressSink != null ? progressSink : AgentProgressSink.NOOP;
        this.routePolicy = routePolicy != null ? routePolicy : new ToolRoutePolicy();
        this.executionPolicy = executionPolicy != null ? executionPolicy : new ToolExecutionPolicy();
        this.permissionGate = permissionGate;
        this.permissionConfig = permissionConfig != null ? permissionConfig : new ToolPermissionConfig();
        this.groupManager = groupManager != null ? groupManager : new ToolGroupManager();
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

    /** 覆盖基类方法，返回注入的 maxToolRounds 而非 config 默认值。 */
    @Override
    protected int effectiveMaxToolRounds() {
        return maxToolRounds;
    }

    /** 设置是否使用 LLM 流式输出（由 AppConfig 注入）。 */
    public void setStreamEnabled(boolean streamEnabled) {
        this.streamEnabled = streamEnabled;
    }

    /** 获取流式输出开关。 */
    public boolean isStreamEnabled() {
        return streamEnabled;
    }

    /** 注入工具结果压缩器（null = 不启用工具结果溢出保护）。 */
    public void setToolResultCompactor(ToolResultCompactor compactor) {
        this.toolResultCompactor = compactor;
    }

    /** 设置工具结果压缩阈值（字符数，默认 3000）。 */
    public void setToolResultThreshold(int threshold) {
        this.toolResultThreshold = Math.max(500, threshold);
    }

    /** 取消当前 ToolLoop 及所有正在运行的 SubAgent（ESC / Ctrl+C）。 */
    @Override
    public void cancel() {
        super.cancel();
        // 取消所有子代理：直接设置 cancelRequested 标志（比 future.cancel 更可靠）
        if (agentFactory != null) {
            agentFactory.cancelAll();
        }
    }

    // ══════════════════════════════════════════
    // 工具执行钩子 — 权限门禁（fail-closed）
    // ══════════════════════════════════════════

    @Override
    protected boolean beforeToolExecute(ParsedToolCall parsed, List<LlmMessage> messages) {
        String toolName = parsed.tool();

        // 权限门禁：mutating/destructive 工具必须经过 permissionGate
        ToolCategory category = ToolCategoryRegistry.categoryOf(toolName);
        if (category == ToolCategory.MUTATING || category == ToolCategory.DESTRUCTIVE) {
            if (permissionGate == null) {
                String rejectMsg = "[系统] 工具 " + toolName
                        + " 需要用户确认（" + category + "），但当前未配置权限门禁。操作被拒绝。"
                        + "请改用只读工具或调用 finish_action 结束本轮。";
                messages.add(LlmMessage.user(rejectMsg));
                progressSink.onProgress(AgentProgressEvent.toolFailed(0, effectiveMaxToolRounds(),
                        toolName, "REJECTED: no permission gate"));
                return false;
            }
            // gate exists — ask user (blocking)
            ToolConfirmationRequest confirmReq = new ToolConfirmationRequest(
                    toolName, category,
                    category == ToolCategory.DESTRUCTIVE
                            ? "破坏性操作: " + toolName : "写入操作: " + toolName,
                    parsed.args(), null);
            ConfirmationChoice choice = permissionGate.askConfirmation(confirmReq);
            if (choice == ConfirmationChoice.DENY) {
                String denyMsg = "[系统] 用户拒绝了工具 " + toolName + "，本轮已停止。";
                messages.add(LlmMessage.user(denyMsg));
                return false;
            }
            // ALLOW or ALLOW_ALL_THIS_TURN → proceed
        }
        return true;
    }

    /**
     * 调用 LLM — 根据 streamEnabled 配置选择流式或非流式路径。
     *
     * <p>流式路径：通过 {@link LlmManager#submit} 异步提交，轮询 {@link StreamPool}
     * 同时将 delta 作为 {@link AgentProgressEvent} 发送给 progressSink（CLI 灰框预览）。
     * 流式结束后通过 {@link LlmCall#await} 获取完整 {@link LlmResult}，语义与 chat() 完全一致。
     *
     * <p>非流式路径：直接调用 {@link LlmManager#chat}。
     */
    protected LlmResult callLlm(LlmRequest request) {
        int requestTools = request.tools() != null ? request.tools().size() : 0;
        log.debug("[ORCH_STREAM] streamEnabled={} requestTools={}", streamEnabled, requestTools);

        if (!streamEnabled) {
            return llmManager.chat(request);
        }

        // 流式路径：submit → 轮询 pool → await 结果
        LlmCall call = llmManager.submit(request);
        StreamPool pool = call.pool();
        String streamId = pool.streamId();

        progressSink.onProgress(AgentProgressEvent.llmStreamStarted(streamId));

        String lastContent = "";
        String lastReasoning = "";
        try {
            while (!pool.isComplete()) {
                String content = pool.getContent();
                if (!content.equals(lastContent)) {
                    // 发送增量 delta
                    String delta = content.substring(lastContent.length());
                    lastContent = content;
                    if (!delta.isEmpty()) {
                        progressSink.onProgress(AgentProgressEvent.llmContentDelta(streamId, delta));
                    }
                }
                String reasoning = pool.getReasoning();
                if (!reasoning.equals(lastReasoning)) {
                    String delta = reasoning.substring(lastReasoning.length());
                    lastReasoning = reasoning;
                    if (!delta.isEmpty()) {
                        progressSink.onProgress(AgentProgressEvent.llmReasoningDelta(streamId, delta));
                    }
                }
                // yield to background thread
                Thread.sleep(50);

                // ESC 取消检查
                if (cancelRequested.get()) {
                    log.info("[ORCH_STREAM] cancelled by user (ESC)");
                    progressSink.onProgress(AgentProgressEvent.llmStreamFailed(streamId, "用户取消"));
                    return LlmResult.failure("cancelled");
                }
            }

            // pool 已完成 — 发送可能遗留的 delta（处理 pool 在 while 之前就已完成的情况）
            String content = pool.getContent();
            if (!content.equals(lastContent)) {
                String delta = content.substring(lastContent.length());
                if (!delta.isEmpty()) {
                    progressSink.onProgress(AgentProgressEvent.llmContentDelta(streamId, delta));
                }
            }
            String reasoning = pool.getReasoning();
            if (!reasoning.equals(lastReasoning)) {
                String delta = reasoning.substring(lastReasoning.length());
                if (!delta.isEmpty()) {
                    progressSink.onProgress(AgentProgressEvent.llmReasoningDelta(streamId, delta));
                }
            }

            LlmResult result = call.await(100); // pool 已完成，立即返回
            if (result.success()) {
                progressSink.onProgress(AgentProgressEvent.llmStreamCompleted(streamId));
            } else {
                progressSink.onProgress(AgentProgressEvent.llmStreamFailed(streamId,
                        result.errorMessage() != null ? result.errorMessage() : "unknown"));
            }

            // [STREAM_TRACE] 汇总
            log.info("[STREAM_TRACE] completed streamId={} contentDeltaEvents={} reasoningDeltaEvents={}"
                            + " toolCallDeltaEvents={} finalContentChars={} responseToolCalls={}",
                    streamId,
                    pool.eventCount(StreamPool.EventType.CONTENT),
                    pool.eventCount(StreamPool.EventType.REASONING),
                    pool.eventCount(StreamPool.EventType.TOOL_CALL_DELTA),
                    result.content() != null ? result.content().length() : 0,
                    result.hasApiToolCalls() ? result.toolCalls().size() : 0);

            return result;
        } catch (Exception e) {
            log.error("LLM stream call failed: {}", e.getMessage(), e);
            progressSink.onProgress(AgentProgressEvent.llmStreamFailed(streamId, e.getMessage()));
            return LlmResult.failure(e.getMessage());
        }
    }

    /**
     * 从 ToolGroupManager 生成工具组目录 prompt（含组名、描述、成员工具、激活说明）。
     */


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
    protected static String validateFinishActionMessage(String message) {
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
    /** LLM 有充分自主权 — 不质疑 finish_action 中的成功宣称。始终返回 null。 */
    static String validateFinishActionClaims(String message, List<ToolCallRecord> toolCalls) {
        return null;
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

    /** 按允许集过滤 ToolDef 列表，保留 finish_action 兜底。未归组工具（如测试自定义工具）始终包含。 */
    private static List<ToolDef> filterToolDefs(List<ToolDef> allDefs, java.util.Set<String> allowed) {
        List<ToolDef> filtered = new ArrayList<>();
        for (ToolDef def : allDefs) {
            if (allowed.contains(def.name()) || !ToolGroup.isKnownTool(def.name())) {
                filtered.add(def);
            }
        }
        // 兜底：至少保留 finish_action
        if (filtered.isEmpty()) {
            for (ToolDef def : allDefs) {
                if ("finish_action".equals(def.name())) {
                    filtered.add(def);
                    break;
                }
            }
        }
        return filtered;
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

    // ParsedToolCall moved to com.gsim.agent.ParsedToolCall (top-level public record)

    /**
     * 一次工具调用记录（含结果）。
     */
    public record ToolCallRecord(String tool, Map<String, String> args, ToolResult result) {}

    /**
     * 消息追踪记录。
     */
    public record MessageTrace(String role, String type, String content) {}

    /**
     * 基于已渲染的上下文执行推演。
     * @param contextMarkdown BranchContextRenderer 输出的完整 markdown
     * @param simNote 本轮 /sim 的推演备注
     */

    public record SimResult(String finalText, List<ToolCallRecord> toolCalls, List<MessageTrace> trace) {}

    // ---- chat mode ----

    /** 对话模式：不覆盖任何章节，只返回 LLM 回复。 */

    public record ChatResult(boolean success, String finalText, List<ToolCallRecord> toolCalls,
                              List<MessageTrace> trace, String errorMessage) {}

    static boolean isMeaningfulAssistantContent(String content) {
        if (content == null) return false;
        String t = content.strip();
        if (t.isEmpty()) return false;
        return switch (t) {
            case "null", "NULL", "Null",
                 "undefined", "UNDEFINED", "Undefined",
                 "JsonNull", "jsonNull", "JSONNULL",
                 "{}", "[]" -> false;
            default -> true;
        };
    }

    /** 公共 tool-loop（chat 模式）— v2 工具组路由。 */
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
        List<ToolDef> allToolDefs = buildToolDefs();

        while (toolRound <= maxToolRounds) {
            // ESC 取消检查
            if (cancelRequested.get()) {
                log.info("[ToolLoop] cancelled by user (ESC) at round {}", toolRound);
                return new ChatResult(false, "[已取消]", toolCalls, trace, "cancelled");
            }

            // 每轮动态计算当前允许的工具集（基于激活的工具组）
            java.util.Set<String> currentAllowedTools = groupManager.computeAllowedTools();
            List<ToolDef> roundToolDefs = filterToolDefs(allToolDefs, currentAllowedTools);
            Object roundToolChoice = "auto";

            // context load debug
            ToolLoopDebug.logContextLoad(log, "runToolLoop", toolRound, messages, roundToolDefs, contextMeta);

            log.debug("[TOOL_LOOP_TRACE] engine=runToolLoop round={} requestTools={} activeGroups={}",
                    toolRound, roundToolDefs.size(), groupManager.activeGroupKeys());

            // progress: 上下文加载完毕
            int totalMessageChars = 0;
            for (var m : messages) { if (m.content() != null) totalMessageChars += m.content().length(); }
            int toolsJsonChars = ToolLoopDebug.estimateToolsJsonChars(roundToolDefs);
            progressSink.onProgress(AgentProgressEvent.contextLoaded(toolRound, maxToolRounds,
                    totalMessageChars + toolsJsonChars, roundToolDefs.size()));

            // progress: 等待 LLM
            progressSink.onProgress(AgentProgressEvent.waitingLlm(toolRound, maxToolRounds));

            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048,
                    roundToolDefs, roundToolChoice);
            LlmResult response = callLlm(request);

            if (!response.success()) {
                if (response.isContextLengthExceeded()) {
                    String hint = "⚠️ 上下文窗口不足。建议执行 /compact 压缩对话历史。\n原始错误: " + response.errorMessage();
                    log.warn("[LLM] context length exceeded: {}", response.errorMessage());
                    return new ChatResult(false, hint, toolCalls, trace, response.errorMessage());
                }
                return new ChatResult(false, "", toolCalls, trace, response.errorMessage());
            }

            String content = response.content();
            int apiToolCallCount = response.hasApiToolCalls() ? response.toolCalls().size() : 0;
            String finishReason = response.finishReason();

            messages.add(LlmMessage.assistant(content != null ? content : ""));

            // DEBUG: LLM response preview
            ToolLoopDebug.logLlmResult(log, "runToolLoop", toolRound, content,
                    apiToolCallCount, finishReason);

            // 缓存 draft
            String strippedForDraft = toCleanDraft(content);
            if (strippedForDraft != null && !strippedForDraft.isBlank()) {
            }
            ToolLoopDebug.logCleanedDraft(log, "runToolLoop", toolRound, strippedForDraft);

            // === 优先级 1: API 原生 tool_calls ===
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

            if (!allParsed.isEmpty()) {
                // progress: LLM 选择了工具
                for (ParsedToolCall parsed : allParsed) {
                    progressSink.onProgress(AgentProgressEvent.toolSelected(
                            toolRound, maxToolRounds, parsed.tool()));
                }

                // === finish_action 混用检测：允许混用，但必须放在最末尾 ===
                int finishActionIdx = -1;
                for (int i = 0; i < allParsed.size(); i++) {
                    if ("finish_action".equals(allParsed.get(i).tool())) {
                        finishActionIdx = i;
                        break;
                    }
                }
                if (finishActionIdx >= 0 && finishActionIdx < allParsed.size() - 1) {
                    // finish_action 不是最后一个 → REJECT
                    String toolsList = allParsed.stream()
                            .map(ParsedToolCall::tool)
                            .collect(java.util.stream.Collectors.joining(", "));
                    progressSink.onProgress(AgentProgressEvent.finishRejected(
                            toolRound, maxToolRounds, "FINISH_ACTION_NOT_LAST"));
                    String rejection = "[系统] finish_action 必须出现在工具调用的最末尾。"
                            + "检测到 finish_action 后面还有工具调用（" + toolsList + "）。"
                            + "请把 finish_action 调到最后。"
                            + "\n正确: [other_tools..., finish_action]";
                    messages.add(LlmMessage.system(rejection));
                    trace.add(new MessageTrace("system", "finish_rejected",
                            "FINISH_ACTION_NOT_LAST"));
                    log.debug("[ToolLoop] finish_action not last in round={}, tools={}",
                            toolRound, toolsList);
                    toolRound++;
                    continue;
                }

                consecutiveNoToolRounds = 0;
                consecutiveInvalidToolIntent = 0;
                int toolsBefore = toolCalls.size();
                boolean finishActionSeen = false;
                String finishMessage = null;
                String finishStatus = null;
                String finishSummary = null;

                // 构建 routeDecision 用于执行策略校验
                ToolRouteDecision routeDecision = new ToolRouteDecision(
                        java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(currentAllowedTools)),
                        "GROUP_BASED",
                        "工具组路由：默认工具 + 已激活组");

                for (ParsedToolCall parsed : allParsed) {
                    String callSource = toolSource == ToolLoopDebug.ToolCallSource.API_TOOL_CALLS
                            ? "API_TOOL_CALLS" : "TEXT_FALLBACK";
                    trace.add(new MessageTrace("tool", "tool_call", parsed.tool() + " " + parsed.args()));
                    ToolLoopDebug.logToolCall(log, "runToolLoop", toolRound,
                            parsed.tool(), parsed.args().toString(), callSource);

                    // progress: 正在执行工具
                    progressSink.onProgress(AgentProgressEvent.toolExecuting(
                            toolRound, maxToolRounds, parsed.tool()));

                    // === 执行前门禁：路由 + 分类 + 确认 ===
                    ToolExecutionDecision execDecision = executionPolicy.validateBeforeExecute(
                            parsed.tool(), parsed.args(), routeDecision,
                            allowAllMutations);
                    ToolLoopDebug.logToolExecutionPolicy(log, "runToolLoop", toolRound,
                            parsed.tool(), execDecision);

                    if (execDecision.decision() == ToolExecutionDecisionType.REJECT) {
                        String reprompt = executionPolicy.buildRejectionReprompt(
                                parsed.tool(), execDecision, routeDecision);
                        messages.add(LlmMessage.user(reprompt));
                        trace.add(new MessageTrace("system", "tool_rejected",
                                execDecision.reason()));
                        progressSink.onProgress(AgentProgressEvent.toolFailed(
                                toolRound, maxToolRounds, parsed.tool(),
                                "REJECTED: " + execDecision.reason()));
                        continue;
                    }

                    if (execDecision.decision() == ToolExecutionDecisionType.NEED_CONFIRMATION) {
                        if (permissionGate == null) {
                            // Fail-closed: no gate configured → reject
                            String rejectMsg = "工具 " + parsed.tool()
                                    + " 需要用户确认，但当前未配置权限门禁（permissionGate）。"
                                    + "操作被拒绝。请调用 finish_action 结束本轮。";
                            messages.add(LlmMessage.user(rejectMsg));
                            trace.add(new MessageTrace("system", "tool_rejected",
                                    "NO_PERMISSION_GATE"));
                            progressSink.onProgress(AgentProgressEvent.toolFailed(
                                    toolRound, maxToolRounds, parsed.tool(),
                                    "REJECTED: no permission gate configured"));
                            continue;
                        }
                        progressSink.onProgress(AgentProgressEvent.awaitingToolConfirmation(
                                toolRound, maxToolRounds, parsed.tool()));

                        ToolConfirmationRequest confirmReq = new ToolConfirmationRequest(
                                parsed.tool(), execDecision.category(),
                                execDecision.reason(), parsed.args(),
                                contextMeta != null ? contextMeta.activeBranch() : null);
                        ConfirmationChoice choice = permissionGate.askConfirmation(confirmReq);
                        ToolLoopDebug.logToolPermissionDecision(log, "runToolLoop",
                                toolRound, parsed.tool(), choice);

                        if (choice == ConfirmationChoice.DENY) {
                            String denyMsg = executionPolicy.buildDenyStopMessage(parsed.tool());
                            messages.add(LlmMessage.user(denyMsg));
                            trace.add(new MessageTrace("system", "tool_denied",
                                    "tool=" + parsed.tool() + " choice=DENY"));
                            finalText = denyMsg;
                            break;
                        }
                        if (choice == ConfirmationChoice.ALLOW_ALL_THIS_TURN) {
                            allowAllMutations = true;
                        }
                    }

                    ToolCall call = new ToolCall(parsed.tool(), parsed.args());
                    ToolResult result = toolRegistry.call(call);
                    toolCalls.add(new ToolCallRecord(parsed.tool(), parsed.args(), result));

                    // progress: 工具结果
                    if (result.success()) {
                        progressSink.onProgress(AgentProgressEvent.toolSuccess(
                                toolRound, maxToolRounds, parsed.tool()));
                    } else {
                        progressSink.onProgress(AgentProgressEvent.toolFailed(
                                toolRound, maxToolRounds, parsed.tool(), result.error()));
                    }

                    String toolFeedback = buildToolResultFeedback(parsed.tool(), result);
                    if (toolResultCompactor != null && toolFeedback.length() > toolResultThreshold) {
                        toolFeedback = toolResultCompactor.compactIfNeeded(toolFeedback);
                    }
                    messages.add(LlmMessage.user(toolFeedback));
                    trace.add(new MessageTrace("tool", "tool_result",
                            "tool=" + parsed.tool() + " success=" + result.success()));
                    ToolLoopDebug.logToolResult(log, "runToolLoop", toolRound,
                            parsed.tool(), result.success(), result.error(),
                            result.success() ? summarizeToolResult(result) : null,
                            null);

                    // 推演内容工具成功 → 黄字回显正文（前端渲染为推文卡片）
                    if (result.success()) {
                        for (ToolResult.Item item : result.items()) {
                            if ("simulation_content_text".equals(item.title())) {
                                String snippet = item.snippet();
                                String title = "";
                                String body = snippet;
                                int sep = snippet.indexOf("\n\n");
                                if (sep > 0) {
                                    String heading = snippet.substring(0, sep);
                                    title = heading.startsWith("# ") ? heading.substring(2).trim() : heading.trim();
                                    body = snippet.substring(sep + 2);
                                }
                                progressSink.onProgress(AgentProgressEvent.simulationContent(title, body));
                                break;
                            }
                        }
                    }

                    if ("finish_action".equals(parsed.tool()) && result.success()) {
                        finishActionSeen = true;
                        for (ToolResult.Item item : result.items()) {
                            if ("finish_action_summary".equals(item.title())) {
                                finishSummary = item.snippet();
                            } else {
                                finishMessage = item.snippet();
                                finishStatus = item.title();
                            }
                        }
                    }
                }

                // 用户拒绝了确认 → finalText 已设置，直接结束 while 循环
                if (finalText != null) {
                    break;
                }

                if (finishActionSeen && finishMessage != null) {
                    // 输出 summary（蓝色高亮）
                    if (finishSummary != null && !finishSummary.isBlank()) {
                        progressSink.onProgress(AgentProgressEvent.publicMessage(
                                "\033[34m" + finishSummary + "\033[0m"));
                    }

                    ToolLoopDebug.logFinishAction(log, "runToolLoop", toolRound,
                            finishStatus, finishMessage,
                            finishMessage != null ? finishMessage.length() : 0);

                    String validationError = validateFinishActionMessage(finishMessage);
                    if (validationError != null) {
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
                    return new ChatResult(true, finalText, toolCalls, trace, null);
                }

                if (toolCalls.size() > toolsBefore) {
                    toolRound++;
                    continue;
                }
            }

            // === 无工具调用处理 ===
            if (toolSource == ToolLoopDebug.ToolCallSource.INVALID_TOOL_INTENT) {
                progressSink.onProgress(AgentProgressEvent.invalidBracketIntent(
                        toolRound, maxToolRounds));
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

            // 普通无工具轮：纯文本直接作为最终回复 — LLM 有充分自主权
            consecutiveInvalidToolIntent = 0;
            {
                consecutiveNoToolRounds++;
                ToolLoopDebug.logNoToolRound(log, "runToolLoop", toolRound, consecutiveNoToolRounds);

                // 有意义的纯文本 → 直接接受，本轮结束
                if (content != null && !content.isBlank()
                        && isMeaningfulAssistantContent(content)) {
                    finalText = content;
                    break;
                }

                // 连续 3 轮空内容 → abort
                if (consecutiveNoToolRounds >= 3) {
                    String errMsg = ToolLoopDebug.noToolAbortError(consecutiveNoToolRounds);
                    trace.add(new MessageTrace("system", "error", errMsg));
                    ToolLoopDebug.logNoToolAbort(log, "runToolLoop", toolRound,
                            "consecutive_no_tool_rounds_exceeded", consecutiveNoToolRounds);
                    ToolLoopDebug.logFinalText(log, "runToolLoop", "no_tool_abort", errMsg);
                    return new ChatResult(false, errMsg, toolCalls, trace,
                            "Agent produced no tool calls for " + consecutiveNoToolRounds
                                    + " consecutive rounds");
                }

                // 空内容才提醒
                messages.add(LlmMessage.user("回复内容为空。如果需要查询/操作，请调用工具。如果已完成，请调用 finish_action。"));
                trace.add(new MessageTrace("system", "finish_reminder", "empty content"));
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
            // ESC 取消检查
            if (cancelRequested.get()) {
                log.info("[SimToolLoop] cancelled by user (ESC) at round {}", toolRound);
                return new SimResult("[已取消]", toolCalls, trace);
            }

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
            LlmResult response = callLlm(request);

            if (!response.success()) {
                if (response.isContextLengthExceeded()) {
                    String hint = "⚠️ 上下文窗口不足。建议执行 /compact 压缩对话历史。\n原始错误: " + response.errorMessage();
                    log.warn("[LLM] context length exceeded: {}", response.errorMessage());
                    trace.add(new MessageTrace("system", "error", hint));
                    return new SimResult(hint, toolCalls, trace);
                }
                String err = "LLM 调用失败: " + response.errorMessage();
                trace.add(new MessageTrace("system", "error", err));
                return new SimResult(err, toolCalls, trace);
            }

            String content = response.content();
            int apiToolCallCount = response.hasApiToolCalls() ? response.toolCalls().size() : 0;
            String finishReason = response.finishReason();

            messages.add(LlmMessage.assistant(content != null ? content : ""));

            ToolLoopDebug.logLlmResult(log, "runSimToolLoop", toolRound, content,
                    apiToolCallCount, finishReason);

            // 缓存 draft
            String strippedForDraft = toCleanDraft(content);
            if (strippedForDraft != null && !strippedForDraft.isBlank()) {
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
                            toolRound, maxToolRounds, parsed.tool()));
                }

                // === finish_action 混用检测：允许混用，但必须放在最末尾 ===
                int finishActionIdxSim = -1;
                for (int i = 0; i < allParsed.size(); i++) {
                    if ("finish_action".equals(allParsed.get(i).tool())) {
                        finishActionIdxSim = i;
                        break;
                    }
                }
                if (finishActionIdxSim >= 0 && finishActionIdxSim < allParsed.size() - 1) {
                    String toolsListSim = allParsed.stream()
                            .map(ParsedToolCall::tool)
                            .collect(java.util.stream.Collectors.joining(", "));
                    progressSink.onProgress(AgentProgressEvent.finishRejected(
                            toolRound, maxToolRounds, "FINISH_ACTION_NOT_LAST"));
                    String rejectionSim = "[系统] finish_action 必须出现在工具调用的最末尾。"
                            + "检测到 finish_action 后面还有工具调用（" + toolsListSim + "）。"
                            + "请把 finish_action 调到最后。";
                    messages.add(LlmMessage.system(rejectionSim));
                    trace.add(new MessageTrace("system", "finish_rejected",
                            "FINISH_ACTION_NOT_LAST"));
                    toolRound++;
                    continue;
                }

                consecutiveNoToolRounds = 0;
                consecutiveInvalidToolIntent = 0;
                int toolsBefore = toolCalls.size();
                boolean finishActionSeen = false;
                String finishMessage = null;
                String finishStatus = null;
                String finishSummary = null;

                for (ParsedToolCall parsed : allParsed) {
                    String callSource = toolSource == ToolLoopDebug.ToolCallSource.API_TOOL_CALLS
                            ? "API_TOOL_CALLS" : "TEXT_FALLBACK";
                    trace.add(new MessageTrace("tool", "tool_call", parsed.tool() + " " + parsed.args()));
                    ToolLoopDebug.logToolCall(log, "runSimToolLoop", toolRound,
                            parsed.tool(), parsed.args().toString(), callSource);

                    // progress: 正在执行工具
                    progressSink.onProgress(AgentProgressEvent.toolExecuting(
                            toolRound, maxToolRounds, parsed.tool()));

                    ToolCall call = new ToolCall(parsed.tool(), parsed.args());
                    ToolResult result = toolRegistry.call(call);
                    toolCalls.add(new ToolCallRecord(parsed.tool(), parsed.args(), result));

                    // progress: 工具结果
                    if (result.success()) {
                        progressSink.onProgress(AgentProgressEvent.toolSuccess(
                                toolRound, maxToolRounds, parsed.tool()));
                    } else {
                        progressSink.onProgress(AgentProgressEvent.toolFailed(
                                toolRound, maxToolRounds, parsed.tool(), result.error()));
                    }

                    String toolFeedback = buildToolResultFeedback(parsed.tool(), result);
                    if (toolResultCompactor != null && toolFeedback.length() > toolResultThreshold) {
                        toolFeedback = toolResultCompactor.compactIfNeeded(toolFeedback);
                    }
                    messages.add(LlmMessage.user(toolFeedback));
                    trace.add(new MessageTrace("tool", "tool_result",
                            "tool=" + parsed.tool() + " success=" + result.success()));
                    ToolLoopDebug.logToolResult(log, "runSimToolLoop", toolRound,
                            parsed.tool(), result.success(), result.error(),
                            result.success() ? summarizeToolResult(result) : null,
                            null);

                    // 推演内容工具成功 → 黄字回显正文（前端渲染为推文卡片）
                    if (result.success()) {
                        for (ToolResult.Item item : result.items()) {
                            if ("simulation_content_text".equals(item.title())) {
                                String snippet = item.snippet();
                                String title = "";
                                String body = snippet;
                                int sep = snippet.indexOf("\n\n");
                                if (sep > 0) {
                                    String heading = snippet.substring(0, sep);
                                    title = heading.startsWith("# ") ? heading.substring(2).trim() : heading.trim();
                                    body = snippet.substring(sep + 2);
                                }
                                progressSink.onProgress(AgentProgressEvent.simulationContent(title, body));
                                break;
                            }
                        }
                    }

                    if ("finish_action".equals(parsed.tool()) && result.success()) {
                        finishActionSeen = true;
                        for (ToolResult.Item item : result.items()) {
                            if ("finish_action_summary".equals(item.title())) {
                                finishSummary = item.snippet();
                            } else {
                                finishMessage = item.snippet();
                                finishStatus = item.title();
                            }
                        }
                    }
                }

                if (finishActionSeen && finishMessage != null) {
                    // 输出 summary（蓝色高亮）
                    if (finishSummary != null && !finishSummary.isBlank()) {
                        progressSink.onProgress(AgentProgressEvent.publicMessage(
                                "\033[34m" + finishSummary + "\033[0m"));
                    }
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

            // 普通无工具轮：纯文本直接作为最终回复
            consecutiveNoToolRounds++;
            consecutiveInvalidToolIntent = 0;
            ToolLoopDebug.logNoToolRound(log, "runSimToolLoop", toolRound, consecutiveNoToolRounds);

            if (content != null && !content.isBlank()
                    && isMeaningfulAssistantContent(content)) {
                finalText = content;
                break;
            }

            if (consecutiveNoToolRounds >= 3) {
                String errMsg = ToolLoopDebug.noToolAbortError(consecutiveNoToolRounds);
                trace.add(new MessageTrace("system", "error", errMsg));
                ToolLoopDebug.logNoToolAbort(log, "runSimToolLoop", toolRound,
                        "consecutive_no_tool_rounds_exceeded", consecutiveNoToolRounds);
                ToolLoopDebug.logFinalText(log, "runSimToolLoop", "no_tool_abort", errMsg);
                return new SimResult(errMsg, toolCalls, trace);
            }

            messages.add(LlmMessage.user("回复内容为空。如需操作请调用工具，已完成请调用 finish_action。"));
            trace.add(new MessageTrace("system", "finish_required", "empty content"));
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

        ToolLoopDebug.logFinalText(log, "runSimToolLoop", "finish_action", finalText);
        return new SimResult(finalText, toolCalls, trace);
    }

    // ---- Context History 配置 ----

    /**
     * 对话上下文历史配置。
     * @param historyTurns  最近保留轮数（1..50）
     * @param messageMaxChars 单条消息最大字符数（500..20000）
     */

    /**
     * 按轮数过滤 sessionMessage 列表，保留最近 N 轮。
     * 一轮按 user/chat_user 消息切分；每个 user 消息到下一 user 消息之间为一轮。
     * 保留完整轮内容（含 assistant / tool_call / tool_result），不截断轮内 tool 链。
     */
}
