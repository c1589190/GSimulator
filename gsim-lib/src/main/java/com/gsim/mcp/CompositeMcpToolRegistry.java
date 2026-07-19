package com.gsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将多个 {@link McpToolRegistry} 合并为一个统一视图的装饰器。
 *
 * <p>工具按注册表顺序在 {@link #all()} 中列出。
 * {@link #execute(String, JsonNode)} 使用 O(1) 名称到注册表的索引查找；
 * 如果多个注册表提供了同名工具，最后注册的注册表胜出。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * var composite = new CompositeMcpToolRegistry(
 *     new MyCustomRegistry(),
 *     new GsimMcpToolRegistry(Path.of("worlds"))
 * );
 * }</pre>
 */
public class CompositeMcpToolRegistry implements McpToolRegistry {

    private final List<McpToolRegistry> registries;
    private final Map<String, McpToolRegistry> toolIndex = new LinkedHashMap<>();

    /**
     * 创建一个合并多个注册表的组合视图。
     * 工具按参数顺序合并；对于 {@link #execute} 使用索引表查找，常量时间。
     *
     * @param registries 要合并的注册表（按优先级顺序）
     */
    public CompositeMcpToolRegistry(McpToolRegistry... registries) {
        this(List.of(registries));
    }

    /**
     * 创建一个合并多个注册表的组合视图。
     *
     * @param registries 要合并的注册表列表（按优先级顺序）
     */
    public CompositeMcpToolRegistry(List<McpToolRegistry> registries) {
        this.registries = List.copyOf(registries);
        rebuildIndex();
    }

    @Override
    public List<ToolDef> all() {
        return registries.stream().flatMap(r -> r.all().stream()).toList();
    }

    @Override
    public String execute(String name, JsonNode args) throws Exception {
        // 先用索引做 O(1) 查找
        McpToolRegistry reg = toolIndex.get(name);
        if (reg != null) {
            return reg.execute(name, args);
        }
        // 降级：线性扫描（处理索引建立后动态注册的工具）
        for (McpToolRegistry r : registries) {
            try {
                return r.execute(name, args);
            } catch (IllegalArgumentException e) {
                // 该注册表不认识这个工具，继续下一个
            }
        }
        throw new IllegalArgumentException("Unknown tool: " + name);
    }

    /**
     * 返回此组合中的所有注册表。
     * 调用者可用此方法进行注册表级别的内省或直接路由。
     *
     * @return 注册表列表（不可变）
     */
    public List<McpToolRegistry> getRegistries() {
        return registries;
    }

    /** 重建名称到注册表的索引。 */
    private void rebuildIndex() {
        toolIndex.clear();
        for (McpToolRegistry reg : registries) {
            for (ToolDef tool : reg.all()) {
                toolIndex.put(tool.name(), reg);
            }
        }
    }
}
