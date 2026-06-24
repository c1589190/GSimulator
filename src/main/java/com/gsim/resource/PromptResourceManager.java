package com.gsim.resource;

import java.io.IOException;
import java.util.Map;

/**
 * 提示词资源管理器 — 从 classpath gsim/prompts/ 读取 LLM 提示词。
 *
 * <h3>新 API（推荐）</h3>
 * 使用 {@link #getAgentPrompt(String, String)} 按 agentId + sceneId 加载：
 * <pre>{@code
 *   PromptResourceManager.getAgentPrompt("sim", "system")
 *   // → gsim/prompts/sim/system.md
 *   PromptResourceManager.renderAgentPrompt("sim", "user", Map.of("prompt", "..."))
 *   // → gsim/prompts/sim/user.md with {{prompt}} substitution
 * }</pre>
 */
public final class PromptResourceManager {

    private PromptResourceManager() {}

    // ====== 新 API：agentId + sceneId ======

    /**
     * 按 agentId + sceneId 加载 prompt 文件。
     * 路径约定: {@code gsim/prompts/{agentId}/{sceneId}.md}
     */
    public static String getAgentPrompt(String agentId, String sceneId) throws IOException {
        return ResourceManager.readText(
                "gsim/prompts/" + agentId + "/" + sceneId + ".md");
    }

    /**
     * 按 agentId + sceneId 加载并渲染 prompt 模板。
     * 路径约定: {@code gsim/prompts/{agentId}/{sceneId}.md}
     */
    public static String renderAgentPrompt(String agentId, String sceneId,
                                            Map<String, String> params) throws IOException {
        return ResourceManager.renderTemplate(
                "gsim/prompts/" + agentId + "/" + sceneId + ".md", params);
    }

    // ====== 旧 API（@Deprecated，保留兼容） ======

    /** @deprecated 使用 {@link #getAgentPrompt("orchestrator", "system")} 替代 */
    @Deprecated
    public static String getSystemPrompt() throws IOException {
        return ResourceManager.readText("gsim/prompts/system.md");
    }

    /** @deprecated 使用 {@link #getAgentPrompt("orchestrator", "system")} 替代 */
    @Deprecated
    public static String getOrchestratorSystemPrompt() throws IOException {
        return ResourceManager.readText("gsim/prompts/orchestrator-system.md");
    }

    /** @deprecated 已被 SimAgent 替代 */
    @Deprecated
    public static String getSimUserTemplate() throws IOException {
        return ResourceManager.readText("gsim/prompts/sim-user-template.md");
    }

    /** @deprecated 工具策略已内置于 orchestrator-system.md */
    @Deprecated
    public static String getToolPolicy() throws IOException {
        return ResourceManager.readText("gsim/prompts/tool-policy.md");
    }

    /** @deprecated 输出合约已内置于 orchestrator-system.md */
    @Deprecated
    public static String getOutputContract() throws IOException {
        return ResourceManager.readText("gsim/prompts/output-contract.md");
    }

    /** @deprecated 使用 {@link #renderAgentPrompt("sim", "user", Map.of("sim_note", ...))} 替代 */
    @Deprecated
    public static String renderSimUserTemplate(String simNote) throws IOException {
        return ResourceManager.renderTemplate("gsim/prompts/sim-user-template.md",
                Map.of("sim_note", simNote != null ? simNote : ""));
    }
}
