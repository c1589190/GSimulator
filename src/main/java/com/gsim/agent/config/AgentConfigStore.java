package com.gsim.agent.config;

import com.gsim.agent.core.AgentConfig;
import com.gsim.resource.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
                        log.info("Loaded agent config from filesystem: {} ({})",
                                config.agentId(), name);
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
                // 重新加载
                reload(agentsDir);
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
                InputStream is = Thread.currentThread().getContextClassLoader()
                        .getResourceAsStream(classpath);
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
            Files.writeString(readme, """
                    Agent 配置目录
                    ==============

                    每个 .json 文件定义一个 Agent。字段说明：

                      agentId              — 唯一标识（如 "orchestrator", "sim", "search"）
                      llmProvider          — 引用的 LLM provider ID（对应 data/llms.json 中的 id）
                      staticSystemPrompt   — 静态系统提示词，不被 FreeMarker 渲染（可选）
                      systemPromptTemplate — FreeMarker 模板路径或文本（可选）
                      systemPrompt         — 兼容旧字段（systemPromptTemplate 不存在时使用）
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

    /** 解析 systemPrompt/userTemplate/staticSystemPrompt 中的 classpath 引用 */
    private AgentConfig resolvePrompts(AgentConfig config) throws IOException {
        // 解析 staticSystemPrompt
        String staticSys = config.staticSystemPrompt();
        if (staticSys != null && staticSys.startsWith("gsim/")) {
            staticSys = ResourceManager.readText(staticSys);
        }
        // 解析 systemPromptTemplate（新字段优先）
        String sys = config.effectiveSystemPromptTemplate();
        if (sys != null && sys.startsWith("gsim/")) {
            sys = ResourceManager.readText(sys);
        }
        String userTpl = config.userTemplate();
        if (userTpl != null && userTpl.startsWith("gsim/")) {
            userTpl = ResourceManager.readText(userTpl);
        }
        return new AgentConfig(config.agentId(), config.llmProvider(),
                staticSys, sys, config.systemPrompt(),
                userTpl, config.toolFilter(), config.maxToolRounds(),
                config.temperature(), config.maxTokens());
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
}
