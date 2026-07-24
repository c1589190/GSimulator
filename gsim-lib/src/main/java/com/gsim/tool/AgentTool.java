package com.gsim.tool;

/**
 * Agent 可调用工具的抽象接口。
 * 所有工具必须实现此接口并通过 ToolRegistry 注册。
 */
public interface AgentTool {

    /**
     * 工具名称（用于注册和调用）。
     *
     * @return 工具的唯一名称
     */
    String name();

    /**
     * 工具描述（供 LLM 选择工具时参考）。
     *
     * @return 工具的文本描述
     */
    String description();

    /**
     * 执行工具并返回结果。
     *
     * @param call 工具调用请求，包含参数
     * @return 工具执行结果
     */
    ToolResult execute(ToolCall call);

    /**
     * 工具的 JSON Schema 参数定义。
     *
     * <p>返回 null 表示无严格 schema（序列化时使用宽 schema）。
     *
     * @return 包含 type、properties、required 等字段的 JSON Schema Map，或 null
     */
    default java.util.Map<String, Object> getParameters() {
        return null;
    }

    /**
     * 工具权限等级 — 与路由规则和 MCP/Agent 权限门禁配合使用。
     *
     * <p>{@link Permission#SELF} Agent 自身流程控制（finish_action 等），任意时刻允许。
     * {@link Permission#READ} 只读查询，不修改任何数据。
     * {@link Permission#WRITE} 创建/修改数据。
     * {@link Permission#SYSTEM} 破坏性操作（delete_* 等），需用户确认。
     *
     * <p>排序: SELF < READ < WRITE < SYSTEM（用于 maxPermission 比较）。
     * 默认返回 READ（保守策略：未知工具不得写入）。
     */
    enum Permission {
        SELF,
        READ,
        WRITE,
        SYSTEM
    }

    /**
     * 此工具的权限等级。
     *
     * @return 工具的 Permission 等级，默认 READ
     */
    default Permission permission() {
        return Permission.READ;
    }

    /**
     * 此工具是否需要 {@code worldId} 参数。
     *
     * <p>返回 {@code true} 时，MCP 适配器会：
     * <ol>
     *   <li>在 JSON Schema 中注入 {@code worldId} 作为必填参数</li>
     *   <li>在 execute 前校验 worldId 存在性（空白则拒绝）</li>
     *   <li>对 worldinfo 类工具额外校验 worldId 与活跃 world 一致</li>
     * </ol>
     *
     * <p>默认返回 {@code false} — 不需要 worldId 的工具（Doc、Import、
     * Agent 配置、搜索等）无需任何改动。
     *
     * @return true 需要 worldId，false 不需要
     */
    default boolean requiresWorldId() {
        return false;
    }

    /**
     * 此工具是否通过 MCP 公共接口暴露。
     *
     * <p>返回 {@code false} 时，工具只在 Agent 内部 ToolLoop 中可用：
     * <ul>
     *   <li>不会出现在 MCP {@code tools/list} 中</li>
     *   <li>不能通过 MCP {@code tools/call} 调用（返回 UnknownTool）</li>
     * </ul>
     *
     * <p>此属性仅表示工具是否属于 MCP 公共接口，与运行时权限
     *（READ/WRITE/SYSTEM）无关。默认返回 {@code true}，存量工具无需逐个声明。
     *
     * @return true 允许 MCP 暴露，false 仅内部可用
     */
    default boolean mcpExposed() {
        return true;
    }
}
