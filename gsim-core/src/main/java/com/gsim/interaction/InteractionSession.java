package com.gsim.interaction;

import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.app.AppConfig;
import com.gsim.llm.LlmManager;

/**
 * 交互会话 — 持有所有服务引用和交互上下文。
 */
public class InteractionSession {

    private final InteractionContext context;
    private final AppConfig config;
    private final ToolRegistry toolRegistry;
    private final LlmManager llmClient;

    /**
     * 创建交互会话，使用默认的空工具注册表和 LLM 客户端。
     *
     * @param context 交互上下文
     * @param config  应用配置
     */
    public InteractionSession(InteractionContext context, AppConfig config) {
        this(context, config, null, null);
    }

    /**
     * 创建完整的交互会话。
     *
     * @param context     交互上下文
     * @param config      应用配置
     * @param toolRegistry 工具注册表，可为 {@code null}
     * @param llmClient    LLM 管理器，可为 {@code null}
     */
    public InteractionSession(
            InteractionContext context, AppConfig config, ToolRegistry toolRegistry, LlmManager llmClient) {
        this.context = context;
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient;
    }

    public InteractionContext getContext() {
        return context;
    }

    public AppConfig getConfig() {
        return config;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public LlmManager getLlmManager() {
        return llmClient;
    }
}
