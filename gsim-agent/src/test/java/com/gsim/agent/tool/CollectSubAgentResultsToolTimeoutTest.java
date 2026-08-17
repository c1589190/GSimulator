package com.gsim.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agent.core.AgentResult;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CollectSubAgentResultsTool 超时配置注入测试 — 断言注入的超时秒数用于 future.get(timeout)。
 */
@DisplayName("CollectSubAgentResultsTool 超时配置注入")
class CollectSubAgentResultsToolTimeoutTest {

    @Test
    @DisplayName("注入的超时秒数生效：1 秒即超时而非 300 秒")
    void injectedTimeoutDrivesFutureGet() {
        Map<String, CompletableFuture<AgentResult>> running = new HashMap<>();
        running.put("sim-1", new CompletableFuture<>()); // never completes
        var tool = new CollectSubAgentResultsTool(running, 1);

        long start = System.nanoTime();
        ToolResult result = tool.execute(new ToolCall("collect_sub_agent_results", Map.of()));
        long elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertFalse(result.success(), "should time out");
        assertTrue(result.error().contains("超时"), "error should mention timeout: " + result.error());
        assertTrue(elapsedSeconds < 10, "should return within ~1s, took " + elapsedSeconds + "s");
        assertTrue(running.isEmpty(), "timeout path should clear the running map");
    }

    @Test
    @DisplayName("默认构造保留 300 秒超时（description 反映默认值）")
    void defaultConstructorKeepsDefaults() {
        Map<String, CompletableFuture<AgentResult>> running = new HashMap<>();
        var tool = new CollectSubAgentResultsTool(running);

        assertTrue(tool.description().contains("300 秒"), "default description should mention 300s");
    }
}
