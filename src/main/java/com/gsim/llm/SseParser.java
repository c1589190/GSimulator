package com.gsim.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * SSE (Server-Sent Events) 解析器 — 解析 OpenAI-compatible 流式响应。
 *
 * <p>从 {@link BufferedReader} 逐行读取 SSE 数据，提取 content/reasoning/tool_call delta，
 * 写入 {@link StreamPool}。完成后调用 {@link StreamPool#onComplete(LlmResult)} 或
 * {@link StreamPool#onError(String)}。
 *
 * <p>支持 JSON fallback：如果首行不是 "data:" 开头，回退到整段 JSON 解析。
 */
class SseParser {

    private static final Logger log = LoggerFactory.getLogger(SseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StreamPool pool;
    private final String model;

    SseParser(StreamPool pool, String model) {
        this.pool = pool;
        this.model = model;
    }

    /**
     * 从 reader 解析 SSE 流，阻塞直到流结束或出错。
     * 完成后 pool 状态为 complete/failed。
     */
    void parse(BufferedReader reader) throws IOException {
        String firstLine = readFirstNonEmptyLine(reader);
        if (firstLine == null) {
            pool.onError("Empty response from LLM provider");
            return;
        }

        if (firstLine.startsWith("data:")) {
            parseSse(reader, firstLine);
        } else {
            parseJsonFallback(reader, firstLine);
        }
    }

    // ---- SSE 路径 ----

    private void parseSse(BufferedReader reader, String firstLine) throws IOException {
        StringBuilder dataBuffer = new StringBuilder();
        String line = firstLine;

        while (line != null) {
            if (line.startsWith("data:")) {
                String data = line.substring(5).strip();
                if (data.isEmpty()) {
                    // 空 data: 行 → 忽略
                } else if ("[DONE]".equals(data)) {
                    finishStream();
                    return;
                } else {
                    dataBuffer.append(data);
                    // 立即处理当前 data（支持单行完整 JSON）
                    processDataLine(dataBuffer.toString());
                    dataBuffer.setLength(0);
                }
            } else if (line.isEmpty()) {
                // 空行（SSE 事件分隔符）→ 忽略
            }
            // 非 data: 行 → 可能是注释或空行，忽略

            line = reader.readLine();
        }

        // 流结束但没收到 [DONE]
        finishStream();
    }

    private String readFirstNonEmptyLine(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isEmpty()) return line;
        }
        return null;
    }

    private void processDataLine(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) return;

            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            if (delta == null) return;

            // content
            JsonNode contentNode = delta.get("content");
            if (contentNode != null && !contentNode.isNull()) {
                String text = contentNode.asText();
                if (!text.isEmpty()) {
                    pool.onContentDelta(text);
                }
            }

            // reasoning_content（可能在不同字段名）
            JsonNode reasoningNode = delta.get("reasoning_content");
            if (reasoningNode == null) reasoningNode = delta.get("reasoning");
            if (reasoningNode == null) reasoningNode = delta.get("think");
            if (reasoningNode != null && !reasoningNode.isNull()) {
                String text = reasoningNode.asText();
                if (!text.isEmpty()) {
                    pool.onReasoningDelta(text);
                }
            }

            // tool_calls
            JsonNode toolCallsNode = delta.get("tool_calls");
            if (toolCallsNode != null && toolCallsNode.isArray()) {
                for (JsonNode tcNode : toolCallsNode) {
                    int index = tcNode.get("index").asInt(0);
                    String name = null;
                    String argsChunk = null;

                    JsonNode fnNode = tcNode.get("function");
                    if (fnNode != null) {
                        JsonNode nameNode = fnNode.get("name");
                        if (nameNode != null && !nameNode.isNull()) {
                            name = nameNode.asText();
                        }
                        JsonNode argsNode = fnNode.get("arguments");
                        if (argsNode != null && !argsNode.isNull()) {
                            argsChunk = argsNode.asText();
                        }
                    }
                    pool.onToolCallDelta(index, name, argsChunk);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse SSE data line: {}", e.getMessage());
        }
    }

    private void finishStream() {
        pool.onComplete(new LlmResult(
                pool.getContent(), pool.getReasoning(),
                model, 0, true, null,
                pool.getToolCalls(), "stop", false));
    }

    // ---- JSON fallback 路径（非流式响应） ----

    private void parseJsonFallback(BufferedReader reader, String firstLine) throws IOException {
        StringBuilder body = new StringBuilder(firstLine);
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }

        try {
            JsonNode root = MAPPER.readTree(body.toString());

            // 检查错误
            JsonNode errorNode = root.get("error");
            if (errorNode != null) {
                String errMsg = errorNode.has("message")
                        ? errorNode.get("message").asText()
                        : errorNode.toString();
                pool.onError(errMsg);
                return;
            }

            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                pool.onError("No choices in response");
                return;
            }

            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            String content = "";
            String reasoning = "";
            List<LlmToolCall> toolCalls = new java.util.ArrayList<>();

            if (message != null) {
                JsonNode contentNode = message.get("content");
                if (contentNode != null && !contentNode.isNull()) {
                    content = contentNode.asText();
                    pool.onContentDelta(content); // 作为单个 delta 发出
                }

                // reasoning
                JsonNode rn = message.get("reasoning_content");
                if (rn == null) rn = message.get("reasoning");
                if (rn != null && !rn.isNull()) {
                    reasoning = rn.asText();
                    pool.onReasoningDelta(reasoning);
                }

                // tool_calls
                JsonNode tcNode = message.get("tool_calls");
                if (tcNode != null && tcNode.isArray()) {
                    for (int i = 0; i < tcNode.size(); i++) {
                        JsonNode tc = tcNode.get(i);
                        String id = tc.has("id") ? tc.get("id").asText() : "call_fb_" + i;
                        JsonNode fn = tc.get("function");
                        if (fn != null) {
                            String name = fn.get("name").asText();
                            String argsJson = fn.has("arguments")
                                    ? fn.get("arguments").asText() : "{}";
                            Map<String, String> args = parseArgs(argsJson);
                            toolCalls.add(new LlmToolCall(id, name, args));
                        }
                    }
                }
            }

            // usage
            int tokensUsed = 0;
            JsonNode usage = root.get("usage");
            if (usage != null && usage.has("total_tokens")) {
                tokensUsed = usage.get("total_tokens").asInt();
            }

            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                    ? choice.get("finish_reason").asText() : "stop";

            LlmResult result;
            if (!toolCalls.isEmpty()) {
                result = LlmResult.withToolCalls(toolCalls, model, tokensUsed);
            } else {
                result = new LlmResult(content, reasoning, model, tokensUsed, true, null,
                        List.of(), finishReason, false);
            }
            pool.onComplete(result);

        } catch (Exception e) {
            log.error("Failed to parse JSON fallback response: {}", e.getMessage());
            pool.onError("Failed to parse LLM response: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = MAPPER.readValue(json, Map.class);
            Map<String, String> result = new java.util.LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                Object v = entry.getValue();
                result.put(entry.getKey(), v != null ? v.toString() : "");
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
