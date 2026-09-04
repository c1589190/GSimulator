package com.gsim.agentsmanager.tool;

import com.gsim.agentsmanager.cache.CacheInfo;
import com.gsim.agentsmanager.cache.CacheSession;
import com.gsim.agentsmanager.cache.CachesManager;
import com.gsim.agentsmanager.config.CoreConfig;
import com.gsim.agentsmanager.llm.ToolDef;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.staging.DocStaging;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * query_sub_agent_cache — 关键检索 SubAgent 缓存中的消息条目。
 *
 * <p>跨一个或多个缓存，按关键词匹配消息 content（大小写不敏感，空格分隔的词须全部命中）。
 * 单条消息 content 超过 {@code agent.subagent.cache.staging.threshold} 时自动暂存为 TMP 文档，
 * 返回 docId 供 gsim_doc_read 读取全文；否则内联返回（detail=true 时完整，否则截断到 400 字符）。
 */
public class QuerySubAgentCacheTool implements AgentTool {

    public static final String NAME = "query_sub_agent_cache";

    private static final String DOC_PREFIX = "subagentcache_";
    private static final int INLINE_TRUNCATE = 400;

    private final CachesManager cachesManager;
    private final DocStore docStore;
    private final CoreConfig coreConfig;

    public QuerySubAgentCacheTool(CachesManager cachesManager, DocStore docStore, CoreConfig coreConfig) {
        this.cachesManager = cachesManager;
        this.docStore = docStore;
        this.coreConfig = coreConfig;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                SubAgent 缓存消息关键检索：跨一个或多个缓存，按关键词匹配消息内容。
                参数:
                - cacheId (可选): 限定单个 cache sessionId；为空则跨全部 SubAgent 缓存。
                - keywords (必填): 空格分隔的关键词，命中消息须包含全部词（不区分大小写）。
                - detail (可选): true=逐条完整返回（超长暂存 doc），false=截断到 400 字符。
                超长单条自动暂存为 TMP 文档，返回 docId 可用 gsim_doc_read 读取全文。
                """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolDef.strictSchema(
                Map.of(
                        "keywords", Map.of("type", "string", "description", "空格分隔的关键词（匹配消息内容，全部命中才算）"),
                        "cacheId",
                                Map.of(
                                        "type", "string",
                                        "description", "可选 — 限定单个 cache sessionId，为空则跨全部 SubAgent 缓存"),
                        "detail",
                                Map.of("type", "boolean", "description", "可选 — true 完整返回，false 截断到 400 字符（默认 false）")),
                List.of("keywords"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String keywords = call.param("keywords", "").trim();
        if (keywords.isEmpty()) {
            return ToolResult.fail(NAME, "keywords 是必填的");
        }
        String cacheId = call.param("cacheId", "").trim();
        boolean detail = "true".equalsIgnoreCase(call.param("detail"));
        int threshold = coreConfig.getInt(CoreConfig.CACHE_STAGING_THRESHOLD, 3000);

        List<String> tokens = tokenize(keywords);
        if (tokens.isEmpty()) {
            return ToolResult.fail(NAME, "keywords 无法解析为有效关键词");
        }

        List<CacheSession> targets = resolveTargets(cacheId);
        if (targets.isEmpty()) {
            return ToolResult.ok(
                    NAME,
                    List.of(new ToolResult.Item(
                            "no_cache", NAME, cacheId.isEmpty() ? "没有可检索的 SubAgent 缓存。" : "缓存不存在: " + cacheId, 1.0)));
        }

        List<ToolResult.Item> items = new ArrayList<>();
        for (CacheSession session : targets) {
            scanSession(session, tokens, detail, threshold, items);
        }

        if (items.isEmpty()) {
            return ToolResult.ok(
                    NAME,
                    List.of(new ToolResult.Item(
                            "no_match",
                            NAME,
                            "没有匹配关键词 '" + keywords + "' 的缓存消息" + (cacheId.isEmpty() ? "" : "（缓存 " + cacheId + "）"),
                            1.0)));
        }

        return ToolResult.ok(NAME, items);
    }

    private List<CacheSession> resolveTargets(String cacheId) {
        List<CacheSession> result = new ArrayList<>();
        if (!cacheId.isEmpty()) {
            CacheSession s = cachesManager.loadCache(cacheId);
            if (s != null) result.add(s);
            return result;
        }
        for (CacheInfo ci : cachesManager.listCaches()) {
            if ("sim".equals(ci.agentType()) || "search".equals(ci.agentType())) {
                CacheSession s = cachesManager.loadCache(ci.sessionId());
                if (s != null) result.add(s);
            }
        }
        return result;
    }

    private void scanSession(
            CacheSession session, List<String> tokens, boolean detail, int threshold, List<ToolResult.Item> items) {
        List<Map<String, Object>> messages = session.messages();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            String role = String.valueOf(msg.getOrDefault("role", "?"));
            if ("system".equals(role)) continue;
            Object contentObj = msg.getOrDefault("content", "");
            String content = contentObj != null ? String.valueOf(contentObj) : "";
            if (content.isBlank()) continue;
            String lowered = content.toLowerCase(Locale.ROOT);
            boolean allHit = true;
            for (String t : tokens) {
                if (!lowered.contains(t)) {
                    allHit = false;
                    break;
                }
            }
            if (!allHit) continue;

            String title = session.sessionId() + "#" + i + " (" + role + ")";
            String body;
            if (content.length() > threshold && docStore != null) {
                body = DocStaging.stageOrInline(docStore, DOC_PREFIX, title, content);
            } else if (!detail && content.length() > INLINE_TRUNCATE) {
                body = content.substring(0, INLINE_TRUNCATE) + "… (截断，detail=true 看全文)";
            } else {
                body = content;
            }
            String path = session.sessionId() + "#" + i;
            items.add(new ToolResult.Item(title, path, body, 1.0));
        }
    }

    private static List<String> tokenize(String keywords) {
        List<String> tokens = new ArrayList<>();
        StringTokenizer st = new StringTokenizer(keywords, " \t\n\r");
        while (st.hasMoreTokens()) {
            tokens.add(st.nextToken().toLowerCase(Locale.ROOT));
        }
        return tokens;
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
