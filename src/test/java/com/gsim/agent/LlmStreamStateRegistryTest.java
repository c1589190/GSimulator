package com.gsim.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmStreamStateRegistry 单元测试。
 * 验证 registry 作为流式状态唯一真相源的正确性。
 */
@DisplayName("LLM 流式状态注册表测试")
class LlmStreamStateRegistryTest {

    private LlmStreamStateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new LlmStreamStateRegistry();
    }

    // ---- Test 1: StreamRegistryAccumulatesContentAndReasoningTest ----

    @Test
    @DisplayName("registry 累加 content 和 reasoning → snapshot 完整反映")
    void accumulatesContentAndReasoning() {
        String sid = "stream-1";
        registry.start(sid);

        registry.appendContent(sid, "阿道芙");
        registry.appendContent(sid, "进入了");
        registry.appendContent(sid, "学校大门");

        registry.appendReasoning(sid, "分析玩家");
        registry.appendReasoning(sid, "行动意图");

        LlmStreamSnapshot snap = registry.snapshot(sid);
        assertNotNull(snap);
        assertEquals(sid, snap.streamId());
        assertEquals("阿道芙进入了学校大门", snap.content());
        assertEquals("分析玩家行动意图", snap.reasoning());
        assertEquals(3, snap.contentDeltaCount());
        assertEquals(2, snap.reasoningDeltaCount());
        assertTrue(snap.active(), "stream 应处于活跃状态");
        assertFalse(snap.completed(), "stream 不应已完成");
    }

    @Test
    @DisplayName("registry complete → snapshot 标记 completed，不再 active")
    void completeMarksSnapshotComplete() {
        String sid = "stream-2";
        registry.start(sid);
        registry.appendContent(sid, "test");

        registry.complete(sid);

        LlmStreamSnapshot snap = registry.snapshot(sid);
        assertTrue(snap.completed(), "应标记为已完成");
        assertFalse(snap.active(), "完成后不应活跃");
    }

    @Test
    @DisplayName("registry fail → snapshot 标记 error，不再 active")
    void failMarksSnapshotError() {
        String sid = "stream-3";
        registry.start(sid);
        registry.appendContent(sid, "partial...");

        registry.fail(sid, "连接超时");

        LlmStreamSnapshot snap = registry.snapshot(sid);
        assertFalse(snap.active(), "失败后不应活跃");
        assertEquals("连接超时", snap.error());
    }

    @Test
    @DisplayName("不存在 streamId → snapshot 返回 EMPTY")
    void snapshotReturnsEmptyForUnknownStream() {
        assertEquals(LlmStreamSnapshot.EMPTY, registry.snapshot("nonexistent"));
    }

    @Test
    @DisplayName("remove 后 snapshot 返回 EMPTY")
    void removeCleansUpStreamState() {
        String sid = "stream-4";
        registry.start(sid);
        registry.appendContent(sid, "data");

        registry.remove(sid);

        assertEquals(LlmStreamSnapshot.EMPTY, registry.snapshot(sid));
    }

    @Test
    @DisplayName("activeCount 正确反映活跃流数量")
    void activeCountTracksActiveStreams() throws InterruptedException {
        assertEquals(0, registry.activeCount());

        registry.start("a");
        registry.start("b");
        registry.start("c");
        assertEquals(3, registry.activeCount());

        registry.complete("a");
        assertEquals(2, registry.activeCount());

        registry.fail("b", "error");
        assertEquals(1, registry.activeCount());

        registry.remove("c");
        assertEquals(0, registry.activeCount());
    }

    @Test
    @DisplayName("并发追加 content → snapshot 最终完整不丢失")
    void concurrentAppendContentIsSafe() throws Exception {
        String sid = "concurrent";
        registry.start(sid);

        int threadCount = 4;
        int appendsPerThread = 25;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                for (int i = 0; i < appendsPerThread; i++) {
                    registry.appendContent(sid, "T" + threadId + "-" + i + " ");
                }
                latch.countDown();
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        LlmStreamSnapshot snap = registry.snapshot(sid);
        assertEquals(threadCount * appendsPerThread, snap.contentDeltaCount(),
                "并发追加后 delta 计数应正确");
        assertFalse(snap.content().isEmpty(), "content 不应为空");
    }

    @Test
    @DisplayName("并发追加 reasoning 和 content → snapshot 两个通道独立累加")
    void concurrentReasoningAndContentIndependent() throws Exception {
        String sid = "mixed-concurrent";
        registry.start(sid);

        CountDownLatch latch = new CountDownLatch(2);

        new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                registry.appendContent(sid, "C");
            }
            latch.countDown();
        }).start();

        new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                registry.appendReasoning(sid, "R");
            }
            latch.countDown();
        }).start();

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        LlmStreamSnapshot snap = registry.snapshot(sid);
        assertEquals(50, snap.contentDeltaCount());
        assertEquals(30, snap.reasoningDeltaCount());
    }

    @Test
    @DisplayName("incrementToolCallDelta → toolCallDeltaCount 递增")
    void incrementToolCallDeltaCountsCorrectly() {
        String sid = "tool-stream";
        registry.start(sid);

        registry.incrementToolCallDelta(sid);
        registry.incrementToolCallDelta(sid);
        registry.incrementToolCallDelta(sid);

        LlmStreamSnapshot snap = registry.snapshot(sid);
        assertEquals(3, snap.toolCallDeltaCount());
    }

    @Test
    @DisplayName("start 后 snapshot 返回空状态但 active=true")
    void startCreatesEmptyActiveState() {
        String sid = "fresh";
        registry.start(sid);

        LlmStreamSnapshot snap = registry.snapshot(sid);
        assertNotNull(snap);
        assertTrue(snap.active());
        assertEquals("", snap.content());
        assertEquals("", snap.reasoning());
        assertEquals(0, snap.contentDeltaCount());
        assertEquals(0, snap.reasoningDeltaCount());
        assertEquals(0, snap.toolCallDeltaCount());
    }

    @Test
    @DisplayName("EMPTY 常量不可变且各字段为空")
    void emptySnapshotConstantIsClean() {
        assertEquals("", LlmStreamSnapshot.EMPTY.streamId());
        assertEquals("", LlmStreamSnapshot.EMPTY.content());
        assertEquals("", LlmStreamSnapshot.EMPTY.reasoning());
        assertEquals(0, LlmStreamSnapshot.EMPTY.contentDeltaCount());
        assertEquals(0, LlmStreamSnapshot.EMPTY.reasoningDeltaCount());
        assertEquals(0, LlmStreamSnapshot.EMPTY.toolCallDeltaCount());
        assertFalse(LlmStreamSnapshot.EMPTY.active());
        assertFalse(LlmStreamSnapshot.EMPTY.completed());
        assertNull(LlmStreamSnapshot.EMPTY.error());
        assertFalse(LlmStreamSnapshot.EMPTY.hasAnyContent());
    }
}
