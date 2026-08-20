package com.gsim.agent.tool;

import com.gsim.agent.AgentInstance;
import com.gsim.agent.core.AgentResult;
import com.gsim.agent.management.AgentsManager;
import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.llm.ToolDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * collect_sub_agent_results 工具 — 列出所有已完成的异步子代理结果（非阻塞）。
 *
 * <p>dispatch_sub_agent 现在异步派发（立即返回），本工具用于聚合所有已完成
 * （status=DONE）的子代理结果。不阻塞等待运行中的子代理。
 */
public class CollectSubAgentResultsTool implements AgentTool {

    public static final String NAME = "collect_sub_agent_results";

    private static final Logger log = LoggerFactory.getLogger(CollectSubAgentResultsTool.class);

    private final Map<String, CompletableFuture<AgentResult>> runningSubAgents;
    private volatile AgentsManager agentsManager;

    /** 默认构造（旧路径：使用 runningSubAgents map 收集已完成结果）。 */
    public CollectSubAgentResultsTool(Map<String, CompletableFuture<AgentResult>> runningSubAgents) {
        this.runningSubAgents = runningSubAgents;
    }

    /**
     * 注入 AgentsManager（优先路径），启用基于实例状态的非阻塞已完成列表收集。
     * 注入后 {@link #execute(ToolCall)} 优先使用 AgentsManager 而非旧 map 路径。
     *
     * @param am AgentsManager 实例
     */
    public void setAgentsManager(AgentsManager am) {
        this.agentsManager = am;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                列出所有已完成的异步子代理结果（非阻塞，不等待运行中的子代理）。
                参数:
                - type (可选): 按子代理类型过滤（sim/search/orchestrator）
                返回每个已完成子代理的 agentId、configId、cacheId、最终结果摘要和轮数。
                无已完成子代理时返回空列表。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "type",
                        Map.of(
                                "type", "string",
                                "description", "可选 — 按子代理类型过滤（sim/search/orchestrator）")),
                List.of());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String type = call.param("type", "").trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) type = null;

        List<CompletedEntry> entries = new ArrayList<>();
        if (agentsManager != null) {
            for (AgentInstance inst : agentsManager.listDoneAgents(null, type)) {
                AgentResult result = agentsManager.getCompletedResult(inst.instanceId());
                entries.add(CompletedEntry.fromInstance(inst, result));
            }
        } else {
            // Fallback: 旧路径 — 从 runningSubAgents map 收集已完成的 future
            for (var entry : runningSubAgents.entrySet()) {
                CompletableFuture<AgentResult> future = entry.getValue();
                if (!future.isDone() || future.isCancelled()) continue;
                AgentResult result = future.getNow(null);
                if (result == null || !result.success()) continue;
                if (type != null && !entry.getKey().startsWith(type + "-")) continue;
                entries.add(CompletedEntry.fromLegacy(entry.getKey(), result));
            }
        }

        if (entries.isEmpty()) {
            log.info("[CollectSubAgent] no completed sub-agents{}", type != null ? " (type=" + type + ")" : "");
            String hint = "无已完成的子代理" + (type != null ? "（type=" + type + "）" : "") + "。"
                    + "已派发的子代理仍在运行，可通过 view_sub_agent_cache 查看状态与最近交互。";
            return ToolResult.ok(NAME, List.of(new ToolResult.Item("no_completed_sub_agents", NAME, hint, 1.0)));
        }

        StringBuilder sb = new StringBuilder("## 已完成子代理结果\n\n");
        for (CompletedEntry e : entries) {
            sb.append("### ").append(e.agentId()).append("\n\n");
            sb.append("- **configId**: ").append(e.configId()).append("\n");
            sb.append("- **cacheId**: ").append(e.cacheId()).append("\n");
            sb.append("- **轮数**: ").append(e.rounds()).append("\n");
            if (e.finalText() != null && !e.finalText().isBlank()) {
                sb.append("- **结果**:\n").append(truncate(e.finalText(), 4000)).append("\n\n");
            } else {
                sb.append("- **结果**: (空结果)\n\n");
            }
        }
        sb.append("---\n");
        sb.append("汇总: ").append(entries.size()).append(" 个已完成子代理");
        if (type != null) sb.append("（type=").append(type).append("）");

        log.info("[CollectSubAgent] completed: {} entries", entries.size());
        return ToolResult.ok(
                NAME,
                List.of(new ToolResult.Item("completed: " + entries.size() + " sub-agents", NAME, sb.toString(), 1.0)));
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private record CompletedEntry(String agentId, String configId, String cacheId, String finalText, int rounds) {
        static CompletedEntry fromInstance(AgentInstance inst, AgentResult result) {
            return new CompletedEntry(
                    inst.instanceId(),
                    inst.configId(),
                    inst.cacheId(),
                    result != null ? result.finalText() : null,
                    result != null && result.rounds() != null ? result.rounds().size() : 0);
        }

        static CompletedEntry fromLegacy(String agentId, AgentResult result) {
            return new CompletedEntry(
                    agentId,
                    result != null ? result.agentId() : agentId,
                    result != null ? result.cacheSessionId() : null,
                    result != null ? result.finalText() : null,
                    result != null && result.rounds() != null ? result.rounds().size() : 0);
        }
    }

    @Override
    public Permission permission() {
        return Permission.SYSTEM;
    }

    @Override
    public boolean mcpExposed() {
        return true;
    }
}
