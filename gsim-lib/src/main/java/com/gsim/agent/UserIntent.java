package com.gsim.agent;

import java.util.Locale;

/**
 * 用户意图分类 — 用于工具路由决策。
 */
public enum UserIntent {

    /** 查看/列出/确认玩家行动 */
    PLAYER_ACTION_QUERY,

    /** 短推/复写/重写/改写已有玩家行动 */
    SHORT_POST_REWRITE,

    /** 搜索世界信息 */
    WORLDINFO_SEARCH,

    /** 写入/更新世界信息 */
    WORLDINFO_WRITE,

    /** 结算 + 下一回合 */
    NEXT_TURN_SETTLE,

    /** 世界推演 */
    WORLD_SIM,

    /** 状态检查 */
    STATUS_CHECK,

    /** 未识别 */
    GENERAL;

    /** 短推/复写 触发词 */
    private static final String[] SHORT_POST_TRIGGERS = {"短推", "推文", "复写", "重写", "改写", "整理成推文"};

    /** 世界信息写入 触发词 */
    private static final String[] WORLDINFO_WRITE_TRIGGERS = {"写入元素", "记录到世界", "保存为事实", "更新世界信息", "写入世界", "记录事实"};

    /** 结算 触发词 */
    private static final String[] NEXT_TURN_TRIGGERS = {"保存结算", "进入下一回合", "创建下一回合", "next turn", "结算并进入"};

    /**
     * 根据用户输入文本推断其意图分类。
     *
     * <p>通过关键字匹配识别以下意图：
     * <ul>
     *   <li>{@link #SHORT_POST_REWRITE} — 短推/复写/改写</li>
     *   <li>{@link #WORLDINFO_WRITE} — 写入世界信息</li>
     *   <li>{@link #NEXT_TURN_SETTLE} — 结算进入下一回合</li>
     *   <li>{@link #PLAYER_ACTION_QUERY} — 查看玩家行动</li>
     *   <li>{@link #WORLDINFO_SEARCH} — 搜索世界信息</li>
     *   <li>{@link #GENERAL} — 未识别或空白输入</li>
     * </ul>
     *
     * @param userInput 用户输入文本
     * @return 推断出的意图枚举值，若无匹配返回 {@link #GENERAL}
     */
    public static UserIntent infer(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return GENERAL;
        }
        String lower = userInput.toLowerCase(Locale.ROOT).trim();

        // 短推/复写 检测
        for (String trigger : SHORT_POST_TRIGGERS) {
            if (lower.contains(trigger)) {
                return SHORT_POST_REWRITE;
            }
        }

        // 世界信息写入
        for (String trigger : WORLDINFO_WRITE_TRIGGERS) {
            if (lower.contains(trigger)) {
                return WORLDINFO_WRITE;
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
                || (lower.contains("玩家行动")
                        && (lower.contains("查看")
                                || lower.contains("告诉")
                                || lower.contains("有没有")
                                || lower.contains("列出")))
                || lower.contains("player action")) {
            return PLAYER_ACTION_QUERY;
        }

        // 世界信息搜索
        if (lower.contains("搜索")
                || lower.contains("查一下")
                || lower.contains("有没有关于")
                || lower.contains("知不知道")
                || lower.contains("wiki")
                || lower.contains("资料")) {
            return WORLDINFO_SEARCH;
        }

        return GENERAL;
    }
}
