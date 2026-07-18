package com.gsim.prompt;

import java.util.Map;

/**
 * Prompt 渲染上下文 — 包含模板变量值。
 */
public record PromptRenderContext(Map<String, String> variables) {

    /**
     * 使用指定的变量映射创建渲染上下文。
     *
     * @param vars 模板变量键值对映射
     * @return 新的渲染上下文实例
     */
    public static PromptRenderContext of(Map<String, String> vars) {
        return new PromptRenderContext(vars);
    }

    /**
     * 创建空的渲染上下文（不含任何变量）。
     *
     * @return 空的渲染上下文实例
     */
    public static PromptRenderContext empty() {
        return new PromptRenderContext(Map.of());
    }
}
