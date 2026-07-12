package com.gsim.agent.tool;

import com.gsim.cache.CacheInfo;
import com.gsim.cache.CachesManager;
import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * list_sub_agent_caches 工具 — 列出所有 SubAgent（sim/search）的缓存历史。
 *
 * <p>参数:
 * <ul>
 *   <li>type (可选): 过滤类型，"sim" 或 "search"，不提供则列出全部</li>
 * </ul>
 *
 * <p>返回按创建时间倒序排列的 cache 列表，主 Agent 可据此选择 cache 进行续接。
 */
public class ListSubAgentCachesTool implements AgentTool {

    public static final String NAME = "list_sub_agent_caches";

    private final CachesManager cachesManager;
    private final Supplier<String> worldIdSupplier;

    public ListSubAgentCachesTool(CachesManager cachesManager, Supplier<String> worldIdSupplier) {
        this.cachesManager = cachesManager;
        this.worldIdSupplier = worldIdSupplier;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                列出所有 SubAgent（sim/search）的对话缓存历史。
                参数:
                - type (可选): 过滤类型，"sim" 或 "search"，不提供则列出全部。
                返回 cache 列表，包含 cacheId（sessionId），可在 dispatch_sub_agent 的 cacheId 参数中使用以续接上下文。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "type", Map.of(
                                "type", "string",
                                "description", "可选 — 过滤类型：sim 或 search。不提供则列出全部 SubAgent",
                                "enum", List.of("sim", "search")
                        )
                ),
                List.of()  // type 非必填
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = worldIdSupplier.get();
        String type = call.param("type", "").trim().toLowerCase();
        if (type.isEmpty()) type = null;

        List<CacheInfo> caches;
        if (type != null) {
            caches = cachesManager.listCaches(worldId, type);
        } else {
            // 列出 sim + search 的并集
            caches = cachesManager.listCaches(worldId);
            caches = caches.stream()
                    .filter(ci -> "sim".equals(ci.agentType()) || "search".equals(ci.agentType()))
                    .toList();
        }

        if (caches.isEmpty()) {
            return ToolResult.ok(NAME, List.of(new ToolResult.Item(
                    "no_caches",
                    NAME,
                    type != null
                            ? "没有 " + type + " 类型的 SubAgent 缓存。"
                            : "没有任何 SubAgent 缓存。",
                    1.0)));
        }

        StringBuilder sb = new StringBuilder("## SubAgent 缓存列表\n\n");
        sb.append("| # | cacheId | 类型 | 消息数 | 创建时间 |\n");
        sb.append("|---|---------|------|--------|----------|\n");
        int idx = 1;
        for (CacheInfo ci : caches) {
            String shortTime = ci.createdAt().length() > 16
                    ? ci.createdAt().substring(0, 16) : ci.createdAt();
            sb.append(String.format("| %d | `%s` | %s | %d | %s |\n",
                    idx++, ci.sessionId(), ci.agentType(),
                    ci.messageCount(), shortTime));
        }
        sb.append("\n使用 `dispatch_sub_agent` 的 `cacheId` 参数传入上述 `cacheId` 以续接上下文。");

        return ToolResult.ok(NAME, List.of(new ToolResult.Item(
                "sub_agent_caches",
                NAME,
                sb.toString(),
                1.0)));
    }
}
