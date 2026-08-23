package com.gsim.agent.tools.search;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentlib.mcp.ToolDef;
import com.gsim.agentlib.mcp.ToolRegistryMcpAdapter;
import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.agentlib.util.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 搜索工具 MCP 名称回环回归测试（F3 实机缺陷 D1）。
 *
 * <p>缺陷：工具注册名（registry key）若以 {@code gsim_} 为前缀（如 {@code gsim_search}），
 * MCP wire 名经 {@link ToolRegistryMcpAdapter#execute} 时 {@code toRegistryName} 会无条件剥离
 * {@code gsim_} 前缀再查注册表与门禁（{@code ToolExecutionGuard.checkMcp} 使用剥离后的 key），
 * 导致 UNKNOWN_TOOL——聚合器经 MCP 完全不可达。修复 = 注册名使用短形式（search /
 * search_world / search_doc），wire 名由适配器加回 {@code gsim_} 前缀（gsim_search /
 * gsim_search_world / gsim_search_doc）。
 *
 * <p>本测试走 MCP 服务器同一条路径（ToolRegistryMcpAdapter）：断言每个新工具的 wire 名
 * 在 {@code tools/list} 可见，且经 {@code execute} 能解析到真实工具并穿过门禁。
 */
@DisplayName("SearchToolMcpNamingTest — 搜索工具 MCP 名称回环")
class SearchToolMcpNamingTest {

    private static final SearchToolContext CTX = new SearchToolContext(() -> null, null, null, null);

    private static List<AgentTool> searchTools() {
        return List.of(
                new GsimSearchTool(CTX),
                new GsimSearchWorldTool(CTX),
                new GsimapSearchRegionTool(CTX),
                new GsimapSearchHexTool(CTX),
                new GsimSearchDocTool(CTX));
    }

    @Test
    @DisplayName("5 个搜索工具的 wire 名可回环解析到注册表工具并通过 MCP 门禁")
    void searchToolNamesRoundTripThroughMcpAdapter() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        List<AgentTool> tools = searchTools();
        for (AgentTool tool : tools) {
            registry.register(tool);
        }
        ToolRegistryMcpAdapter adapter = new ToolRegistryMcpAdapter(registry);

        for (AgentTool tool : tools) {
            // wire 名 = mcpName：gsimap_ 前缀保留，其余加 gsim_ 前缀（与适配器规则一致）
            String expectedWire = tool.name().startsWith("gsimap_") ? tool.name() : "gsim_" + tool.name();
            ToolDef def = adapter.all().stream()
                    .filter(d -> d.name().equals(expectedWire))
                    .findFirst()
                    .orElseThrow(() ->
                            new AssertionError("tools/list 缺少 " + expectedWire + "（registry key=" + tool.name() + "）"));

            // 走 MCP 执行路径：execute 内部 toRegistryName（剥离 gsim_）+ guard 都用剥离后的
            // registry key。若名字回环失败 → guard 报 "Tool not found: <key>"；成功解析则
            // 先到 worldId 校验（工具 requiresWorldId=true）→ "worldId is required for ..."。
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> adapter.execute(def.name(), JsonUtils.MAPPER.readTree("{}")),
                    "wire=" + def.name() + " 应解析到真实工具（而非 UNKNOWN_TOOL）");
            assertTrue(
                    ex.getMessage().contains("worldId is required"),
                    "wire=" + def.name() + " 未通过解析/门禁: " + ex.getMessage());
        }
    }
}
