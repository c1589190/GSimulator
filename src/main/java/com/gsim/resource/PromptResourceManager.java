package com.gsim.resource;

import java.io.IOException;
import java.util.Map;

/**
 * 提示词资源管理器 — 从 classpath gsim/prompts/ 读取 LLM 提示词。
 */
public final class PromptResourceManager {

    private PromptResourceManager() {}

    public static String getSystemPrompt() throws IOException {
        return ResourceManager.readText("gsim/prompts/system.md");
    }

    public static String getOrchestratorSystemPrompt() throws IOException {
        return ResourceManager.readText("gsim/prompts/orchestrator-system.md");
    }

    public static String getSimUserTemplate() throws IOException {
        return ResourceManager.readText("gsim/prompts/sim-user-template.md");
    }

    public static String getToolPolicy() throws IOException {
        return ResourceManager.readText("gsim/prompts/tool-policy.md");
    }

    public static String getOutputContract() throws IOException {
        return ResourceManager.readText("gsim/prompts/output-contract.md");
    }

    /** 渲染 sim user 模板，替换 {{sim_note}}。 */
    public static String renderSimUserTemplate(String simNote) throws IOException {
        return ResourceManager.renderTemplate("gsim/prompts/sim-user-template.md",
                Map.of("sim_note", simNote != null ? simNote : ""));
    }
}
