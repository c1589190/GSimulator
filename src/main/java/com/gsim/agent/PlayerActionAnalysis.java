package com.gsim.agent;

import java.util.List;

/**
 * 玩家行动分析 — PlayerActionAnalyzerAgent 的输出。
 */
public record PlayerActionAnalysis(
        String playerActionId,
        String playerName,
        String summary,
        List<String> declaredActions,
        List<String> impliedActions,
        String politicalIntent,
        String militaryIntent,
        String economicIntent,
        String diplomaticIntent,
        List<String> contradictions,
        List<String> risks,
        List<String> opportunities,
        List<String> requiredAdjudications
) {
}
