package com.gsim.agent;

/**
 * Per-agent query scope context — ThreadLocal 承载当前 Agent 的查询范围。
 *
 * <p>AbstractAgent 在工具调用前用当前 AgentConfig 的 {@link QueryScope}
 * 设置本上下文，调用后清除。查询工具在执行时读取 {@link #get()} 判断越权。
 * 非 Agent 路径（MCP 直接调用）下上下文为 null，查询不受限。
 */
public final class QueryScopeContext {

    private static final ThreadLocal<QueryScope> CURRENT = new ThreadLocal<>();

    private QueryScopeContext() {}

    public static void set(QueryScope scope) {
        CURRENT.set(scope);
    }

    public static QueryScope get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
