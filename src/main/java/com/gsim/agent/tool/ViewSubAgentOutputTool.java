package com.gsim.agent.tool;

import com.gsim.cache.CacheSession;
import com.gsim.cache.CachesManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 滑动窗口读取 SubAgent 的对话输出 — 解决子Agent结果被截断的问题。
 *
 * <p>按 assistant 消息（含 tool_calls）分段，支持 offset/limit 分页读取。
 * 主 Agent 可多次调用，逐段读取子Agent的完整推理过程和工具调用。
 */
public final class ViewSubAgentOutputTool implements AgentTool {

    private final CachesManager cachesManager;
    private final Supplier<String> worldId;

    public ViewSubAgentOutputTool(CachesManager cachesManager, Supplier<String> worldId) {
        this.cachesManager = cachesManager;
        this.worldId = worldId;
    }

    @Override
    public String name() { return "view_sub_agent_output"; }

    @Override
    public String description() {
        return "分页读取 SubAgent 的完整对话输出（assistant 消息和工具调用），解决输出被截断的问题。"
                + "参数: cacheId (SubAgent cache 文件名, 必填), "
                + "offset (起始消息序号, 默认0), "
                + "limit (返回消息数, 默认50, 最大200)。"
                + "每次调用返回一段消息，通过增大 offset 来滑动窗口。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "cacheId", Map.of("type", "string",
                                "description", "SubAgent cache 文件名（如 sim-1_2026-...json）"),
                        "offset", Map.of("type", "integer",
                                "description", "起始消息序号（0-based），默认 0"),
                        "limit", Map.of("type", "integer",
                                "description", "返回消息数，默认 50，最大 200")
                ),
                "required", List.of("cacheId")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolCall call) {
        String cacheId = call.param("cacheId", "").trim();
        if (cacheId.isEmpty()) return ToolResult.fail(name(), "cacheId 不能为空");

        String wid = worldId.get();
        CacheSession session = cachesManager.loadCache(wid, cacheId);
        if (session == null) {
            return ToolResult.fail(name(), "Cache 未找到: " + cacheId + " (world=" + wid + ")");
        }

        int offset = Math.max(0, parseInt(call.param("offset"), 0));
        int limit = Math.min(Math.max(1, parseInt(call.param("limit"), 50)), 200);

        List<Map<String, Object>> messages = session.messages();

        // 筛选 assistant + tool 消息（排除 system），构建消息列表
        List<OutputSegment> segments = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.getOrDefault("role", "");
            if ("system".equals(role)) continue;

            String content = (String) msg.get("content");
            List<String> toolNames = new ArrayList<>();
            Object tcObj = msg.get("tool_calls");
            if (tcObj instanceof List<?> tcList) {
                for (Object tc : tcList) {
                    if (tc instanceof Map<?, ?> tcMap) {
                        Map<String, Object> fn = (Map<String, Object>) tcMap.get("function");
                        if (fn != null) toolNames.add((String) fn.get("name"));
                    }
                }
            }

            if (content != null || !toolNames.isEmpty()) {
                segments.add(new OutputSegment(i, role, content, toolNames));
            }
        }

        int total = segments.size();
        int start = Math.min(offset, total);
        int end = Math.min(start + limit, total);

        if (start >= total) {
            return ToolResult.ok(name(), List.of(new ToolResult.Item(
                    "output end", cacheId,
                    "已到末尾。总消息段: " + total + "，请求 offset=" + offset, 0)));
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            OutputSegment seg = segments.get(i);
            sb.append("[").append(seg.index).append("] ").append(seg.role);
            if (!seg.toolNames.isEmpty()) {
                sb.append(" → ").append(seg.toolNames);
            }
            sb.append("\n");
            if (seg.content != null) {
                sb.append(seg.content);
                if (!seg.content.endsWith("\n")) sb.append("\n");
            }
            sb.append("\n");
        }

        String title = cacheId + " messages " + start + "-" + (end - 1) + " / " + total;
        String snippet = sb.toString();
        boolean hasMore = end < total;
        String footer = "\n[总消息段: " + total + "]"
                + (hasMore ? " [还有更多: offset=" + end + "]" : " [已到末尾]");

        return ToolResult.ok(name(), List.of(new ToolResult.Item(
                title, cacheId, snippet + footer, hasMore ? 0.5 : 1.0)));
    }

    private record OutputSegment(int index, String role, String content, List<String> toolNames) {}

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
