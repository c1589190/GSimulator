package com.gsim.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 LLM 响应中提取 tool call JSON。
 *
 * <p>支持格式：
 * <ul>
 *   <li>纯 JSON: {"tool":"...","args":{...}}</li>
 *   <li>Fenced: ```json\n{"tool":"...","args":{...}}\n```</li>
 *   <li>混合文本 + JSON: "让我先看看：\n{"tool":"...","args":{...}}"</li>
 *   <li>混合文本 + Fenced: "需要查询：\n```json\n{"tool":"...","args":{...}}\n```"</li>
 *   <li>空格 fence: ``` json\n{"tool":"..."}\n```</li>
 *   <li>无语言标记 fence: ```\n{"tool":"..."}\n```</li>
 *   <li>内联 fence（无换行）: ```json{"tool":"..."}```</li>
 *   <li>波浪线 fence: ~~~json\n{"tool":"..."}\n~~~</li>
 * </ul>
 */
public final class ToolCallExtractor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallExtractor() {}

    /**
     * 从文本中提取第一个 tool call。
     * 返回 null 如果没有找到有效 tool call。
     */
    public static OrchestratorAgent.ParsedToolCall extractFirstToolCall(String text) {
        if (text == null || text.isBlank()) return null;

        // 1. Try pure JSON (entire trimmed text)
        OrchestratorAgent.ParsedToolCall result = tryParseJson(text.trim());
        if (result != null) return result;

        // 2. Try extracting fenced code block(s) - find all fences, try each
        for (String fenced : extractAllFencedContents(text)) {
            result = tryParseJson(fenced);
            if (result != null) return result;
        }

        // 3. Scan for balanced-brace JSON objects in the text
        for (String candidate : extractJsonObjects(text)) {
            result = tryParseJson(candidate);
            if (result != null) return result;
        }

        return null;
    }

    /**
     * 从文本中提取所有 tool call（按出现顺序），自动去重 fenced + bare JSON 重复。
     */
    public static List<OrchestratorAgent.ParsedToolCall> extractAllToolCalls(String text) {
        List<OrchestratorAgent.ParsedToolCall> results = new ArrayList<>();
        if (text == null || text.isBlank()) return results;

        // 1. 找到所有 fenced block 的文本范围
        List<FenceRange> fences = findAllFenceRanges(text);

        // 2. 从 fenced block 中提取 tool call
        for (FenceRange fence : fences) {
            OrchestratorAgent.ParsedToolCall parsed = tryParseJson(fence.content);
            if (parsed != null) results.add(parsed);
        }

        // 3. 从非 fenced 区域扫描裸 JSON 对象
        for (String candidate : extractJsonObjects(text)) {
            // 检查 candidate 是否落在 fenced block 内（避免重复计数）
            int candidateStart = text.indexOf(candidate);
            if (candidateStart < 0) continue;
            int candidateEnd = candidateStart + candidate.length();
            boolean insideFence = false;
            for (FenceRange fence : fences) {
                if (fence.contains(candidateStart, candidateEnd)) {
                    insideFence = true;
                    break;
                }
            }
            if (insideFence) continue;

            OrchestratorAgent.ParsedToolCall parsed = tryParseJson(candidate);
            if (parsed != null) results.add(parsed);
        }

        return results;
    }

    /** 文本中一个 fenced block 的范围和内容。 */
    private record FenceRange(int start, int end, String content) {
        boolean contains(int pos, int endPos) {
            return pos >= start && endPos <= end;
        }
    }

    /** 找到文本中所有 fenced block 的范围。 */
    private static List<FenceRange> findAllFenceRanges(String text) {
        List<FenceRange> ranges = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int fenceStart = findNextFenceStart(text, pos);
            if (fenceStart < 0) break;
            char fenceChar = text.charAt(fenceStart);
            String fenceMarker = fenceChar == '~' ? "~~~" : "```";

            int contentStart = text.indexOf('\n', fenceStart);
            int contentBegin;
            if (contentStart < 0 || contentStart > findClosingFence(text, fenceStart + fenceMarker.length(), fenceMarker)) {
                // Inline: content starts right after fence marker
                contentBegin = fenceStart + fenceMarker.length();
                while (contentBegin < text.length()
                        && text.charAt(contentBegin) != '{'
                        && text.charAt(contentBegin) != '\n') {
                    contentBegin++;
                }
                if (contentBegin >= text.length() || text.charAt(contentBegin) != '{') {
                    pos = fenceStart + fenceMarker.length();
                    continue;
                }
            } else {
                contentBegin = contentStart + 1;
            }

            int end = findClosingFence(text, contentBegin, fenceMarker);
            if (end < 0) { pos = fenceStart + fenceMarker.length(); continue; }

            String inside = text.substring(contentBegin, end).trim();
            pos = end + fenceMarker.length();
            if (!inside.isEmpty() && inside.startsWith("{")) {
                ranges.add(new FenceRange(fenceStart, end + fenceMarker.length(), inside));
            }
        }
        return ranges;
    }

    /** 尝试将纯 JSON 字符串解析为 tool call。 */
    static OrchestratorAgent.ParsedToolCall tryParseJson(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return null;
        String trimmed = jsonText.trim();
        if (!trimmed.startsWith("{")) return null;

        try {
            JsonNode root = MAPPER.readTree(trimmed);
            if (!root.has("tool")) return null;
            String tool = root.get("tool").asText();
            if (tool == null || tool.isBlank()) return null;

            JsonNode argsNode = root.get("args");
            Map<String, String> args = new java.util.HashMap<>();
            if (argsNode != null && argsNode.isObject()) {
                var iter = argsNode.fields();
                while (iter.hasNext()) {
                    var entry = iter.next();
                    args.put(entry.getKey(), entry.getValue().asText());
                }
            }
            return new OrchestratorAgent.ParsedToolCall(tool, args);
        } catch (Exception e) {
            log.debug("Not a valid tool call JSON: {}", trimmed.substring(0, Math.min(80, trimmed.length())));
            return null;
        }
    }

    /**
     * 提取所有 fenced code block 的内容（支持多种 fence 变体）。
     * 处理 ```json, ``` json, ``` (无语言标记), ~~~json, ~~~ 等格式。
     */
    static List<String> extractAllFencedContents(String text) {
        List<String> results = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int fenceStart = findNextFenceStart(text, pos);
            if (fenceStart < 0) break;
            char fenceChar = text.charAt(fenceStart);
            String fenceMarker = fenceChar == '~' ? "~~~" : "```";
            // Find the content after the fence line
            int contentStart = text.indexOf('\n', fenceStart);
            int contentBegin;
            if (contentStart < 0 || contentStart > findClosingFence(text, fenceStart + fenceMarker.length(), fenceMarker)) {
                // Inline: ```json{"tool":"x"}``` — content starts right after fence marker
                contentBegin = fenceStart + fenceMarker.length();
                // Also skip language tag like "json" or " json" if present (no newline)
                while (contentBegin < text.length() && text.charAt(contentBegin) != '{' && text.charAt(contentBegin) != '\n') {
                    contentBegin++;
                }
                if (contentBegin >= text.length() || text.charAt(contentBegin) != '{') {
                    pos = fenceStart + fenceMarker.length();
                    continue;
                }
            } else {
                // Normal: content starts after the newline
                contentBegin = contentStart + 1;
            }

            int end = findClosingFence(text, contentBegin, fenceMarker);
            if (end < 0) { pos = fenceStart + fenceMarker.length(); continue; }

            String inside = text.substring(contentBegin, end).trim();
            pos = end + fenceMarker.length();
            if (!inside.isEmpty() && inside.startsWith("{")) {
                results.add(inside);
            }
        }
        return results;
    }

    /** 从指定位置查找下一个 fence 起始位置。支持 ``` 和 ~~~。 */
    private static int findNextFenceStart(String text, int from) {
        int backtick = text.indexOf("```", from);
        int tilde = text.indexOf("~~~", from);
        if (backtick < 0 && tilde < 0) return -1;
        if (backtick < 0) return tilde;
        if (tilde < 0) return backtick;
        return Math.min(backtick, tilde);
    }

    /** 查找对应结束 fence 的位置。 */
    private static int findClosingFence(String text, int from, String fenceMarker) {
        return text.indexOf(fenceMarker, from);
    }

    /** 扫描文本中所有平衡大括号 JSON 对象。 */
    private static List<String> extractJsonObjects(String text) {
        List<String> results = new ArrayList<>();
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == '{') {
                int end = findMatchingBrace(text, i);
                if (end > i) {
                    results.add(text.substring(i, end + 1));
                    i = end;
                }
            }
        }
        return results;
    }

    /** 找到匹配的 } 位置。处理字符串、转义和嵌套。 */
    private static int findMatchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
