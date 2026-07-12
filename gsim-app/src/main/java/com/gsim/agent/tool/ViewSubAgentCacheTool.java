package com.gsim.agent.tool;

import com.gsim.cache.CacheInfo;
import com.gsim.cache.CacheSession;
import com.gsim.cache.CachesManager;
import com.gsim.llm.ToolDef;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * view_sub_agent_cache 工具 — 查看指定 SubAgent 缓存的对话摘要。
 *
 * <p>参数:
 * <ul>
 *   <li>cacheId (必填): 要查看的 cache sessionId</li>
 * </ul>
 *
 * <p>返回缓存摘要（首次用户输入、最后交互、消息统计），不加载全部消息体。
 */
public class ViewSubAgentCacheTool implements AgentTool {

    public static final String NAME = "view_sub_agent_cache";

    private final CachesManager cachesManager;
    private final Supplier<String> worldIdSupplier;

    public ViewSubAgentCacheTool(CachesManager cachesManager, Supplier<String> worldIdSupplier) {
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
                查看指定 SubAgent 缓存的历史对话摘要。
                参数:
                - cacheId (必填): 要查看的 cache sessionId（从 list_sub_agent_caches 获取）。
                返回摘要包含：首次用户输入、最后几条消息、消息统计。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "cacheId", Map.of(
                                "type", "string",
                                "description", "要查看的 cache sessionId（文件名）"
                        )
                ),
                List.of("cacheId")
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = worldIdSupplier.get();
        String cacheId = call.param("cacheId", "").trim();

        if (cacheId.isEmpty()) {
            return ToolResult.fail(NAME, "cacheId 不能为空");
        }

        CacheSession session = cachesManager.loadCache(worldId, cacheId);
        if (session == null) {
            return ToolResult.fail(NAME, "缓存不存在: " + cacheId);
        }

        CacheInfo info = CacheInfo.fromSession(session);
        List<Map<String, Object>> messages = session.messages();

        StringBuilder sb = new StringBuilder("## SubAgent 缓存: `").append(cacheId).append("`\n\n");
        sb.append("- **类型**: ").append(info.agentType()).append("\n");
        sb.append("- **Agent**: ").append(info.agentName()).append("\n");
        sb.append("- **创建时间**: ").append(info.createdAt()).append("\n");
        sb.append("- **消息总数**: ").append(info.messageCount()).append("\n");

        if (info.previousSessionId() != null) {
            sb.append("- **前序缓存**: `").append(info.previousSessionId()).append("`\n");
        }

        // 显示首次 user 消息
        sb.append("\n### 首次用户输入\n\n");
        String firstUser = findFirstUserMessage(messages);
        if (firstUser != null) {
            sb.append("> ").append(truncate(firstUser, 500)).append("\n");
        } else {
            sb.append("*（无用户消息）*\n");
        }

        // 显示最后几条交互
        sb.append("\n### 最近交互（最后 3 条）\n\n");
        int shown = 0;
        for (int i = messages.size() - 1; i >= 0 && shown < 3; i--) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.getOrDefault("role", "?");
            String content = (String) msg.getOrDefault("content", "");
            if ("system".equals(role)) continue;  // 跳过 system prompt
            sb.append("**").append(role).append("**: ")
                    .append(truncate(content, 300)).append("\n\n");
            shown++;
        }

        if (info.messageCount() > 0) {
            sb.append("\n> 使用 `dispatch_sub_agent` 的 `cacheId` 参数传入 `")
                    .append(cacheId).append("` 以续接此 SubAgent 的上下文。");
        }

        return ToolResult.ok(NAME, List.of(new ToolResult.Item(
                "cache_view:" + cacheId,
                NAME,
                sb.toString(),
                1.0)));
    }

    private static String findFirstUserMessage(List<Map<String, Object>> messages) {
        for (Map<String, Object> msg : messages) {
            if ("user".equals(msg.getOrDefault("role", ""))) {
                return (String) msg.getOrDefault("content", "");
            }
        }
        return null;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
