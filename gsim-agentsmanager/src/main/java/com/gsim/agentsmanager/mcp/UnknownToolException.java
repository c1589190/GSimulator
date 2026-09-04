package com.gsim.agentsmanager.mcp;

/**
 * 表示 MCP 工具注册表中不存在指定工具名称的异常。
 *
 * <p>与 {@link IllegalArgumentException} 区分用途：
 * <ul>
 *   <li><b>UnknownToolException</b> — 路由失败：工具名称在所有注册表中都找不到</li>
 *   <li><b>IllegalArgumentException</b> — 参数错误：工具存在但参数无效</li>
 * </ul>
 *
 * <p>{@link CompositeMcpToolRegistry} 和
 * {@link AbstractMcpServer#executeTool(String, JsonNode)} 只捕获此异常
 * 来尝试下一个注册表，不会将参数验证失败误判为路由失败。
 */
public class UnknownToolException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String toolName;

    /**
     * @param toolName 未找到的工具名称
     */
    public UnknownToolException(String toolName) {
        super("Unknown tool: " + toolName);
        this.toolName = toolName;
    }

    /**
     * @return 未找到的工具名称
     */
    public String getToolName() {
        return toolName;
    }
}
