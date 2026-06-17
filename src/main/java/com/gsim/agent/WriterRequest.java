package com.gsim.agent;

import com.gsim.chroma.EvidenceBundle;
import com.gsim.task.TaskContext;
import com.gsim.timeline.TimelineEvent;
import com.gsim.world.StateChange;

import java.util.List;

/**
 * 出文请求 — 包含 WriterAgent 所需的全部上下文。
 */
public record WriterRequest(
        TaskContext taskContext,
        EvidenceBundle evidenceBundle,
        List<PlayerActionAnalysis> analyses,
        List<TimelineEvent> timelineEvents,
        List<StateChange> stateChanges,
        String styleInstruction,
        String outputFormat
) {
}
