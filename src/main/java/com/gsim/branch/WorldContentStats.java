package com.gsim.branch;

import com.gsim.player.PlayerProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界内容统计 — 实体/玩家/规则/世界观概况。
 */
public record WorldContentStats(
        /** 实体数量（entities.md 中 ## entity. 标题数）。 */
        int entityCount,

        /** 正式玩家数量（排除模板示例）。 */
        int playerCount,

        /** 规则章节数（rules.md 中 ## 标题数）。 */
        int ruleSectionCount,

        /** 世界观章节数（world.md 中 ## 标题数）。 */
        int worldSectionCount,

        /** 实体名称列表。 */
        List<String> entityNames,

        /** 玩家名称列表。 */
        List<String> playerNames,

        /** 是否仅有模板实体（entity.player.a/b 等）。 */
        boolean onlyTemplateEntities,

        /** 是否仅有模板示例玩家。 */
        boolean onlyTemplatePlayers
) {
    public static WorldContentStats empty() {
        return new WorldContentStats(0, 0, 0, 0, List.of(), List.of(), true, true);
    }

    /** 根据实体正文和玩家列表构建统计。 */
    public static WorldContentStats from(String entitiesBody, List<PlayerProfile> allPlayers) {
        // 统计 entities.md 中的 ## entity. 标题
        List<String> eNames = new ArrayList<>();
        int eCount = 0;
        boolean onlyTemplate = true;
        for (String line : entitiesBody.split("\n")) {
            String t = line.trim();
            if (t.startsWith("## entity.")) {
                eCount++;
                String eName = t.substring("## entity.".length()).trim();
                eNames.add(eName);
                // player.a / player.b 是模板占位
                if (!eName.equals("player.a") && !eName.equals("player.b")) {
                    onlyTemplate = false;
                }
            }
        }
        if (eCount == 0) onlyTemplate = false;

        // 统计真实玩家（过滤模板示例）
        List<String> pNames = new ArrayList<>();
        int pCount = 0;
        boolean onlyTemplatePlayers = true;
        for (PlayerProfile p : allPlayers) {
            if ("示例玩家".equals(p.name())) {
                onlyTemplatePlayers = allPlayers.size() == 1;
                continue;
            }
            pCount++;
            pNames.add(p.name());
            onlyTemplatePlayers = false;
        }

        return new WorldContentStats(eCount, pCount, 0, 0,
                eNames, pNames, onlyTemplate, onlyTemplatePlayers);
    }

    /** 追加规则和世界章节统计。 */
    public WorldContentStats withSectionCounts(int ruleSecs, int worldSecs) {
        return new WorldContentStats(entityCount, playerCount, ruleSecs, worldSecs,
                entityNames, playerNames, onlyTemplateEntities, onlyTemplatePlayers);
    }
}
