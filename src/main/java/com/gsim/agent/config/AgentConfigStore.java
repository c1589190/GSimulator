package com.gsim.agent.config;

import com.gsim.agent.core.AgentConfig;
import com.gsim.resource.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Agent 配置存储 — 从 classpath gsim/agents/{agentId}/config.json 加载配置。
 *
 * <p>支持运行时重新扫描发现新增/删除的 agent 类型。
 */
public class AgentConfigStore {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigStore.class);
    private static final String CONFIG_BASE = "gsim/agents/";
    private static final String[] BUILTIN_AGENTS = {"orchestrator", "sim", "search"};

    private final Map<String, AgentConfig> configs = new LinkedHashMap<>();
    private final Map<String, String> promptCache = new LinkedHashMap<>(); // agentId:sceneId → content

    public AgentConfigStore() {
        reload();
    }

    /** 重新扫描 classpath 加载所有 agent 配置 */
    public void reload() {
        configs.clear();
        for (String agentId : BUILTIN_AGENTS) {
            try {
                String path = CONFIG_BASE + agentId + "/config.json";
                AgentConfig config = AgentConfig.fromClasspath(path);
                // 解析 systemPrompt（可能是 classpath 路径）
                config = resolvePrompts(config);
                configs.put(agentId, config);
                log.info("Loaded agent config: {}", agentId);
            } catch (IOException e) {
                log.warn("Failed to load agent config '{}': {}", agentId, e.getMessage());
            }
        }
    }

    /** 解析 systemPrompt/userTemplate 中的 classpath 引用 */
    private AgentConfig resolvePrompts(AgentConfig config) throws IOException {
        String sys = config.systemPrompt();
        if (sys.startsWith("gsim/")) {
            sys = ResourceManager.readText(sys);
        }
        String userTpl = config.userTemplate();
        if (userTpl != null && userTpl.startsWith("gsim/")) {
            userTpl = ResourceManager.readText(userTpl);
        }
        return new AgentConfig(config.agentId(), sys, userTpl,
                config.toolFilter(), config.maxToolRounds(),
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
