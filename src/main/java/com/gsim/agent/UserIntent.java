package com.gsim.agent;

/**
 * 用户意图分类 — 用于工具路由决策。
 */
public enum UserIntent {

    /** 查看/列出/确认玩家行动 */
    PLAYER_ACTION_QUERY,

    /** 短推/复写/重写/改写已有玩家行动 */
    SHORT_POST_REWRITE,

    /** 搜索知识库 */
    KNOWLEDGE_SEARCH,

    /** 写入/更新知识库 */
    KNOWLEDGE_WRITE,

    /** 结算 + 下一回合 */
    NEXT_TURN_SETTLE,

    /** 世界推演 */
    WORLD_SIM,

    /** 状态检查 */
    STATUS_CHECK,

    /** 未识别 */
    GENERAL;

    /** 短推/复写 触发词 */
    private static final String[] SHORT_POST_TRIGGERS = {
            "短推", "推文", "复写", "重写", "改写", "整理成推文"
    };

    /** 知识写入 触发词 */
    private static final String[] KNOWLEDGE_WRITE_TRIGGERS = {
            "写入知识库", "记录到知识库", "保存为事实", "更新知识库",
            "保存到知识库", "导入知识库"
    };

    /** 结算 触发词 */
    private static final String[] NEXT_TURN_TRIGGERS = {
            "保存结算", "进入下一回合", "创建下一回合", "next turn",
            "结算并进入"
    };

    /**
     * 根据用户输入推断意图。
     */
    public static UserIntent infer(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return GENERAL;
        }
        String lower = userInput.toLowerCase().trim();

        // 短推/复写 检测
        for (String trigger : SHORT_POST_TRIGGERS) {
            if (lower.contains(trigger)) {
                return SHORT_POST_REWRITE;
            }
        }

        // 知识写入
        for (String trigger : KNOWLEDGE_WRITE_TRIGGERS) {
            if (lower.contains(trigger)) {
                return KNOWLEDGE_WRITE;
            }
        }

        // 结算下一回合
        for (String trigger : NEXT_TURN_TRIGGERS) {
            if (lower.contains(trigger)) {
                return NEXT_TURN_SETTLE;
            }
        }

        // 玩家行动查询
        if (lower.contains("有没有玩家行动")
                || lower.contains("当前回合有没有")
                || lower.contains("列出玩家行动")
                || lower.contains("查看行动")
                || lower.contains("当前回合行动")
                || lower.contains("有什么行动")
                || (lower.contains("玩家行动") && (lower.contains("查看") || lower.contains("告诉")
                        || lower.contains("有没有") || lower.contains("列出")))
                || lower.contains("player action")) {
            return PLAYER_ACTION_QUERY;
        }

        // 知识搜索
        if (lower.contains("搜索")
                || lower.contains("查一下")
                || lower.contains("有没有关于")
                || lower.contains("知不知道")
                || lower.contains("wiki")
                || lower.contains("资料")) {
            return KNOWLEDGE_SEARCH;
        }

        return GENERAL;
    }
}
