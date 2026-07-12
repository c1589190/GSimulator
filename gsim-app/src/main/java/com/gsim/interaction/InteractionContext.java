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

    public void setOption(String key, Object value) {
        this.options.put(key, value);
    }

    public Object getOption(String key) {
        return this.options.get(key);
    }
}
