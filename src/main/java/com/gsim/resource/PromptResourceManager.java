package com.gsim.resource;

import java.io.IOException;
import java.util.Map;

/**
 * 提示词资源管理器。
 *
 * <p>主 Agent（Orchestrator）的 system prompt 由 agents/orchestrator.json 配置。
 * SubAgent 的 prompt 由主 Agent 运行时生成或从 classpath 加载。
 *
 * <h3>API</h3>
 * <pre>{@code
 *   // SubAgent prompt — classpath 读取
 *   PromptResourceManager.getAgentPrompt("sim", "system")
 * }</pre>
 */
public final class PromptResourceManager {

    private static final ClassLoader CL = Thread.currentThread().getContextClassLoader();

    private PromptResourceManager() {}

    // ====== SubAgent prompt（classpath，运行时生成不持久化） ======

    /**
     * 按 agentId + sceneId 加载 SubAgent 的 prompt 模板。
     * 路径约定: {@code gsim/prompts/{agentId}/{sceneId}.md}
     */
    public static String getAgentPrompt(String agentId, String sceneId) throws IOException {
        return ResourceManager.readText(
                "gsim/prompts/" + agentId + "/" + sceneId + ".md");
    }

    /**
     * 按 agentId + sceneId 加载并渲染 SubAgent 的 prompt 模板。
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
