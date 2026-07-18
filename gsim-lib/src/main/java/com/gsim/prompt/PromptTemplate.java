package com.gsim.prompt;

/**
 * Prompt 模板 — 从文件系统加载。
 */
public record PromptTemplate(
        String name, String version, String purpose, String templateContent, PromptTemplateMetadata metadata) {
    /**
     * 使用提供的上下文变量渲染模板，替换所有 {@code {{key}}} 占位符。
     *
     * @param context 渲染上下文，包含变量键值对
     * @return 渲染后的 prompt 文本
     */
    public String render(PromptRenderContext context) {
        String result = templateContent;
        if (context.variables() != null) {
            for (var entry : context.variables().entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }
        return result;
    }
}
