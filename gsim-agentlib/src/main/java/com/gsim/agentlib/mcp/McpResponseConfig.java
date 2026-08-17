package com.gsim.agentlib.mcp;

/**
 * 可配置的 MCP 响应限值。
 *
 * <p>控制 {@link ToolRegistryMcpAdapter} 对工具结果的序列化保护：
 * <ul>
 *   <li>{@code defaultPageSize} — {@code _pageSize} 参数缺省时的每页条目数（默认 20）</li>
 *   <li>{@code maxPageSize} — {@code _pageSize} 参数可被钳制的上限（默认 100）</li>
 *   <li>{@code maxJsonBytes} — 序列化响应 JSON 的硬上限，超过后触发溢出处理（默认 50000）</li>
 *   <li>{@code snippetMaxChars} — 截断路径中单条 snippet 的最大字符数（默认 300）</li>
 *   <li>{@code stagingEnabled} — 是否允许 {@link ToolResultOverflowHandler} 改写溢出结果（默认 true）</li>
 * </ul>
 *
 * <p>默认值与历史硬编码常量完全一致 — 不传入配置时行为零变化。
 *
 * @param defaultPageSize 缺省每页条目数
 * @param maxPageSize     每页条目数上限
 * @param maxJsonBytes    序列化响应 JSON 上限（字符数）
 * @param snippetMaxChars 截断路径的单条 snippet 上限
 * @param stagingEnabled  是否启用溢出暂存 handler
 */
public record McpResponseConfig(
        int defaultPageSize, int maxPageSize, int maxJsonBytes, int snippetMaxChars, boolean stagingEnabled) {

    /** 历史默认分页大小（与既有常量一致）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 历史默认分页上限（与既有常量一致）。 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 历史默认序列化 JSON 上限（与既有常量一致）。 */
    private static final int MAX_JSON_BYTES = 50_000;
    /** 历史默认截断长度（与既有常量一致）。 */
    private static final int SNIPPET_MAX_CHARS = 300;

    /**
     * 使用全部默认值构造。
     */
    public McpResponseConfig() {
        this(DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, MAX_JSON_BYTES, SNIPPET_MAX_CHARS, true);
    }

    /**
     * 返回默认配置（与旧构造路径行为一致）。
     *
     * @return 默认配置实例
     */
    public static McpResponseConfig defaults() {
        return new McpResponseConfig();
    }
}
