package com.gsim.importdata;

import java.util.List;

/**
 * 导入文档 — 从 import/ 读取的原始资料。
 */
public record ImportDocument(
        String fileName,
        String fileType,         // txt, md, json
        String rawText,
        String cleanedText,
        List<String> chunks,
        String suggestedTitle,
        List<String> suggestedTags,
        String targetCollection
) {
}
