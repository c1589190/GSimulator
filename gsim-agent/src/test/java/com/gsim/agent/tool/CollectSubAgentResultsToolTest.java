package com.gsim.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agent.core.AgentResult;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CollectSubAgentResultsTool 非阻塞列表语义测试 — 旧路径（runningSubAgents map）。
 *
 * <p>断言：运行中的 future 不被等待；仅已完成的结果进入列表；type 过滤生效。
 */
@DisplayName("CollectSubAgentResultsTool 非阻塞已完成列表（旧路径）")
class CollectSubAgentResultsToolTest {

    private static AgentResult okResult(String agentId, String text) {
        return AgentResult.ok(agentId, text, List.of(), 3);
    }

    @Test
    @DisplayName("运行中的 future 不被阻塞等待，返回无完成提示")
    void runningFutureDoesNotBlock() {
        Map<String, CompletableFuture<AgentResult>> running = new HashMap<>();
        running.put("sim-1", new CompletableFuture<>()); // never completes

        var tool = new CollectSubAgentResultsTool(running);

        long start = System.nanoTime();
        ToolResult result = tool.execute(new ToolCall("collect_sub_agent_results", Map.of()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.success(), "非阻塞收集不应失败");
        assertEquals(1, result.items().size());
        assertEquals("no_completed_sub_agents", result.items().get(0).title());
        assertTrue(result.items().get(0).snippet().contains("无已完成的子代理"));
        assertTrue(elapsedMs < 2000, "不应阻塞等待运行中的子代理，耗时 " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("已完成的 future 出现在结果列表中")
    void completedFutureAppearsInList() {
        Map<String, CompletableFuture<AgentResult>> running = new HashMap<>();
        running.put("sim-1", CompletableFuture.completedFuture(okResult("sim-1", "最终结果文本")));

        var tool = new CollectSubAgentResultsTool(running);
        ToolResult result = tool.execute(new ToolCall("collect_sub_agent_results", Map.of()));

        assertTrue(result.success());
        assertEquals(1, result.items().size());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("sim-1"), "应包含 agentId: " + snippet);
        assertTrue(snippet.contains("最终结果文本"), "应包含最终结果: " + snippet);
        assertTrue(snippet.contains("轮数"), "应包含轮数统计: " + snippet);
    }

    @Test
    @DisplayName("type 过滤：只返回匹配类型的已完成子代理")
    void typeFilterApplies() {
        Map<String, CompletableFuture<AgentResult>> running = new HashMap<>();
        running.put("sim-1", CompletableFuture.completedFuture(okResult("sim-1", "sim result")));
        running.put("search-2", CompletableFuture.completedFuture(okResult("search-2", "search result")));

        var tool = new CollectSubAgentResultsTool(running);

        ToolResult simOnly = tool.execute(new ToolCall("collect_sub_agent_results", Map.of("type", "sim")));
        assertTrue(simOnly.success());
        String simSnippet = simOnly.items().get(0).snippet();
        assertTrue(simSnippet.contains("sim-1"));
        assertFalse(simSnippet.contains("search-2"), "type=sim 不应包含 search 结果");

        ToolResult searchOnly = tool.execute(new ToolCall("collect_sub_agent_results", Map.of("type", "search")));
        assertTrue(searchOnly.success());
        assertTrue(searchOnly.items().get(0).snippet().contains("search-2"));
    }
}
