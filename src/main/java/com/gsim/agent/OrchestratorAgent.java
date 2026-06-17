package com.gsim.agent;

import com.gsim.llm.LlmClient;
import com.gsim.task.TaskContext;
import com.gsim.task.TaskPlan;
import com.gsim.task.TaskType;

import java.util.List;

/**
 * Orchestrator Agent — 主协调者。
 * 接收 TaskContext，生成 TaskPlan，协调各专业 Agent 工作。
 */
public class OrchestratorAgent {

    private final LlmClient llmClient;

    public OrchestratorAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 生成任务执行计划。
     * Phase 7 之前返回占位实现。
     */
    public TaskPlan generatePlan(TaskContext context) {
        // TODO Phase 7: 使用 LLM 生成真实的 TaskPlan
        return new TaskPlan(
                context.taskType() != null ? context.taskType() : TaskType.UNKNOWN,
                "Process player actions and generate turn results",
                List.of("KnowledgeRouterAgent", "PlayerActionAnalyzerAgent",
                        "TimelineAgent", "WriterAgent"),
                true,   // needKnowledgeRetrieval
                false,  // needWebResearch
                true,   // needTimelineUpdate
                true,   // needWorldStateUpdate
                "markdown",
                List.of("LLM hallucination", "Incomplete evidence"),
                "Auto-generated plan for Phase 2 stub"
        );
    }
}
