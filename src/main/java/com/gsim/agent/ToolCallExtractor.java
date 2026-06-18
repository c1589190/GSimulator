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
 *   <li>Fenced: ```json\\n{"tool":"...","args":{...}}\\n```</li>
 *   <li>混合文本 + JSON: "让我先看看：\\n{"tool":"...","args":{...}}"</li>
 *   <li>混合文本 + Fenced: "需要查询：\\n```json\\n{"tool":"...","args":{...}}\\n```"</li>
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

        // 2. Try extracting fenced code block
        String fenced = extractFencedJson(text);
        if (fenced != null) {
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

    /** 提取 fenced code block 中的 JSON。 */
    private static String extractFencedJson(String text) {
        int start = text.indexOf("```json");
        if (start < 0) start = text.indexOf("```");
        if (start < 0) return null;

        int contentStart = text.indexOf('\n', start);
        if (contentStart < 0) return null;

        int end = text.indexOf("```", contentStart + 1);
        if (end < 0) return null;

        String inside = text.substring(contentStart + 1, end).trim();
        return inside.startsWith("{") ? inside : null;
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

    /** 找到匹配的 } 位置。 */
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
