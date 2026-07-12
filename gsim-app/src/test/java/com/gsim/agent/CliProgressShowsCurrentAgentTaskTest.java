package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 quiet 模式下 CONTEXT_LOADED / WAITING_LLM 事件不输出到终端。
 */
@DisplayName("CLI 进度显示上下文/等待事件（quiet mode）")
class CliProgressShowsCurrentAgentTaskTest {

    @Test
    @DisplayName("CONTEXT_LOADED → null（静默）")
    void contextLoadedIsQuiet() {
        var enriched = new AgentProgressEvent(AgentProgressEvent.CONTEXT_LOADED, 1, 5,
                "上下文加载完毕",
                java.util.Map.of("requestChars", "46028", "toolCount", "2",
                        "activeBranch", "branch.b0002", "contextMode", "FULL_CONTEXT"));
        assertNull(CliAgentProgressSink.format(enriched));
    }

    @Test
    @DisplayName("WAITING_LLM → null（静默）")
    void waitingLlmIsQuiet() {
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.waitingLlm(1, 5)));
    }

    @Test
    @DisplayName("AGENT_PUBLIC_MESSAGE → 非 null（公开消息始终可见）")
    void publicMessageVisible() {
        var event = AgentProgressEvent.publicMessage("公开消息内容");
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.contains("公开消息内容"));
    }
}
