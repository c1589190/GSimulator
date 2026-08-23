package com.gsim.core.doc;

/**
 * 文档类型 — 统一管理所有 Agent 可读写的文本资产。
 */
public enum DocType {
    CHARACTER("character", "角色设定"),
    SKILL("skill", "Skill / 技能规则"),
    WORLD_STATE("world_state", "世界态势"),
    TEMPLATE("template", "模板"),
    CONTEXT("context", "上下文片段"),
    RULE("rule", "规则文档"),
    BOARD("board", "展示板"),
    TMP("tmp", "暂存"),
    OTHER("other", "其他");

    private final String key;
    private final String label;

    DocType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /**
     * 根据 key 值查找对应的文档类型。
     *
     * @param key 类型键值（不区分大小写）
     * @return 匹配的 DocType 枚举；无匹配时返回 OTHER
     */
    public static DocType fromKey(String key) {
        for (DocType t : values()) {
            if (t.key.equalsIgnoreCase(key)) return t;
        }
        return OTHER;
    }
}
