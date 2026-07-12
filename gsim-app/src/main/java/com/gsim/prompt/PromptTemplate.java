package com.gsim.prompt;

/**
 * Prompt 模板 — 从文件系统加载。
 */
public record PromptTemplate(
        String name,
        String version,
        String purpose,
        String templateContent,
        PromptTemplateMetadata metadata
) {
    /**
     * 用变量渲染模板，返回实际 prompt 文本。
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
