package com.gsim.doc;

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
    OTHER("other", "其他");

    private final String key;
    private final String label;

    DocType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() { return key; }
    public String label() { return label; }

    public static DocType fromKey(String key) {
        for (DocType t : values()) {
            if (t.key.equalsIgnoreCase(key)) return t;
        }
        return OTHER;
    }
}
