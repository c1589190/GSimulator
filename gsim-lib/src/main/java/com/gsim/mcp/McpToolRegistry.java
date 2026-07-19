package com.gsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * MCP 工具注册表契约。
 *
 * <p>实现类负责注册工具并按名称将 JSON 参数路由到具体的处理程序。
 * 与 {@link AbstractMcpServer} 配合使用以提供完整的 MCP 端点。
 *
 * <p>外部项目可通过实现此接口来添加自定义 MCP 工具，
 * 然后将注册表实例传入 {@link AbstractMcpServer} 或
 * {@link CompositeMcpToolRegistry} 与 GSim 工具合并。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * public class MyToolRegistry implements McpToolRegistry {
 *     private final List<ToolDef> tools = List.of(
 *         new ToolDef("my_hello", "Say hello", "{...}")
 *     );
 *
 *     @Override public List<ToolDef> all() { return tools; }
 *
 *     @Override public String execute(String name, JsonNode args) throws Exception {
 *         return switch (name) {
 *             case "my_hello" -> "{\"greeting\":\"Hello\"}";
 *             default -> throw new IllegalArgumentException("Unknown: " + name);
 *         };
 *     }
 * }
 * }</pre>
 */
public interface McpToolRegistry {

    /**
     * 返回此注册表中所有工具定义的快照。
     *
     * @return 工具定义列表（不可变），按注册顺序排列
     */
    List<ToolDef> all();

    /**
     * 执行指定名称的工具。
     *
     * @param name 工具名称（如 {@code gsim_list_worlds}）
     * @param args 工具参数的 JSON 树
     * @return JSON 编码的结果字符串
     * @throws IllegalArgumentException 如果工具名称未知
     * @throws Exception                如果工具执行失败
     */
    String execute(String name, JsonNode args) throws Exception;
}
