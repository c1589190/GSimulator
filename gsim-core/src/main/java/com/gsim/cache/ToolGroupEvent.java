package com.gsim.cache;

import java.util.Set;

/**
 * 工具组变更事件，存储在 AgentCache 的结构化字段中。
 *
 * <p>与自然语言 tool_result 文本解耦 — 恢复逻辑只读取此结构化字段，
 * 不解析 LLM 生成的文本。
 *
 * @param type      事件类型："activated" | "deactivated"
 * @param groups    变更的组 key 集合
 * @param timestamp epoch millis
 */
public record ToolGroupEvent(String type, Set<String> groups, long timestamp) {

    public static ToolGroupEvent activated(Set<String> groups) {
        return new ToolGroupEvent("activated", Set.copyOf(groups), System.currentTimeMillis());
    }

    public static ToolGroupEvent deactivated(Set<String> groups) {
        return new ToolGroupEvent("deactivated", Set.copyOf(groups), System.currentTimeMillis());
    }
}
