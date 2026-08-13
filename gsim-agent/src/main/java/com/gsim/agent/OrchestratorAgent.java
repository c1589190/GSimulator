package com.gsim.agent;

import com.gsim.agent.core.AbstractAgent;
import com.gsim.agent.core.AgentFactory;
import com.gsim.agent.core.AgentResult;
import com.gsim.agent.tool.CollectSubAgentResultsTool;
import com.gsim.agent.tool.DispatchSubAgentTool;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.event.AgentProgressEvent;
import com.gsim.core.event.AgentProgressSink;
import com.gsim.core.llm.LlmCall;
import com.gsim.core.llm.LlmManager;
import com.gsim.core.llm.LlmMessage;
import com.gsim.core.llm.LlmRequest;
import com.gsim.core.llm.LlmResult;
import com.gsim.core.llm.StreamPool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     *
     * @param registry        工具注册表
     * @param agentFactory    Agent 工厂（用于创建 SubAgent）
     * @param docCacheManager 文档缓存管理器
     */
    public void registerSubAgentTools(
            ToolRegistry registry, AgentFactory agentFactory, com.gsim.doc.DocCacheManager docCacheManager) {
        this.agentFactory = agentFactory;
        registry.register(new DispatchSubAgentTool(
                llmManager,
                toolRegistry,
                model,
                progressSink,
                runningSubAgents,
                subAgentCounter,
                agentFactory,
                agentFactory.store(),
                docCacheManager));
        registry.register(new CollectSubAgentResultsTool(runningSubAgents));
    }

    public OrchestratorAgent(LlmManager llmManager, ToolRegistry toolRegistry, String model) {
        this(llmManager, toolRegistry, model, AgentProgressSink.NOOP);
    }

    public OrchestratorAgent(
            LlmManager llmManager, ToolRegistry toolRegistry, String model, AgentProgressSink progressSink) {
        this(llmManager, toolRegistry, model, progressSink, null);
    }

    public OrchestratorAgent(
            LlmManager llmManager,
            ToolRegistry toolRegistry,
            String model,
            AgentProgressSink progressSink,
            ToolPermissionGate permissionGate) {
        this(
                llmManager,
                toolRegistry,
                model,
                progressSink != null ? progressSink : AgentProgressSink.NOOP,
                new ToolRoutePolicy(),
                new ToolExecutionPolicy(),
                permissionGate,
                new ToolPermissionConfig(),
                ToolGroupManager.createWithAllGroupsActivated());
    }

    public OrchestratorAgent(
            LlmManager llmManager,
            ToolRegistry toolRegistry,
            String model,
            AgentProgressSink progressSink,
            ToolPermissionGate permissionGate,
            ToolGroupManager groupManager) {
        this(
                llmManager,
                toolRegistry,
                model,
                progressSink != null ? progressSink : AgentProgressSink.NOOP,
                new ToolRoutePolicy(),
                new ToolExecutionPolicy(),
                permissionGate,
                new ToolPermissionConfig(),
                groupManager);
    }

    OrchestratorAgent(
            LlmManager llmManager,
            ToolRegistry toolRegistry,
            String model,
            AgentProgressSink progressSink,
            ToolRoutePolicy routePolicy,
            ToolExecutionPolicy executionPolicy,
            ToolPermissionGate permissionGate,
            ToolPermissionConfig permissionConfig) {
        this(
                llmManager,
                toolRegistry,
                model,
                progressSink,
                routePolicy,
                executionPolicy,
                permissionGate,
                permissionConfig,
                ToolGroupManager.createWithAllGroupsActivated());
    }

    OrchestratorAgent(
            LlmManager llmManager,
            ToolRegistry toolRegistry,
            String model,
            AgentProgressSink progressSink,
            ToolRoutePolicy routePolicy,
            ToolExecutionPolicy executionPolicy,
            ToolPermissionGate permissionGate,
            ToolPermissionConfig permissionConfig,
            ToolGroupManager groupManager) {
        super(
                AgentConfig.defaultOrchestrator(),
                llmManager,
                toolRegistry,
                progressSink != null ? progressSink : AgentProgressSink.NOOP,
                model);
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

    /** Orchestrator 必须通过 finish_action 结束，不接受纯文本自动完成。 */
    @Override
    protected boolean requireFinishAction() {
        return true;
    }

    /** 设置是否使用 LLM 流式输出（由 AppConfig 注入）。 */
    public void setStreamEnabled(boolean streamEnabled) {
        this.streamEnabled = streamEnabled;
    }

    /** 获取流式输出开关。 */
    public boolean isStreamEnabled() {
        return streamEnabled;
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
                progressSink.onProgress(AgentProgressEvent.toolFailed(
                        0, effectiveMaxToolRounds(), toolName, "REJECTED: no permission gate"));
                return false;
            }
            // gate exists — ask user (blocking)
            ToolConfirmationRequest confirmReq = new ToolConfirmationRequest(
                    toolName,
                    category,
                    category == ToolCategory.DESTRUCTIVE ? "破坏性操作: " + toolName : "写入操作: " + toolName,
                    parsed.args(),
                    null);
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
                progressSink.onProgress(AgentProgressEvent.llmStreamFailed(
                        streamId, result.errorMessage() != null ? result.errorMessage() : "unknown"));
            }

            // [STREAM_TRACE] 汇总
            log.info(
                    "[STREAM_TRACE] completed streamId={} contentDeltaEvents={} reasoningDeltaEvents={}"
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

    // ---- result types ----

    // ParsedToolCall moved to com.gsim.agent.ParsedToolCall (top-level public record)

    /**
     * 一次工具调用记录（含结果）。
     *
     * @param tool   工具名称
     * @param args   工具参数
     * @param result 工具执行结果
     */
    public record ToolCallRecord(String tool, Map<String, String> args, ToolResult result) {}

    /**
     * 判断 assistant 回复内容是否有意义（非空、非 null/undefined 占位符）。
     *
     * @param content assistant 的输出文本
     * @return true 表示内容有意义，false 表示空内容或无意义占位符
     */
    static boolean isMeaningfulAssistantContent(String content) {
        if (content == null) return false;
        String t = content.strip();
        if (t.isEmpty()) return false;
        return switch (t) {
            case "null",
                    "NULL",
                    "Null",
                    "undefined",
                    "UNDEFINED",
                    "Undefined",
                    "JsonNull",
                    "jsonNull",
                    "JSONNULL",
                    "{}",
                    "[]" -> false;
            default -> true;
        };
    }
}
