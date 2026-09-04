package com.gsim.agentsmanager.tool;

import com.gsim.agentsmanager.AgentInstance;
import com.gsim.agentsmanager.cache.CacheInfo;
import com.gsim.agentsmanager.cache.CacheSession;
import com.gsim.agentsmanager.cache.CachesManager;
import com.gsim.agentsmanager.config.CoreConfig;
import com.gsim.agentsmanager.core.AgentResult;
import com.gsim.agentsmanager.llm.ToolDef;
import com.gsim.agentsmanager.management.AgentsManager;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.staging.DocStaging;
import java.util.List;
import java.util.Map;

/**
 * view_sub_agent_cache 工具 — 查看指定 SubAgent 缓存的对话摘要与运行状态。
 *
 * <p>参数:
 * <ul>
 *   <li>cacheId (必填): 要查看的 cache sessionId</li>
 * </ul>
 *
 * <p>返回缓存摘要（首次用户输入、最后交互、消息统计），不加载全部消息体。
 * 顶部状态行通过 AgentsManager 关联运行时实例：运行中 / 已完成（含最终结果）/
 * 历史缓存（仅缓存文件）。
 */
public class ViewSubAgentCacheTool implements AgentTool {

    public static final String NAME = "view_sub_agent_cache";

    private final CachesManager cachesManager;
    private final DocStore docStore;
    private final CoreConfig coreConfig;
    private volatile AgentsManager agentsManager;

    public ViewSubAgentCacheTool(CachesManager cachesManager) {
        this(cachesManager, null, null);
    }

    public ViewSubAgentCacheTool(CachesManager cachesManager, DocStore docStore, CoreConfig coreConfig) {
        this.cachesManager = cachesManager;
        this.docStore = docStore;
        this.coreConfig = coreConfig;
    }

    /**
     * 注入 AgentsManager，用于按 cacheId 关联运行时 Agent 实例并标记完成状态。
     *
     * @param am AgentsManager 实例
     */
    public void setAgentsManager(AgentsManager am) {
        this.agentsManager = am;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                查看指定 SubAgent 缓存的历史对话摘要与运行状态。
                参数:
                - cacheId (必填): 要查看的 cache sessionId（从 list_sub_agent_caches / dispatch_sub_agent 获取）。
                状态行标记: 🔄 运行中 / ✅ 已完成（含最终结果）/ ❌ 失败 / 📄 历史缓存。
                返回摘要包含：状态、首次用户输入、最后几条消息、消息统计。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "cacheId",
                        Map.of(
                                "type", "string",
                                "description", "要查看的 cache sessionId（文件名）")),
                List.of("cacheId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String cacheId = call.param("cacheId", "").trim();

        if (cacheId.isEmpty()) {
            return ToolResult.fail(NAME, "cacheId 不能为空");
        }

        CacheSession session = cachesManager.loadCache(cacheId);
        if (session == null) {
            return ToolResult.fail(NAME, "缓存不存在: " + cacheId);
        }

        CacheInfo info = CacheInfo.fromSession(session);
        List<Map<String, Object>> messages = session.messages();

        StringBuilder sb =
                new StringBuilder("## SubAgent 缓存: `").append(cacheId).append("`\n\n");

        appendStatusLine(sb, cacheId);
        sb.append("- **类型**: ").append(info.agentType()).append("\n");
        sb.append("- **Agent**: ").append(info.agentName()).append("\n");
        sb.append("- **创建时间**: ").append(info.createdAt()).append("\n");
        sb.append("- **消息总数**: ").append(info.messageCount()).append("\n");

        if (info.previousSessionId() != null) {
            sb.append("- **前序缓存**: `").append(info.previousSessionId()).append("`\n");
        }

        // 完整断点：逐条展示全部非 system 消息，单条超过缓存暂存阈值则暂存为 TMP 文档
        int threshold = coreConfig != null ? coreConfig.getInt(CoreConfig.CACHE_STAGING_THRESHOLD, 3000) : 3000;
        sb.append("\n### 完整对话\n\n");
        int shown = 0;
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.getOrDefault("role", "?");
            String content = (String) msg.getOrDefault("content", "");
            if ("system".equals(role)) continue;
            sb.append("**[").append(i).append("] ").append(role).append("**:\n");
            if (content.length() > threshold && docStore != null) {
                String title = cacheId + "#" + i + " (" + role + ")";
                sb.append(DocStaging.stageOrInline(docStore, "subagentcache_view_", title, content))
                        .append("\n\n");
            } else {
                sb.append(content).append("\n\n");
            }
            shown++;
        }
        if (shown == 0) {
            sb.append("*（无对话消息）*\n");
        }

        if (info.messageCount() > 0) {
            sb.append("\n> 使用 `dispatch_sub_agent` 的 `cacheId` 参数传入 `")
                    .append(cacheId)
                    .append("` 以续接此 SubAgent 的上下文。");
        }

        return ToolResult.ok(NAME, List.of(new ToolResult.Item("cache_view:" + cacheId, NAME, sb.toString(), 1.0)));
    }

    /**
     * 顶部状态行 — 通过 AgentsManager 关联运行时实例：
     * RUNNING → 🔄 运行中（最后 3 步见下方）；DONE → ✅ 已完成 + 最终结果；
     * FAILED/CANCELLED → 对应标记；无实例 → 📄 历史缓存。
     */
    private void appendStatusLine(StringBuilder sb, String cacheId) {
        sb.append("- **状态**: ");
        if (agentsManager == null) {
            sb.append("📄 历史缓存\n");
            return;
        }
        AgentInstance instance = agentsManager.getByCacheId(cacheId);
        if (instance == null) {
            sb.append("📄 历史缓存（无运行实例，仅缓存文件）\n");
            return;
        }
        switch (instance.status()) {
            case RUNNING, PENDING -> sb.append("🔄 运行中\n");
            case DONE -> {
                sb.append("✅ 已完成\n");
                String finalText = agentsManager.getAgentOutput(instance.instanceId());
                if (finalText == null) {
                    AgentResult result = agentsManager.getCompletedResult(instance.instanceId());
                    finalText = result != null ? result.finalText() : null;
                }
                if (finalText != null && !finalText.isBlank()) {
                    sb.append("- **最终结果**:\n> ")
                            .append(truncate(finalText, 2000))
                            .append("\n");
                }
            }
            case FAILED -> sb.append("❌ 失败")
                    .append(instance.error() != null ? ": " + truncate(instance.error(), 500) : "")
                    .append("\n");
            case CANCELLED -> sb.append("⏹ 已取消\n");
            default -> sb.append("📄 历史缓存\n");
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    @Override
    public boolean requiresWorldId() {
        return false;
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
