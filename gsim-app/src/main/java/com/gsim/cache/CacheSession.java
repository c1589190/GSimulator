package com.gsim.cache;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One LLM conversation session, stored as a JSON file in worlds/{worldId}/caches/.
 * messages use raw OpenAI format: role, content, tool_calls, tool_call_id.
 */
@JsonDeserialize
public class CacheSession {

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("worldId")
    private String worldId;

    @JsonProperty("nodeId")
    private String nodeId;

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

    public CacheSession() {
        this.messages = new ArrayList<>();
    }

    public CacheSession(String agentName, String worldId, String nodeId,
                        String sessionId, String createdAt) {
        this.agentName = agentName;
        this.worldId = worldId;
        this.nodeId = nodeId;
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.messages = new ArrayList<>();
    }

    // getters
    public String agentName() { return agentName; }
    public String worldId() { return worldId; }
    public String nodeId() { return nodeId; }
    public String sessionId() { return sessionId; }
    public String createdAt() { return createdAt; }
    public String previousSessionId() { return previousSessionId; }
    public String compressionNote() { return compressionNote; }
    public List<Map<String, Object>> messages() { return messages; }

    // setters (for Jackson)
    public void setAgentName(String v) { this.agentName = v; }
    public void setWorldId(String v) { this.worldId = v; }
    public void setNodeId(String v) { this.nodeId = v; }
    public void setSessionId(String v) { this.sessionId = v; }
    public void setCreatedAt(String v) { this.createdAt = v; }
    public void setPreviousSessionId(String v) { this.previousSessionId = v; }
    public void setCompressionNote(String v) { this.compressionNote = v; }
    public void setMessages(List<Map<String, Object>> v) { this.messages = v; }

    // fluent
    public CacheSession previousSessionId(String v) { this.previousSessionId = v; return this; }
    public CacheSession compressionNote(String v) { this.compressionNote = v; return this; }

    public void addMessage(Map<String, Object> message) {
        this.messages.add(message);
    }

    public int messageCount() {
        return messages.size();
    }
}
