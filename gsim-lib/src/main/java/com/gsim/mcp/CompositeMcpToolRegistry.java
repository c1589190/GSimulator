package com.gsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将多个 {@link McpToolRegistry} 合并为一个统一视图的装饰器。
 *
 * <p>工具在 {@link #all()} 中按索引去重——如果多个注册表提供了同名工具，
 * 仅保留最后注册的工具定义，之前被覆盖的名称会收到 WARN 日志警告。
 * {@link #execute(String, JsonNode)} 使用 O(1) 名称到注册表的索引查找，
 * 仅捕获 {@link UnknownToolException} 来尝试下一个注册表，
 * <b>不会</b>将参数验证失败（{@link IllegalArgumentException}）误判为路由失败。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * var composite = new CompositeMcpToolRegistry(
 *     new MyCustomRegistry(),
 *     new GsimMcpToolRegistry(Path.of("worlds")).asMcpRegistry()
 * );
 * }</pre>
 */
public class CompositeMcpToolRegistry implements McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompositeMcpToolRegistry.class);

    private final List<McpToolRegistry> registries;
    private final Map<String, McpToolRegistry> toolIndex = new LinkedHashMap<>();

    /**
     * 创建一个合并多个注册表的组合视图。
     * 工具按参数顺序合并；如果多个注册表提供了同名工具，最后注册的胜出，
     * 并记录 WARN 日志。
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
        // 使用索引去重：保证 tools/list 和 tools/call 的路由一致
        Set<String> seen = new LinkedHashSet<>();
        List<ToolDef> result = new ArrayList<>();
        for (McpToolRegistry reg : registries) {
            for (ToolDef tool : reg.all()) {
                if (seen.add(tool.name())) {
                    result.add(tool);
                }
            }
        }
        return List.copyOf(result);
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
            } catch (UnknownToolException e) {
                // 仅 UnknownToolException 表示路由失败——该注册表不认识这个工具
                // IllegalArgumentException 表示参数错误，会向上传播
            }
        }
        throw new UnknownToolException(name);
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

    /** 重建名称到注册表的索引，检测并警告重复名称。 */
    private void rebuildIndex() {
        toolIndex.clear();
        for (McpToolRegistry reg : registries) {
            for (ToolDef tool : reg.all()) {
                McpToolRegistry previous = toolIndex.put(tool.name(), reg);
                if (previous != null) {
                    log.warn(
                            "[MCP-REGISTRY] Duplicate tool name '{}' detected — registry '{}' overrides '{}'",
                            tool.name(),
                            reg.getClass().getSimpleName(),
                            previous.getClass().getSimpleName());
                }
            }
        }
    }
}
