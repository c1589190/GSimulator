package com.gsim.agent;

import com.gsim.llm.LlmManager;

import java.util.List;

/**
 * 出文 Agent — 基于分析结果生成公开推文、战报、裁定文。
 */
public class WriterAgent {

    private final LlmManager llmManager;

    public WriterAgent(LlmManager llmManager) {
        this.llmManager = llmManager;
    }

    /**
     * 生成出文结果。
     * Phase 10 之前返回占位实现。
     */
    public WriterOutput generateOutput(WriterRequest request) {
        // TODO Phase 10: 使用 LLM 生成真实输出
        return new WriterOutput(
                "Stub Report",
                "Turn results are not yet implemented. (Phase 10 stub)",
                "No private notes yet.",
                List.of(),
                List.of("This is a stub output from Phase 2.")
        );
    }
}
