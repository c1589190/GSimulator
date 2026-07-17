package com.gsim.agent;

import java.util.List;

/**
 * 工具过滤规则 — 控制 Agent 可用工具集。
 *
 * <p>mode:
 * <ul>
 *   <li>"all" — 全部工具可用（主 Agent）</li>
 *   <li>"read_only" — 仅 READ_ONLY + CONTROL（SubAgent 默认）</li>
 *   <li>"none" — 仅 finish_action（纯思考/对话 Agent，不访问任何数据）</li>
 *   <li>"custom" — 按 allow/deny 列表过滤</li>
 * </ul>
 *
 * <p>运行时过滤逻辑 ({@code allows()}) 在 gsim-app 的 {@code ToolFilterEvaluator} 中。
 * 本 record 仅承载配置数据。
 */
public record ToolFilterConfig(
        String mode,
        List<String> allow,
        List<String> deny
) {
    public static final ToolFilterConfig ALL = new ToolFilterConfig("all", List.of(), List.of());
    public static final ToolFilterConfig READ_ONLY = new ToolFilterConfig("read_only", List.of(), List.of());
    public static final ToolFilterConfig NONE = new ToolFilterConfig("none", List.of(), List.of());
}
