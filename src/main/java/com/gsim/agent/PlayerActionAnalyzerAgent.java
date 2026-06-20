package com.gsim.agent;

import com.gsim.campaign.PlayerAction;
import com.gsim.chroma.EvidenceBundle;
import com.gsim.llm.LlmManager;

import java.util.List;

/**
 * 玩家行动分析 Agent — 分析玩家行动中的意图、矛盾、风险、机会。
 */
public class PlayerActionAnalyzerAgent {

    private final LlmManager llmManager;

    public PlayerActionAnalyzerAgent(LlmManager llmManager) {
        this.llmManager = llmManager;
    }

    /**
     * 分析玩家行动。
     * Phase 8 之前返回占位实现。
     */
    public List<PlayerActionAnalysis> analyze(List<PlayerAction> actions, EvidenceBundle evidence) {
        // TODO Phase 8: 使用 LLM 进行真实分析
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }

        return actions.stream()
                .map(a -> new PlayerActionAnalysis(
                        a.id(),
                        a.playerName(),
                        "Stub analysis for: " + a.content(),
                        List.of(a.content()),
                        List.of(),
                        "unknown",
                        "unknown",
                        "unknown",
                        "unknown",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                ))
                .toList();
    }
}
