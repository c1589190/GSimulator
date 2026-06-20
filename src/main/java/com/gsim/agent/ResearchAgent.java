package com.gsim.agent;

import com.gsim.crawler.SearchPlan;
import com.gsim.crawler.SearchProvider;
import com.gsim.crawler.ResearchDocument;
import com.gsim.llm.LlmManager;
import com.gsim.task.TaskContext;

import java.util.List;

/**
 * 研究 Agent — 联网搜索、抓取网页、提取正文、总结可信度。
 */
public class ResearchAgent {

    private final LlmManager llmManager;
    private final SearchProvider searchProvider;

    public ResearchAgent(LlmManager llmManager, SearchProvider searchProvider) {
        this.llmManager = llmManager;
        this.searchProvider = searchProvider;
    }

    /**
     * 生成搜索计划。
     * Phase 9 之前返回占位实现。
     */
    public SearchPlan generateSearchPlan(TaskContext context) {
        // TODO Phase 9: 使用 LLM 生成真实的 SearchPlan
        return new SearchPlan(
                "Stub: no web research needed for Phase 2",
                List.of(),
                List.of(),
                List.of(),
                5,
                3
        );
    }

    /**
     * 执行联网研究。
     */
    public List<ResearchDocument> executeResearch(SearchPlan plan) {
        // TODO Phase 9: 真实实现
        return List.of();
    }
}
