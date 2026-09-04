package com.gsim.agent.tool;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.cache.CacheSession;
import com.gsim.core.cache.CacheStore;
import com.gsim.core.cache.CachesManager;
import com.gsim.core.compact.CacheCompactor;
import com.gsim.core.event.AgentProgressEvent;
import com.gsim.core.event.AgentProgressSink;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 压缩 SubAgent 对话缓存 — Agent 可调用的压缩工具。
 *
 * <p>约束：只能压缩 SubAgent cache（agentName 匹配 sim-\d+ 或 search-\d+）。
 *
 * <p>流程：
 * <ol>
 *   <li>验证 cacheId 是 SubAgent cache</li>
 *   <li>加载 CacheSession 并压缩</li>
 *   <li>创建新 CacheSession（previousSessionId 链接，相同 agentName）</li>
 *   <li>返回压缩文本 + 新 cacheId，Agent 可用 dispatch_sub_agent 重新调用</li>
 * </ol>
 */
public final class CompactCacheTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(CompactCacheTool.class);

    private final CachesManager cachesManager;
    private final CacheCompactor compactor;
    private final AgentProgressSink progressSink;

    public CompactCacheTool(CachesManager cachesManager, CacheCompactor compactor, AgentProgressSink progressSink) {
        this.cachesManager = cachesManager;
        this.compactor = compactor;
        this.progressSink = progressSink;
    }

    @Override
    public String name() {
        return "compact_cache";
    }

    @Override
    public String description() {
        return "压缩一个 SubAgent 的对话历史缓存，创建新缓存文件并返回压缩摘要。"
                + "压缩后可调用 dispatch_sub_agent 重新激活该 SubAgent 继续工作。"
                + "参数: cacheId (SubAgent cache 文件名, 必填, 仅支持 sim-* 或 search-* 类型)。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "cacheId",
                                Map.of("type", "string", "description", "SubAgent cache 文件名（如 sim-1_2026-...json）")),
                "required", List.of("cacheId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String cacheId = call.param("cacheId", "").trim();
        if (cacheId.isEmpty()) return ToolResult.fail(name(), "cacheId 不能为空");

        // 验证是 SubAgent cache
        if (!isSubAgentCache(cacheId)) {
            return ToolResult.fail(name(), "只能压缩 SubAgent 的 cache（文件名需以 sim- 或 search- 开头，当前: " + cacheId + "）");
        }

        // 加载
        CacheSession session = cachesManager.loadCache(cacheId);
        if (session == null) {
            session = CacheStore.load(cacheId);
        }
        if (session == null) {
            return ToolResult.fail(name(), "Cache 未找到: " + cacheId);
        }

        progressSink.onProgress(AgentProgressEvent.publicMessage(
                "  📦 [compact_cache] 压缩 SubAgent cache: " + cacheId + " (" + session.messageCount() + " 条消息)"));

        // 压缩
        String compacted = compactor.compact(session, progressSink);

        // 创建新 session
        CacheSession newSession = CacheStore.createNew(session.agentName());
        newSession.previousSessionId(session.sessionId());
        newSession.compressionNote("compacted by compact_cache from " + session.sessionId() + " ("
                + session.messageCount() + " msgs → " + compacted.length() + " chars)");
        CacheStore.save(newSession);

        // 返回压缩文本 + 新 cacheId
        String snippet = "压缩摘要:\n" + compacted
                + "\n\n新缓存: " + newSession.sessionId()
                + "\n原始缓存: " + session.sessionId()
                + " (" + session.messageCount() + " 条消息 → " + compacted.length() + " 字符)"
                + "\n\n使用 dispatch_sub_agent 工具重新激活此 SubAgent，cacheId 参数传: "
                + newSession.sessionId();

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item("压缩完成: " + session.agentName(), newSession.sessionId(), snippet, 1.0)));
    }

    /**
     * 判断给定的 cacheId 是否为 SubAgent 缓存文件标识。
     *
     * @param cacheId 缓存文件标识（文件名）
     * @return 若以 {@code sim-} 或 {@code search-} 开头则为 {@code true}
     */
    static boolean isSubAgentCache(String cacheId) {
        return cacheId != null && cacheId.matches("^(sim|search)-\\d+.*");
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
