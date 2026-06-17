package com.gsim.task;

import com.gsim.agent.PlayerActionAnalysis;
import com.gsim.chroma.EvidenceBundle;
import com.gsim.chroma.RetrievalPlan;
import com.gsim.crawler.ResearchDocument;
import com.gsim.crawler.SearchPlan;
import com.gsim.timeline.TimelineEvent;
import com.gsim.world.StateChange;
import com.gsim.agent.WriterOutput;

import java.time.Instant;
import java.util.List;

/**
 * 完整任务日志 — 一次 /run 的完整审计记录。
 */
public record TaskLog(
        String taskId,
        String campaignId,
        String turnId,
        String userInstruction,
        TaskPlan taskPlan,
        RetrievalPlan retrievalPlan,
        EvidenceBundle evidenceBundle,
        SearchPlan searchPlan,
        List<ResearchDocument> researchDocuments,
        List<PlayerActionAnalysis> analyses,
        List<TimelineEvent> timelineEvents,
        List<StateChange> stateChanges,
        WriterOutput writerOutput,
        Instant startedAt,
        Instant finishedAt,
        List<String> errors
) {
}
