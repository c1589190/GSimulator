package com.gsim.agent;

import com.gsim.chroma.RetrievalPlan;
import com.gsim.chroma.RetrievalQuery;
import com.gsim.llm.LlmManager;
import com.gsim.task.TaskContext;

import java.util.List;
import java.util.Map;

/**
 * 知识路由 Agent — 分析任务上下文，生成 ChromaDB 检索计划。
 */
public class KnowledgeRouterAgent {

    private final LlmManager llmManager;

    public KnowledgeRouterAgent(LlmManager llmManager) {
        this.llmManager = llmManager;
    }

    /**
     * 生成检索计划。
     * Phase 5 之前返回占位实现。
     */
    public RetrievalPlan generateRetrievalPlan(TaskContext context) {
        // TODO Phase 5: 使用 LLM 生成真实的 RetrievalPlan
        return new RetrievalPlan(
                "Retrieve relevant world lore and character information",
                List.of(
                        new RetrievalQuery("world_lore", "world setting background lore",
                                Map.of("campaignId", context.campaignId()), 5,
                                "Get world context"),
                        new RetrievalQuery("characters", "important characters",
                                Map.of("campaignId", context.campaignId()), 5,
                                "Get character profiles")
                ),
                false,
                null
        );
    }
}
