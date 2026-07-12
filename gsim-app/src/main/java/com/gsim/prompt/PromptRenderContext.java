package com.gsim.prompt;

import java.util.Map;

/**
 * Prompt 渲染上下文 — 包含模板变量值。
 */
public record PromptRenderContext(
        Map<String, String> variables
) {
    public static PromptRenderContext of(Map<String, String> vars) {
        return new PromptRenderContext(vars);
    }

    public static PromptRenderContext empty() {
        return new PromptRenderContext(Map.of());
    }
}
