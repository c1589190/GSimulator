package com.gsim.agent.tool;

import com.gsim.agent.core.AgentResult;
import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * collect_sub_agent_results 工具 — 等待所有异步派发的子代理完成并聚合结果。
 *
 * <p>dispatch_sub_agent 现在是同步阻塞的（派发后主 Agent 等待子 Agent 完成），
 * 因此正常情况下不需要此工具。保留用于：手动检查运行状态、清理残留 future。
 */
public class CollectSubAgentResultsTool implements AgentTool {

    public static final String NAME = "collect_sub_agent_results";

    private static final Logger log = LoggerFactory.getLogger(CollectSubAgentResultsTool.class);

    private final Map<String, CompletableFuture<AgentResult>> runningSubAgents;

    public CollectSubAgentResultsTool(
            Map<String, CompletableFuture<AgentResult>> runningSubAgents) {
        this.runningSubAgents = runningSubAgents;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                收集所有异步派发的子代理执行结果。
                dispatch_sub_agent 现已改为同步阻塞（派发后自动等待结果），
                此工具仅在需要手动检查运行状态或清理残留 future 时使用。
                超时时间: 300 秒。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(),
                List.of()
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (runningSubAgents.isEmpty()) {
            return ToolResult.fail(NAME,
                    "当前没有正在运行的子代理。请先调用 dispatch_sub_agent 创建子代理。");
        }

        int totalCount = runningSubAgents.size();
        log.info("[CollectSubAgent] waiting for {} sub-agents...", totalCount);

        // 阻塞等待所有 CompletableFuture 完成
        CompletableFuture<?>[] futures = runningSubAgents.values()
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).get(300, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[CollectSubAgent] timeout after 300s, cancelling {} sub-agents",
                    runningSubAgents.size());
            // 取消所有未完成的 future
            for (var entry : runningSubAgents.entrySet()) {
                entry.getValue().cancel(true);
            }
            runningSubAgents.clear();
            return ToolResult.fail(NAME,
                    "子代理执行超时（300 秒），已取消所有子代理。");
        } catch (Exception e) {
            log.error("[CollectSubAgent] collection failed: {}", e.getMessage(), e);
            runningSubAgents.clear();
            return ToolResult.fail(NAME, "收集子代理结果失败: " + e.getMessage());
        }

        // 聚合结果
        StringBuilder sb = new StringBuilder();
        sb.append("## 子代理执行结果\n\n");

        int successCount = 0;
        int failCount = 0;
        int runningCount = 0;
        int unknownCount = 0;

        for (var entry : runningSubAgents.entrySet()) {
            String agentId = entry.getKey();
            CompletableFuture<AgentResult> future = entry.getValue();
            AgentResult result = future.getNow(null);

            sb.append("### ").append(agentId).append("\n\n");
            String status;
            if (result != null) {
                status = result.success() ? "COMPLETED" : "FAILED";
            } else if (future.isCancelled()) {
                status = "CANCELLED";
            } else if (!future.isDone()) {
                status = "RUNNING";
            } else {
                status = "UNKNOWN";
            }
            sb.append("- **状态**: ").append(status).append("\n");

            if (result != null && result.success()) {
                successCount++;
                // 截断过长文本（子代理结果可能很长，保留前 4000 字符）
                String text = result.finalText();
                if (text != null && text.length() > 4000) {
                    text = text.substring(0, 3997) + "...";
                }
                sb.append("- **结果**:\n").append(text != null ? text : "(空结果)").append("\n\n");
            } else {
                if (status.equals("RUNNING")) {
                    runningCount++;
                } else if (status.equals("UNKNOWN")) {
                    unknownCount++;
                } else {
                    failCount++;
                }
                sb.append("- **错误**: ")
                        .append(result != null ? result.error() : "无结果（状态: " + status + "）")
                        .append("\n\n");
            }
        }

        sb.append("---\n");
        sb.append("汇总: ").append(successCount).append(" 成功, ")
                .append(failCount).append(" 失败");
        if (runningCount > 0) sb.append(", ").append(runningCount).append(" 仍在运行");
        if (unknownCount > 0) sb.append(", ").append(unknownCount).append(" 状态未知");
        sb.append(", ").append(totalCount).append(" 总计");

        log.info("[CollectSubAgent] completed: {} success, {} fail, {} total",
                successCount, failCount, totalCount);

        runningSubAgents.clear();

        return ToolResult.ok(NAME, List.of(new ToolResult.Item(
                "collected: " + totalCount + " sub-agents",
                NAME,
                sb.toString(),
                1.0)));
    }
}
