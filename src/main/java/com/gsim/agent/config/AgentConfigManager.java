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
                default -> throw new IllegalArgumentException("Unknown field: " + field
                        + ". Valid: llmProvider, temperature, maxTokens, maxToolRounds,"
                        + " toolFilter, staticSystemPrompt");
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

    /** 创建新的 Agent 配置（从 JSON body 解析并写入文件）。 */
    public UpdateResult createAgent(String jsonBody) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JsonUtils.fromJson(jsonBody, Map.class);
            String agentId = (String) body.get("agentId");
            if (agentId == null || agentId.isBlank()) {
                return UpdateResult.fail("agentId is required");
            }
            if (!agentId.matches("[a-zA-Z0-9_\\-]+")) {
                return UpdateResult.fail("Invalid agentId: " + agentId);
            }

            Path file = agentsDir.resolve(agentId + ".json");
            if (Files.exists(file)) {
                return UpdateResult.fail("Agent config already exists: " + agentId);
            }

            // 构建 agent config JSON
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("agentId", agentId);
            json.put("llmProvider", body.getOrDefault("llmProvider", "base"));
            json.put("temperature", body.getOrDefault("temperature", 0.3));
            json.put("maxTokens", body.getOrDefault("maxTokens", 2048));
            json.put("maxToolRounds", body.getOrDefault("maxToolRounds", 16));

            String toolFilterMode = (String) body.getOrDefault("toolFilterMode", "read_only");
            if (body.containsKey("toolFilter")) {
                json.put("toolFilter", body.get("toolFilter"));
            } else {
                json.put("toolFilter", Map.of("mode", toolFilterMode));
            }

            if (body.containsKey("staticSystemPrompt")) {
                json.put("staticSystemPrompt", body.get("staticSystemPrompt"));
            } else if (body.containsKey("systemPrompt")) {
                json.put("systemPrompt", body.get("systemPrompt"));
            }
            if (body.containsKey("userTemplate")) {
                json.put("userTemplate", body.get("userTemplate"));
            }

            Files.createDirectories(agentsDir);
            Files.writeString(file, JsonUtils.toJson(json));
            configStore.reload(agentsDir);

            return UpdateResult.ok("Created agent config: " + agentId);
        } catch (IOException e) {
            return UpdateResult.fail("IO error: " + e.getMessage());
        } catch (Exception e) {
            return UpdateResult.fail("Failed to create agent: " + e.getMessage());
        }
    }

    /** 删除 Agent 配置。不允许删除 orchestrator。 */
    public UpdateResult deleteAgent(String agentId) {
        if ("orchestrator".equals(agentId)) {
            return UpdateResult.fail("Cannot delete orchestrator agent config");
        }
        AgentConfig config = configStore.get(agentId);
        if (config == null) return UpdateResult.fail("Agent not found: " + agentId);

        Path file = agentsDir.resolve(agentId + ".json");
        try {
            Files.deleteIfExists(file);
            configStore.reload(agentsDir);
            return UpdateResult.ok("Deleted agent config: " + agentId);
        } catch (IOException e) {
            return UpdateResult.fail("IO error: " + e.getMessage());
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
