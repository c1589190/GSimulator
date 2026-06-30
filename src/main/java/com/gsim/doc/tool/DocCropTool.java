package com.gsim.doc.tool;

import com.gsim.doc.DocStore;
import com.gsim.doc.Document;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档上下文裁剪工具 — 从源文档中按行范围提取内容，支持关键词遮蔽和选择性替换。
 *
 * <p>GM 用此工具为不同角色生成差异化视野上下文。
 *
 * <h3>执行顺序</h3>
 * <ol>
 *   <li>按 lines 保留指定行范围（基于原始文档行号）</li>
 *   <li>rename — 选择性替换专名（两遍替换，避免重叠）</li>
 *   <li>mask_words — 关键词遮蔽为 ***</li>
 *   <li>mask_lines — 整行遮蔽（mask_lines 中的行号是原始文档行号）</li>
 * </ol>
 */
public final class DocCropTool implements AgentTool {

    private final DocStore store;
    private final com.gsim.doc.DocCacheManager cacheManager;

    public DocCropTool(DocStore store, com.gsim.doc.DocCacheManager cacheManager) {
        this.store = store;
        this.cacheManager = cacheManager;
    }

    @Override
    public String name() { return "doc_crop"; }

    @Override
    public String description() {
        return """
                从源文档裁剪出部分内容，可选关键词遮蔽和信息替换。
                用于为不同角色生成差异化视野上下文（信息隐藏）。

                参数:
                - docId: 源文档 ID（必填）
                - lines: 保留行范围，如 "1-6, 11-14, 20"（必填）
                - mask_words: 遮蔽为 *** 的关键词，逗号分隔（可选）
                - mask_lines: 整行遮蔽为 *** 的原始文档行号范围，如 "8-9"（可选）
                - rename_from: 要替换的原文，逗号分隔（可选，与 rename_to 配合）
                - rename_to: 替换为的文本，逗号分隔（可选，与 rename_from 一一对应）

                注意：mask_lines 的行号是原始文档行号，不是裁剪后的行号。
                rename 使用两遍替换避免重叠（如"司徒王允"→"司徒司徒"不会发生）。
                rename_to 比 rename_from 少时，多余的 from 词保持原样不处理。

                示例:
                doc_crop(docId="turn_5_state", lines="1-6, 11-14",
                         mask_words="王允,七星刀", mask_lines="8-9",
                         rename_from="曹操的密使", rename_to="一位神秘来客")
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "docId", Map.of("type", "string", "description", "源文档 ID"),
                        "lines", Map.of("type", "string",
                                "description", "保留行范围（原始文档行号），逗号分隔。格式: \"1-6, 11-14, 20\""),
                        "mask_words", Map.of("type", "string",
                                "description", "要遮蔽为 *** 的关键词，逗号分隔（可选）"),
                        "mask_lines", Map.of("type", "string",
                                "description", "整行遮蔽的原始文档行号范围（可选）"),
                        "rename_from", Map.of("type", "string",
                                "description", "要替换的原文，逗号分隔（可选）"),
                        "rename_to", Map.of("type", "string",
                                "description", "替换为的文本，逗号分隔（可选，与 rename_from 一一对应）")
                ),
                "required", List.of("docId", "lines")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String docId = call.param("docId", "").trim();
        String linesSpec = call.param("lines", "").trim();
        String maskWordsStr = call.param("mask_words", "").trim();
        String maskLinesStr = call.param("mask_lines", "").trim();
        String renameFromStr = call.param("rename_from", "").trim();
        String renameToStr = call.param("rename_to", "").trim();

        if (docId.isEmpty()) return ToolResult.fail(name(), "docId 不能为空");
        if (linesSpec.isEmpty()) return ToolResult.fail(name(), "lines 不能为空");

        String content;
        String docTitle;

        // @cache: 虚拟文档
        String cached = cacheManager.resolveDocId(docId);
        if (cached != null) {
            content = cached;
            docTitle = docId;
        } else {
            Document doc = store.get(docId);
            if (doc == null) return ToolResult.fail(name(), "文档不存在: " + docId);
            content = doc.content();
            docTitle = doc.title();
        }

        String[] allLines = content.split("\n", -1);

        // Step 1: 解析保留行范围（原始文档行号），同时建立 origLine→croppedIndex 映射
        Set<Integer> keepLines = new LinkedHashSet<>();
        for (String part : linesSpec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                if (part.contains("-")) {
                    String[] range = part.split("-");
                    int start = Integer.parseInt(range[0].trim());
                    int end = Integer.parseInt(range[1].trim());
                    for (int i = start; i <= end && i < allLines.length; i++) {
                        keepLines.add(i);
                    }
                } else {
                    int line = Integer.parseInt(part);
                    if (line < allLines.length) keepLines.add(line);
                }
            } catch (NumberFormatException e) {
                return ToolResult.fail(name(), "无法解析行范围: " + part);
            }
        }

        List<Integer> sorted = new ArrayList<>(keepLines);
        sorted.sort(Integer::compareTo);

        // 建立映射：原始行号 → 裁剪后的行索引
        Map<Integer, Integer> origToCropped = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        int croppedIdx = 0;
        for (int origLine : sorted) {
            sb.append(allLines[origLine]).append("\n");
            origToCropped.put(origLine, croppedIdx);
            croppedIdx++;
        }

        String result = sb.toString();

        // Step 2: rename 替换 — 两遍替换避免重叠
        //   第一遍：from → 占位符 __REN_0__, __REN_1__, ...
        //   第二遍：占位符 → to
        //   避免 "司徒王允"→王允→司徒 与原文"司徒"重叠产生"司徒司徒"
        if (!renameFromStr.isEmpty()) {
            String[] froms = renameFromStr.split(",");
            String[] tos = renameToStr.isEmpty() ? new String[0] : renameToStr.split(",");

            String[] placeholders = new String[froms.length];
            for (int i = 0; i < froms.length; i++) {
                placeholders[i] = "__REN_" + i + "__";
            }

            // Pass 1: from → placeholder
            for (int i = 0; i < froms.length; i++) {
                String from = froms[i].trim();
                if (!from.isEmpty()) {
                    result = result.replace(from, placeholders[i]);
                }
            }

            // Pass 2: placeholder → to（多余的 from 保持原样不替换）
            for (int i = 0; i < froms.length; i++) {
                String from = froms[i].trim();
                if (from.isEmpty()) continue;
                if (i < tos.length) {
                    String to = tos[i].trim();
                    if (!to.isEmpty()) {
                        result = result.replace(placeholders[i], to);
                    } else {
                        result = result.replace(placeholders[i], from); // 回退
                    }
                } else {
                    result = result.replace(placeholders[i], from); // 回退原文
                }
            }

            // Pass 3: 收尾 — 折叠因替换产生的相邻重复词（如 "司徒王允"→王允换司徒→"司徒司徒"→"司徒"）
            for (int i = 0; i < froms.length; i++) {
                if (i < tos.length) {
                    String to = tos[i].trim();
                    if (!to.isEmpty()) {
                        result = result.replaceAll(
                                java.util.regex.Pattern.quote(to) + "("
                                        + java.util.regex.Pattern.quote(to) + ")+",
                                to);
                    }
                }
            }
        }

        // Step 3: mask_words 遮蔽
        if (!maskWordsStr.isEmpty()) {
            for (String word : maskWordsStr.split(",")) {
                word = word.trim();
                if (!word.isEmpty()) {
                    result = result.replace(word, "***");
                }
            }
        }

        // Step 4: mask_lines 整行遮蔽（使用原始文档行号）
        if (!maskLinesStr.isEmpty()) {
            // 先收集要遮蔽的裁剪后行索引
            Set<Integer> cropLinesToMask = new LinkedHashSet<>();
            for (String part : maskLinesStr.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                try {
                    if (part.contains("-")) {
                        String[] range = part.split("-");
                        int start = Integer.parseInt(range[0].trim());
                        int end = Integer.parseInt(range[1].trim());
                        for (int origLine = start; origLine <= end; origLine++) {
                            Integer ci = origToCropped.get(origLine);
                            if (ci != null) cropLinesToMask.add(ci);
                        }
                    } else {
                        int origLine = Integer.parseInt(part);
                        Integer ci = origToCropped.get(origLine);
                        if (ci != null) cropLinesToMask.add(ci);
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            if (!cropLinesToMask.isEmpty()) {
                String[] lines = result.split("\n", -1);
                for (int ci : cropLinesToMask) {
                    if (ci < lines.length) lines[ci] = "***";
                }
                result = String.join("\n", lines);
            }
        }

        // 缓存原始文本
        String rawText = result;
        String cacheId = null;
        try {
            cacheId = cacheManager.put("crop", rawText);
        } catch (java.io.IOException e) {
            // 缓存失败不阻塞，继续返回完整文本
        }

        // 格式化输出：重新编号
        String[] resultLines = rawText.split("\n", -1);
        StringBuilder output = new StringBuilder();
        if (cacheId != null) {
            output.append("[@cache:").append(cacheId).append("]\n");
        }
        for (int i = 0; i < resultLines.length; i++) {
            output.append(String.format("%6d| ", i)).append(resultLines[i]).append("\n");
        }
        if (cacheId != null) {
            output.append("\n---\n使用 @cache:").append(cacheId)
                    .append(" 在后续工具调用中引用此文本，无需复制全文。");
        }

        return ToolResult.ok(name(), List.of(new ToolResult.Item(
                docTitle + " (裁剪视图)", docId,
                output.toString(), 1.0)));
    }
}
