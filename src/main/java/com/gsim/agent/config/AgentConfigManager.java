package com.gsim.agent.config;

import com.gsim.agent.core.AgentConfig;
import com.gsim.agent.core.ToolFilterConfig;
import com.gsim.util.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Agent 配置管理层 — 封装 agent JSON 文件的读写、字段更新、校验、自动 reload。
 */
public class AgentConfigManager {

    private final AgentConfigStore configStore;
    private final Path agentsDir;

    public AgentConfigManager(AgentConfigStore configStore, Path agentsDir) {
        this.configStore = configStore;
        this.agentsDir = agentsDir;
    }

    /** 列出所有 agent 配置摘要。 */
    public List<Map<String, Object>> listAgents() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentConfig c : configStore.all().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", c.agentId());
            m.put("llmProvider", c.llmProvider());
            m.put("maxToolRounds", c.maxToolRounds());
            m.put("temperature", c.temperature());
            m.put("maxTokens", c.maxTokens());
            m.put("toolFilterMode", c.toolFilter() != null ? c.toolFilter().mode() : "all");
            m.put("hasStaticPrompt", c.staticSystemPrompt() != null && !c.staticSystemPrompt().isBlank());
            m.put("hasTemplate", c.systemPromptTemplate() != null && !c.systemPromptTemplate().isBlank());
            list.add(m);
        }
        return list;
    }

    /** 获取单个 agent 详细配置。 */
    public Map<String, Object> getAgent(String agentId) {
        AgentConfig c = configStore.get(agentId);
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", c.agentId());
        m.put("llmProvider", c.llmProvider());
        m.put("maxToolRounds", c.maxToolRounds());
        m.put("temperature", c.temperature());
        m.put("maxTokens", c.maxTokens());
        if (c.toolFilter() != null) {
            m.put("toolFilter", Map.of(
                    "mode", c.toolFilter().mode(),
                    "allow", c.toolFilter().allow(),
                    "deny", c.toolFilter().deny()));
        }
        if (c.staticSystemPrompt() != null && !c.staticSystemPrompt().isBlank()) {
            String preview = c.staticSystemPrompt();
            if (preview.length() > 200) preview = preview.substring(0, 200) + "...";
            m.put("staticSystemPromptPreview", preview);
        }
        if (c.systemPromptTemplate() != null && !c.systemPromptTemplate().isBlank()) {
            m.put("systemPromptTemplate", c.systemPromptTemplate());
        }
        if (c.userTemplate() != null && !c.userTemplate().isBlank()) {
            m.put("userTemplate", c.userTemplate());
        }
        return m;
    }

    /** 更新 agent 的单个字段。原子写入 + 自动 reload。 */
    public UpdateResult updateAgent(String agentId, String field, String value) {
        AgentConfig old = configStore.get(agentId);
        if (old == null) return UpdateResult.fail("Agent not found: " + agentId);

        Path file = agentsDir.resolve(agentId + ".json");
        if (!Files.exists(file)) {
            return UpdateResult.fail("Agent config file not found: " + file);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> json = JsonUtils.fromJson(Files.readString(file), Map.class);

            switch (field) {
                case "llmProvider" -> json.put("llmProvider", value);
                case "temperature" -> {
                    double t = Double.parseDouble(value);
                    if (t < 0 || t > 2.0)
                        throw new IllegalArgumentException("Temperature must be 0.0-2.0");
                    json.put("temperature", t);
                }
                case "maxTokens" -> {
                    int mt = Integer.parseInt(value);
                    if (mt < 1) throw new IllegalArgumentException("maxTokens must be >= 1");
                    json.put("maxTokens", mt);
                }
                case "maxToolRounds" -> {
                    int mr = Integer.parseInt(value);
                    if (mr < 1) throw new IllegalArgumentException("maxToolRounds must be >= 1");
                    json.put("maxToolRounds", mr);
                }
                case "toolFilter" -> {
                    json.put("toolFilter", parseToolFilter(value));
                }
                case "staticSystemPrompt" -> json.put("staticSystemPrompt", value);
                case "systemPromptTemplate" -> json.put("systemPromptTemplate", value);
                default -> throw new IllegalArgumentException("Unknown field: " + field
                        + ". Valid: llmProvider, temperature, maxTokens, maxToolRounds,"
                        + " toolFilter, staticSystemPrompt, systemPromptTemplate");
            }

            // 原子写入
            Path tmp = file.resolveSibling(agentId + ".json.tmp");
            Files.writeString(tmp, JsonUtils.toJson(json));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            // 重新加载
            configStore.reload(agentsDir);

            return UpdateResult.ok("Updated " + field + " for agent " + agentId);
        } catch (IOException e) {
            return UpdateResult.fail("IO error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return UpdateResult.fail(e.getMessage());
        }
    }

    /** 强制重新加载所有 agent 配置。 */
    public String reload() {
        configStore.reload(agentsDir);
        return "Reloaded " + configStore.agentIds().size() + " agent configs";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToolFilter(String value) {
        Map<String, Object> tf = new LinkedHashMap<>();
        if ("all".equals(value) || "read_only".equals(value)) {
            tf.put("mode", value);
        } else if (value.startsWith("custom:")) {
            tf.put("mode", "custom");
            String rest = value.substring("custom:".length());
            String[] parts = rest.split(":");
            if (parts.length >= 1 && !parts[0].isEmpty()) {
                tf.put("allow", Arrays.asList(parts[0].split(",")));
            }
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                tf.put("deny", Arrays.asList(parts[1].split(",")));
            }
        } else {
            throw new IllegalArgumentException("Invalid toolFilter: " + value
                    + ". Use: all, read_only, or custom:allow1,allow2:deny1,deny2");
        }
        return tf;
    }

    public record UpdateResult(boolean success, String message) {
        public static UpdateResult ok(String msg) { return new UpdateResult(true, msg); }
        public static UpdateResult fail(String msg) { return new UpdateResult(false, msg); }
    }
}
