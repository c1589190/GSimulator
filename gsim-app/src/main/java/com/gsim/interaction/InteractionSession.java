package com.gsim.interaction;

import com.gsim.app.AppConfig;
import com.gsim.llm.LlmManager;
import com.gsim.tool.ToolRegistry;

/**
 * 交互会话 — 持有所有服务引用和交互上下文。
 */
public class InteractionSession {

    private final InteractionContext context;
    private final AppConfig config;
    private final ToolRegistry toolRegistry;
    private final LlmManager llmClient;

    public InteractionSession(
            InteractionContext context,
            AppConfig config) {
        this(context, config, null, null);
    }

    public InteractionSession(
            InteractionContext context,
            AppConfig config,
            ToolRegistry toolRegistry,
            LlmManager llmClient) {
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
