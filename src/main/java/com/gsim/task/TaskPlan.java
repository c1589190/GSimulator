package com.gsim.task;

import java.util.List;

/**
 * 任务计划 — Orchestrator 生成的执行计划。
 */
public record TaskPlan(
        TaskType taskType,
        String goal,
        List<String> requiredAgents,
        boolean needKnowledgeRetrieval,
        boolean needWebResearch,
        boolean needTimelineUpdate,
        boolean needWorldStateUpdate,
        String expectedOutputType,
        List<String> risks,
        String notes
) {
}
