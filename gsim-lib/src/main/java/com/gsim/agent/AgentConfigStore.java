package com.gsim.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 配置存储 — 从 filesystem agents/ 目录（或 classpath gsim/agents/ fallback）加载配置。
 *
 * <h3>加载顺序</h3>
 * <ol>
 *   <li>扫描 filesystem {@code agentsDir/*.json} → 加载所有 Agent 配置</li>
 *   <li>若未找到 → 从 classpath {@code gsim/agents/} 复制到 agents/ 作为模板</li>
 *   <li>支持运行时 {@link #reload(Path)} 重新扫描</li>
 * </ol>
 */
public class AgentConfigStore {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigStore.class);
    private static final String CLASSPATH_BASE = "gsim/agents/";
    private static final String[] BUILTIN_AGENTS = {"orchestrator", "sim", "search"};

    private final Map<String, AgentConfig> configs = new LinkedHashMap<>();
    private Path agentsDir;

    public AgentConfigStore() {
        // 无参构造 — 后续调用 reload(Path) 初始化
    }

    /**
     * 从 agents/ 目录加载所有 Agent 配置。
     * 若目录为空或不存在 → 从 classpath 复制内置模板。
     */
    /**
     * 加载或重新加载 agents/ 目录下的所有 Agent 配置。
     * 若目录为空或不存在，则从 classpath 复制内置模板。
     *
     * @param agentsDir Agent 配置文件的目录路径
     */
    public void reload(Path agentsDir) {
        this.agentsDir = agentsDir;
        configs.clear();

        try {
            Files.createDirectories(agentsDir);

            // 1. 扫描 agents/ 目录下的 JSON 文件
            boolean found = false;
            try (Stream<Path> files = Files.list(agentsDir)) {
                for (Path file : files.sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".json")) continue;
                    try {
                        String raw = Files.readString(file);
                        AgentConfig config = AgentConfig.fromJson(raw);
                        config = resolvePrompts(config);
                        configs.put(config.agentId(), config);
                        log.info("Loaded agent config from filesystem: {} ({})", config.agentId(), name);
                        found = true;
                    } catch (Exception e) {
                        log.warn("Failed to load agent config '{}': {}", name, e.getMessage());
                    }
                }
            }

            // 2. Fallback: 从 classpath 复制内置模板
            if (!found) {
                log.info("No agent configs found in {}, copying built-in templates...", agentsDir);
                copyBuiltinsFromClasspath(agentsDir);
                // 检查复制是否成功 — 若 classpath 无资源则走 classpath 直接加载
                boolean copied = false;
                try (Stream<Path> after = Files.list(agentsDir)) {
                    copied = after.anyMatch(f -> f.getFileName().toString().endsWith(".json"));
                }
                if (copied) {
                    reload(agentsDir);
                } else {
                    log.warn("No built-in agents available on classpath, loading from classpath directly");
                    loadFromClasspath();
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan agents dir: {}", e.getMessage(), e);
            // 最后兜底：直接从 classpath 加载
            loadFromClasspath();
        }
    }

    /** 从 classpath 加载内置 agent 配置（兜底方案）。 */
    private void loadFromClasspath() {
        configs.clear();
        for (String agentId : BUILTIN_AGENTS) {
            try {
                String path = CLASSPATH_BASE + agentId + "/config.json";
                AgentConfig config = AgentConfig.fromClasspath(path);
                config = resolvePrompts(config);
                configs.put(agentId, config);
                log.info("Loaded agent config from classpath: {}", agentId);
            } catch (IOException e) {
                log.warn("Failed to load agent config '{}': {}", agentId, e.getMessage());
            }
        }
    }

    /** 从 classpath 复制内置模板到 agents/ 目录。 */
    private void copyBuiltinsFromClasspath(Path agentsDir) throws IOException {
        for (String agentId : BUILTIN_AGENTS) {
            try {
                String classpath = CLASSPATH_BASE + agentId + "/config.json";
                InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpath);
                if (is == null) {
                    log.warn("Built-in agent config not found: {}", classpath);
                    continue;
                }
                String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                // 在 JSON 中添加 llmProvider 默认字段（如果不存在）
                String enriched = ensureField(raw, "llmProvider", "base");
                Path target = agentsDir.resolve(agentId + ".json");
                Files.writeString(target, enriched);
                log.info("Copied built-in agent template: {}", target);
            } catch (IOException e) {
                log.warn("Failed to copy built-in agent '{}': {}", agentId, e.getMessage());
            }
        }

        // 生成一个注释文件提示用户
        Path readme = agentsDir.resolve("_README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(
                    readme,
                    """
                    Agent 配置目录
                    ==============

                    每个 .json 文件定义一个 Agent。字段说明：

                      agentId              — 唯一标识（如 "orchestrator", "sim", "search"）
                      llmProvider          — 引用的 LLM provider ID（对应 data/llms.json 中的 id）
                      staticSystemPrompt   — 静态系统提示词，直接定义 Agent 行为（可选）
                      systemPrompt         — 兼容旧字段（staticSystemPrompt 为空时使用）
                      userTemplate         — 用户 prompt 模板路径或文本（可选）
                      toolFilter           — { "mode": "all" | "read_only" | "custom", "allow": [...], "deny": [...] }
                      maxToolRounds        — 最大工具调用轮数
                      temperature          — LLM 温度参数
                      maxTokens            — LLM 最大输出 token

                    内置 Agent 类型：
                      orchestrator — 主控 Agent，可管理 SubAgent
                      sim          — 推演 SubAgent（只读工具）
                      search       — 搜索 SubAgent（只读工具）

                    你可以添加自定义 Agent（只需新建 .json 文件）。
                    """);
        }
    }

    /** 确保 JSON 字符串中包含指定字段（简单字符串注入，不破坏原 JSON）。 */
    private static String ensureField(String json, String fieldName, String defaultValue) {
        if (json.contains("\"" + fieldName + "\"")) return json;
        // 在第一个 { 后插入
        int idx = json.indexOf('{');
        if (idx < 0) return json;
        return json.substring(0, idx + 1)
                + "\n  \"" + fieldName + "\": \"" + defaultValue + "\","
                + json.substring(idx + 1);
    }

    /** Prompt 内容直接存储在 JSON 字段中，无需解析 classpath 引用。 */
    private AgentConfig resolvePrompts(AgentConfig config) {
        return config;
    }

    public AgentConfig get(String agentId) {
        return configs.get(agentId);
    }

    public Map<String, AgentConfig> all() {
        return new LinkedHashMap<>(configs);
    }

    public Set<String> agentIds() {
        return configs.keySet();
    }

    /**
     * 创建或更新 Agent 配置，写入 JSON 文件到 agents 目录。
     *
     * @param config 要保存的 Agent 配置对象
     * @return 保存成功返回 true，agentsDir 未初始化时返回 false
     * @throws IOException 写入文件失败时抛出
     */
    public boolean saveConfig(AgentConfig config) throws IOException {
        if (agentsDir == null) return false;
        Files.createDirectories(agentsDir);
        Path target = agentsDir.resolve(config.agentId() + ".json");
        Map<String, Object> jsonMap = new LinkedHashMap<>();
        jsonMap.put("agentId", config.agentId());
        jsonMap.put("llmProvider", config.llmProvider() != null ? config.llmProvider() : "base");
        jsonMap.put("staticSystemPrompt", config.staticSystemPrompt() != null ? config.staticSystemPrompt() : "");
        jsonMap.put("systemPrompt", config.systemPrompt() != null ? config.systemPrompt() : "");
        jsonMap.put("userTemplate", config.userTemplate() != null ? config.userTemplate() : "");
        jsonMap.put("maxToolRounds", config.maxToolRounds());
        jsonMap.put("temperature", config.temperature());
        jsonMap.put("maxTokens", config.maxTokens());
        // Serialize toolFilter if present
        if (config.toolFilter() != null) {
            Map<String, Object> tf = new LinkedHashMap<>();
            tf.put(
                    "mode",
                    config.toolFilter().mode() != null ? config.toolFilter().mode() : "all");
            tf.put(
                    "allow",
                    config.toolFilter().allow() != null ? config.toolFilter().allow() : List.of());
            tf.put(
                    "deny",
                    config.toolFilter().deny() != null ? config.toolFilter().deny() : List.of());
            jsonMap.put("toolFilter", tf);
        }
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(jsonMap);
        Files.writeString(target, json);
        configs.put(config.agentId(), config);
        return true;
    }

    /**
     * 删除指定 Agent 的配置文件。
     *
     * @param agentId 要删除的 Agent ID
     * @return 文件存在且删除成功返回 true，否则返回 false
     * @throws IOException 删除文件失败时抛出
     */
    public boolean deleteConfig(String agentId) throws IOException {
        if (agentsDir == null) return false;
        Path target = agentsDir.resolve(agentId + ".json");
        boolean deleted = Files.deleteIfExists(target);
        if (deleted) configs.remove(agentId);
        return deleted;
    }
}
