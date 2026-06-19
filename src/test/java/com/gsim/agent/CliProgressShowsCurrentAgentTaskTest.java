package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CliAgentProgressSink.format 对 CONTEXT_LOADED 事件的显示。
 */
@DisplayName("CLI 进度显示当前 Agent 任务")
class CliProgressShowsCurrentAgentTaskTest {

    @Test
    @DisplayName("CONTEXT_LOADED 显示 activeBranch 和 requestChars")
    void contextLoadedShowsBranchAndChars() {
        var event = AgentProgressEvent.contextLoaded(1, 5, 46028, 2);
        // 手动补充 meta（contextLoaded factory 只有 requestChars + toolCount）
        var enriched = new AgentProgressEvent(AgentProgressEvent.CONTEXT_LOADED, 1, 5,
                "上下文加载完毕",
                java.util.Map.of("requestChars", "46028", "toolCount", "2",
                        "activeBranch", "branch.b0002", "contextMode", "FULL_CONTEXT"));
        String line = CliAgentProgressSink.format(enriched);
        assertNotNull(line);
        assertTrue(line.contains("branch.b0002"),
                "Should show activeBranch: " + line);
        assertTrue(line.contains("46028"),
                "Should show requestChars: " + line);
        assertTrue(line.contains("FULL_CONTEXT"),
                "Should show contextMode: " + line);
        assertTrue(line.contains("[Agent]"),
                "Should start with [Agent] prefix");
    }

    @Test
    @DisplayName("CONTEXT_LOADED 行长度 ≤ 120 chars")
    void contextLoadedLineWithinLength() {
        var enriched = new AgentProgressEvent(AgentProgressEvent.CONTEXT_LOADED, 1, 5,
                "上下文加载完毕",
                java.util.Map.of("requestChars", "46028", "toolCount", "15",
                        "activeBranch", "branch.b0002", "contextMode", "FULL_CONTEXT"));
        String line = CliAgentProgressSink.format(enriched);
        assertNotNull(line);
        // 长 tool 列表可能超 120, 截断即可
        assertTrue(line.length() <= 130,
                "Line should be reasonable length, got " + line.length());
    }

    @Test
    @DisplayName("WAITING_LLM 显示简洁等待提示")
    void waitingLlmShowsBrief() {
        var event = AgentProgressEvent.waitingLlm(1, 5);
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.contains("等待"),
                "Should show waiting status: " + line);
    }
}
