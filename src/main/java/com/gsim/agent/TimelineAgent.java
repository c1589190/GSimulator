package com.gsim.agent;

import com.gsim.chroma.EvidenceBundle;
import com.gsim.llm.LlmClient;
import com.gsim.timeline.TimelineEvent;

import java.util.List;

/**
 * 时间线 Agent — 基于玩家行动和分析结果生成时间线事件。
 */
public class TimelineAgent {

    private final LlmClient llmClient;

    public TimelineAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 生成时间线事件。
     * Phase 8 之前返回占位实现。
     */
    public List<TimelineEvent> generateEvents(
            String campaignId, String turnId,
            List<PlayerActionAnalysis> analyses, EvidenceBundle evidence) {
        // TODO Phase 8: 使用 LLM 生成真实事件
        return List.of();
    }
}
