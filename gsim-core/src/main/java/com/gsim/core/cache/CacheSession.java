package com.gsim.core.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One LLM conversation session, stored as a JSON file in the caches directory.
 * messages use raw OpenAI format: role, content, tool_calls, tool_call_id.
 */
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
public class CacheSession {

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("createdAt")
    private String createdAt;

    @JsonProperty("previousSessionId")
    private String previousSessionId;

    @JsonProperty("compressionNote")
    private String compressionNote;

    @JsonProperty("messages")
    private List<Map<String, Object>> messages;

    @JsonProperty("toolGroupEvents")
    private List<ToolGroupEvent> toolGroupEvents;

    public CacheSession() {
        this.messages = new ArrayList<>();
        this.toolGroupEvents = new ArrayList<>();
    }

    /**
     * 构造一个 CacheSession 实例。
     *
     * @param agentName Agent 名称
     * @param sessionId 会话 ID（文件名）
     * @param createdAt 创建时间（ISO 时间戳）
     */
    public CacheSession(String agentName, String sessionId, String createdAt) {
        this.agentName = agentName;
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.messages = new ArrayList<>();
        this.toolGroupEvents = new ArrayList<>();
    }

    // getters
    public String agentName() {
        return agentName;
    }

    public String sessionId() {
        return sessionId;
    }

    public String createdAt() {
        return createdAt;
    }

    public String previousSessionId() {
        return previousSessionId;
    }

    public String compressionNote() {
        return compressionNote;
    }

    public List<Map<String, Object>> messages() {
        return messages;
    }

    public List<ToolGroupEvent> toolGroupEvents() {
        return toolGroupEvents;
    }

    // setters (for Jackson)
    public void setAgentName(String v) {
        this.agentName = v;
    }

    public void setSessionId(String v) {
        this.sessionId = v;
    }

    public void setCreatedAt(String v) {
        this.createdAt = v;
    }

    public void setPreviousSessionId(String v) {
        this.previousSessionId = v;
    }

    public void setCompressionNote(String v) {
        this.compressionNote = v;
    }

    public void setMessages(List<Map<String, Object>> v) {
        this.messages = v;
    }

    public void setToolGroupEvents(List<ToolGroupEvent> v) {
        this.toolGroupEvents = v;
    }

    /** 添加一条结构化工具组变更事件。 */
    public void addToolGroupEvent(ToolGroupEvent event) {
        if (this.toolGroupEvents == null) {
            this.toolGroupEvents = new ArrayList<>();
        }
        this.toolGroupEvents.add(event);
    }

    // fluent
    public CacheSession previousSessionId(String v) {
        this.previousSessionId = v;
        return this;
    }

    public CacheSession compressionNote(String v) {
        this.compressionNote = v;
        return this;
    }

    /**
     * 添加一条消息到会话记录中。
     *
     * @param message 符合 OpenAI 格式的消息映射（role, content, tool_calls 等）
     */
    public void addMessage(Map<String, Object> message) {
        this.messages.add(message);
    }

    /**
     * 返回当前会话中的消息数量。
     *
     * @return 消息总数
     */
    public int messageCount() {
        return messages.size();
    }
}
