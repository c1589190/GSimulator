package com.gsim.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * LLM 流式状态注册表。
 * 按 streamId 分流管理所有活跃的流式输出状态。
 * 线程安全，支持 CLI / WebUI 并发读取。
 */
public class LlmStreamStateRegistry {

    private final ConcurrentMap<String, LlmStreamState> states = new ConcurrentHashMap<>();

    // ---- 生命周期 ----

    /** 创建一个新的流式状态，返回 streamId。 */
    public LlmStreamState start(String streamId) {
        LlmStreamState state = new LlmStreamState(streamId);
        states.put(streamId, state);
        return state;
    }

    /** 追加 reasoning delta。若无此 streamId 则静默忽略。 */
    public void appendReasoning(String streamId, String delta) {
        if (streamId == null || delta == null || delta.isEmpty()) return;
        LlmStreamState s = states.get(streamId);
        if (s != null) s.appendReasoning(delta);
    }

    /** 追加 content delta。若无此 streamId 则静默忽略。 */
    public void appendContent(String streamId, String delta) {
        if (streamId == null || delta == null || delta.isEmpty()) return;
        LlmStreamState s = states.get(streamId);
        if (s != null) s.appendContent(delta);
    }

    /** 增加 tool_call delta 计数。若无此 streamId 则静默忽略。 */
    public void incrementToolCallDelta(String streamId) {
        if (streamId == null) return;
        LlmStreamState s = states.get(streamId);
        if (s != null) s.incrementToolCallDelta();
    }

    /** 标记流式完成。 */
    public void complete(String streamId) {
        if (streamId == null) return;
        LlmStreamState s = states.get(streamId);
        if (s != null) s.complete();
    }

    /** 标记流式失败。 */
    public void fail(String streamId, String error) {
        if (streamId == null) return;
        LlmStreamState s = states.get(streamId);
        if (s != null) s.fail(error);
    }

    /** 获取快照。若无此 streamId 返回 {@link LlmStreamSnapshot#EMPTY}。 */
    public LlmStreamSnapshot snapshot(String streamId) {
        if (streamId == null) return LlmStreamSnapshot.EMPTY;
        LlmStreamState s = states.get(streamId);
        return s != null ? s.snapshot() : LlmStreamSnapshot.EMPTY;
    }

    /** 移除流式状态（流式结束后调用）。 */
    public void remove(String streamId) {
        if (streamId != null) states.remove(streamId);
    }

    /** 活跃的流式状态数量（调试用）。 */
    public int activeCount() {
        return (int) states.values().stream().filter(LlmStreamState::isActive).count();
    }
}
