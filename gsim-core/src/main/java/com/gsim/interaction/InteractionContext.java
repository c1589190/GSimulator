package com.gsim.interaction;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 交互上下文 — 当前会话的运行时状态。
 */
public class InteractionContext {

    private String currentCampaignId;
    private String currentTurnId;
    private final Instant sessionStartedAt;
    private final Map<String, Object> options;

    /**
     * 创建一个新的交互上下文，初始化会话开始时间为当前时刻，选项映射为空。
     */
    public InteractionContext() {
        this.sessionStartedAt = Instant.now();
        this.options = new HashMap<>();
    }

    public String getCurrentCampaignId() {
        return currentCampaignId;
    }

    public void setCurrentCampaignId(String currentCampaignId) {
        this.currentCampaignId = currentCampaignId;
    }

    public String getCurrentTurnId() {
        return currentTurnId;
    }

    public void setCurrentTurnId(String currentTurnId) {
        this.currentTurnId = currentTurnId;
    }

    public Instant getSessionStartedAt() {
        return sessionStartedAt;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    /**
     * 设置运行时选项。
     *
     * @param key   选项键
     * @param value 选项值
     */
    public void setOption(String key, Object value) {
        this.options.put(key, value);
    }

    /**
     * 获取运行时选项值。
     *
     * @param key 选项键
     * @return 选项值，如果不存在则返回 {@code null}
     */
    public Object getOption(String key) {
        return this.options.get(key);
    }
}
