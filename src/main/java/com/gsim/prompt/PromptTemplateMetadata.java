package com.gsim.prompt;

import java.util.List;

/**
 * Prompt 模板的元数据。
 */
public record PromptTemplateMetadata(
        String name,
        String version,
        String purpose,
        List<String> inputVariables,
        String outputFormat
) {
}
