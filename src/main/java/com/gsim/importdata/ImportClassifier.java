package com.gsim.importdata;

/**
 * 导入分类器 — 根据文件名判断目标 ChromaDB collection。
 */
public class ImportClassifier {

    /**
     * 根据文件名判断目标 collection。
     */
    public String classify(String fileName) {
        String lower = fileName.toLowerCase();

        if (containsAny(lower, "world", "lore", "设定", "世界观")) {
            return "world_lore";
        }
        if (containsAny(lower, "rule", "规则", "裁定")) {
            return "rules";
        }
        if (containsAny(lower, "timeline", "时间线", "事件")) {
            return "timeline_events";
        }
        if (containsAny(lower, "faction", "国家", "派系", "势力")) {
            return "factions";
        }
        if (containsAny(lower, "character", "人物", "角色")) {
            return "characters";
        }

        return "world_lore"; // 默认
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
