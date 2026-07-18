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
     * <p>路径约定: {@code gsim/prompts/{agentId}/{sceneId}.md}</p>
     *
     * @param agentId Agent 标识（如 {@code "sim"}、{@code "search"}）
     * @param sceneId 场景标识（如 {@code "system"}、{@code "user"}）
     * @return prompt 模板文本内容
     * @throws IOException 如果资源文件未找到或读取失败
     */
    public static String getAgentPrompt(String agentId, String sceneId) throws IOException {
        return ResourceManager.readText("gsim/prompts/" + agentId + "/" + sceneId + ".md");
    }

    /**
     * 按 agentId + sceneId 加载并渲染 SubAgent 的 prompt 模板。
     *
     * @param agentId Agent 标识
     * @param sceneId 场景标识
     * @param params  模板变量键值对
     * @return 渲染后的 prompt 文本
     * @throws IOException 如果资源文件未找到或读取失败
     */
    public static String renderAgentPrompt(String agentId, String sceneId, Map<String, String> params)
            throws IOException {
        return ResourceManager.renderTemplate("gsim/prompts/" + agentId + "/" + sceneId + ".md", params);
    }

    // ====== 旧 API（@Deprecated，保留兼容） ======

    /**
     * 加载系统 prompt。
     *
     * @return 系统 prompt 文本
     * @throws IOException 如果资源未找到
     * @deprecated 使用 {@link #getAgentPrompt("orchestrator", "system")} 替代
     */
    @Deprecated
    public static String getSystemPrompt() throws IOException {
        return ResourceManager.readText("gsim/prompts/system.md");
    }

    /**
     * 加载 SimAgent 用户模板。
     *
     * @return 模板文本
     * @throws IOException 如果资源未找到
     * @deprecated 已被 SimAgent 替代
     */
    @Deprecated
    public static String getSimUserTemplate() throws IOException {
        return ResourceManager.readText("gsim/prompts/sim-user-template.md");
    }

    /**
     * 加载工具策略 prompt。
     *
     * @return 工具策略文本
     * @throws IOException 如果资源未找到
     * @deprecated 工具策略已内置于 orchestrator-system.md
     */
    @Deprecated
    public static String getToolPolicy() throws IOException {
        return ResourceManager.readText("gsim/prompts/tool-policy.md");
    }

    /**
     * 加载输出合约 prompt。
     *
     * @return 输出合约文本
     * @throws IOException 如果资源未找到
     * @deprecated 输出合约已内置于 orchestrator-system.md
     */
    @Deprecated
    public static String getOutputContract() throws IOException {
        return ResourceManager.readText("gsim/prompts/output-contract.md");
    }

    /**
     * 渲染 SimAgent 用户模板。
     *
     * @param simNote 模拟说明文本
     * @return 渲染后的模板文本
     * @throws IOException 如果资源未找到
     * @deprecated 使用 {@link #renderAgentPrompt("sim", "user", Map.of("sim_note", ...))} 替代
     */
    @Deprecated
    public static String renderSimUserTemplate(String simNote) throws IOException {
        return ResourceManager.renderTemplate(
                "gsim/prompts/sim-user-template.md", Map.of("sim_note", simNote != null ? simNote : ""));
    }
}
