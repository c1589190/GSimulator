package com.gsim.agentsmanager.core;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.AgentConfig;
import com.gsim.agentsmanager.ToolFilterConfig;
import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.agentsmanager.event.AgentProgressSink;
import com.gsim.agentsmanager.llm.LlmCall;
import com.gsim.agentsmanager.llm.LlmManager;
import com.gsim.agentsmanager.llm.LlmRequest;
import com.gsim.agentsmanager.llm.LlmResult;
import com.gsim.agentsmanager.llm.LlmToolCall;
import com.gsim.agentsmanager.llm.ProviderConfig;
import com.gsim.agentsmanager.llm.StreamPool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * maxToolRounds=0（不限制 / 无限轮）ToolLoop 行为测试。
 *
 * <p>覆盖：0 轮上限时纯文本首轮直接完成；0 轮上限时超过常规数值上限的轮次仍继续
 * （对比 maxToolRounds=2 的同脚本在轮数耗尽时失败）。
 */
@DisplayName("AbstractAgent 轮数 0 = 不限制")
class UnlimitedToolRoundsTest {

    /** 可编程 Fake LLM — 与 AsyncSubAgentToolsTest 同模式。 */
    static class FakeLlm extends LlmManager {
        volatile Function<LlmRequest, LlmResult> handler = req -> LlmResult.success("default", "m", 0);

        FakeLlm() {
            super(ProviderConfig.generic("test", "http://127.0.0.1:1", "k", "m", 0.3, 30));
        }

        @Override
        public LlmCall submit(LlmRequest request) {
            LlmResult result = handler.apply(request);
            StreamPool pool = new StreamPool("fake");
            if (result.success() && result.content() != null) {
                pool.onContentDelta(result.content());
            }
            pool.onComplete(result);
            return new LlmCall("fake", pool);
        }
    }

    /** 只读桩工具 — 让 ToolLoop 可以持续多轮。 */
    static class StubTool implements AgentTool {
        @Override
        public String name() {
            return "stub_tool";
        }

        @Override
        public String description() {
            return "stub tool for tests";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(new ToolResult.Item("stub", name(), "ok", 1.0)));
        }

        @Override
        public Permission permission() {
            return Permission.READ;
        }
    }

    private AbstractAgent buildAgent(int maxToolRounds, FakeLlm llm) {
        AgentConfig config =
                AgentConfig.of("test", "system prompt", "", ToolFilterConfig.ALL, maxToolRounds, 0.3, 2048);
        ToolRegistry tools = new ToolRegistry();
        tools.register(new StubTool());
        return new AbstractAgent(config, llm, tools, AgentProgressSink.NOOP, "test-model");
    }

    @Test
    @DisplayName("maxToolRounds=0：首轮纯文本（无 tool_calls）正常完成")
    void zeroRoundsCompletesWithPlainTextOnFirstRound() {
        FakeLlm llm = new FakeLlm();
        llm.handler = req -> LlmResult.success("直接给出的结果文本", "m", 0);

        AgentResult result = buildAgent(0, llm).run("任务");

        assertTrue(result.success(), "0 轮上限不应阻止纯文本完成: " + result.error());
        assertEquals("直接给出的结果文本", result.finalText());
        assertEquals(1, result.rounds().size(), "首轮即应完成");
        assertEquals(0, result.totalToolCalls());
    }

    @Test
    @DisplayName("maxToolRounds=0：超过常规数值上限的轮次仍继续（2 轮工具后第 3 轮纯文本完成）")
    void zeroRoundsContinuesBeyondNormalLimit() {
        FakeLlm llm = new FakeLlm();
        AtomicInteger calls = new AtomicInteger();
        llm.handler = req -> {
            int c = calls.incrementAndGet();
            if (c <= 2) {
                return LlmResult.withToolCalls(List.of(new LlmToolCall("tc-" + c, "stub_tool", Map.of())), "m", 0);
            }
            return LlmResult.success("第三轮完成的最终结果", "m", 0);
        };

        AgentResult result = buildAgent(0, llm).run("任务");

        assertTrue(result.success(), "0 轮上限不应因轮数终止: " + result.error());
        assertEquals("第三轮完成的最终结果", result.finalText());
        assertEquals(3, calls.get(), "应执行 3 轮 LLM 调用（第 3 轮纯文本完成）");
        assertEquals(3, result.rounds().size());
        assertEquals(2, result.totalToolCalls(), "前两轮各调用一次 stub_tool");
    }

    @Test
    @DisplayName("对照组 maxToolRounds=2：同一脚本在轮数耗尽时失败")
    void finiteLimitStillFailsWhenExhausted() {
        FakeLlm llm = new FakeLlm();
        AtomicInteger calls = new AtomicInteger();
        llm.handler = req -> {
            int c = calls.incrementAndGet();
            if (c <= 2) {
                return LlmResult.withToolCalls(List.of(new LlmToolCall("tc-" + c, "stub_tool", Map.of())), "m", 0);
            }
            return LlmResult.success("本应无法到达的结果", "m", 0);
        };

        AgentResult result = buildAgent(2, llm).run("任务");

        assertFalse(result.success(), "maxRounds=2 时第 3 轮不应执行");
        assertTrue(result.error().contains("达到最大轮数 (2)"), "应报轮数耗尽: " + result.error());
        assertEquals(2, calls.get(), "只应执行 2 轮 LLM 调用");
    }
}
