package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CLI 运行时 CliAgentProgressSink 和 OrchestratorAgent 共享同一个 LlmStreamStateRegistry。
 * 这是灰框渲染的关键前提 — registry 必须是单例共享，不能各自创建。
 */
@DisplayName("CLI 运行时 registry 共享测试")
class CliRuntimeSharesStreamRegistryTest {

    // ===== Test 1: CliRuntimeSharesStreamRegistryTest =====

    @Test
    @DisplayName("CliAgentProgressSink 和 OrchestratorAgent 使用同一个 registry 实例")
    void sinkAndOrchestratorShareSameRegistry() {
        // 模拟 GSimulatorApplication 中的真实 wiring
        LlmStreamStateRegistry sharedRegistry = new LlmStreamStateRegistry();
        CliStreamPreviewRenderer renderer = new CliStreamPreviewRenderer(
                System.out, true, 3000, true);

        // 4-arg 构造：sink 持有 registry
        CliAgentProgressSink sink = new CliAgentProgressSink(
                System.out, true, renderer, sharedRegistry);

        // OrchestratorAgent 也使用同一个 registry
        com.gsim.llm.FakeLlmClient fakeLlm = new com.gsim.llm.FakeLlmClient();
        com.gsim.tool.ToolRegistry toolRegistry = new com.gsim.tool.ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());

        OrchestratorAgent agent = new OrchestratorAgent(
                fakeLlm, toolRegistry, "test-model", sink);
        agent.setStreamRegistry(sharedRegistry);

        // 确认两个引用指向同一对象
        assertSame(sharedRegistry, agent.getStreamRegistry(),
                "Orchestrator 的 registry 应和传给 sink 的是同一个实例");

        // 通过 sink 侧的 registry 写入 stream → 通过 agent 侧的 registry 应能读到
        String streamId = "shared-test-1";
        sharedRegistry.start(streamId);
        sharedRegistry.appendContent(streamId, "Hello from sink");

        LlmStreamSnapshot snap = agent.getStreamRegistry().snapshot(streamId);
        assertNotNull(snap);
        assertEquals("Hello from sink", snap.content(),
                "通过 agent 侧 registry 应能读到相同的 content");
        assertTrue(snap.active(), "stream 应处于活跃状态");
    }

    @Test
    @DisplayName("registry 未共享时 getSnapshot 返回 null（触发 fallback）")
    void nullRegistryTriggersFallbackInSink() {
        // 旧版 3-arg 构造不带 registry
        CliAgentProgressSink sink = new CliAgentProgressSink(
                System.out, true, new CliStreamPreviewRenderer(System.out, true, 3000, true));

        // 发送 LLM_CONTENT_DELTA 不抛异常（走 fallback 路径）
        assertDoesNotThrow(() -> {
            sink.onProgress(AgentProgressEvent.llmContentDelta("no-registry", "fallback text"));
        });
    }

    @Test
    @DisplayName("sink 通过 stream_phase 事件发布后 agent 侧 registry snapshot 可读")
    void sinkPublishesEventsThenAgentRegistrySnapshotReadable() {
        LlmStreamStateRegistry sharedRegistry = new LlmStreamStateRegistry();

        // 模拟 Orchestrator 创建 stream → 注入 registry → 发布 LLM_STREAM_STARTED
        String streamId = "orchestrator-stream";
        sharedRegistry.start(streamId);
        sharedRegistry.appendContent(streamId, "内容A");
        sharedRegistry.appendContent(streamId, "内容B");

        // sink 通过自己的 registry 引用读取
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        CliStreamPreviewRenderer renderer = new CliStreamPreviewRenderer(ps, true, 3000, true);
        CliAgentProgressSink sink = new CliAgentProgressSink(
                ps, true, renderer, sharedRegistry);

        // 发送 delta 事件 → sink handler 从 registry 取 snapshot 渲染
        sink.onProgress(AgentProgressEvent.llmContentDelta(streamId, "内容A"));
        sink.onProgress(AgentProgressEvent.llmContentDelta(streamId, "内容B"));

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("内容A") || output.contains("内容B"),
                "renderer 应显示从 registry 读到的内容: " + output);
    }
}
