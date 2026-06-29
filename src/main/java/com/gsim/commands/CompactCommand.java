package com.gsim.commands;

import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.core.AgentResult;
import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
import com.gsim.cache.CachesManager;
import com.gsim.compact.CacheCompactor;
import com.gsim.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * /compact <cacheId> — 压缩指定对话缓存并自动开始新对话。
 *
 * <p>流程：
 * <ol>
 *   <li>加载 CacheSession</li>
 *   <li>CacheCompactor 压缩 → 流式输出到 CLI</li>
 *   <li>创建新 CacheSession（previousSessionId 链接）</li>
 *   <li>将压缩文本作为 user 消息注入</li>
 *   <li>自动调用 agent 开始第一轮对话</li>
 * </ol>
 */
public final class CompactCommand {

    private static final Logger log = LoggerFactory.getLogger(CompactCommand.class);

    private final CachesManager cachesManager;
    private final CacheCompactor compactor;
    private final AgentProgressSink progressSink;
    private final BiFunction<String, List<LlmMessage>, AgentResult> agentRunner;
    private final Path worldsDir;
    private final Supplier<String> worldId;

    public CompactCommand(CachesManager cachesManager, CacheCompactor compactor,
                          AgentProgressSink progressSink,
                          BiFunction<String, List<LlmMessage>, AgentResult> agentRunner,
                          Path worldsDir, Supplier<String> worldId) {
        this.cachesManager = cachesManager;
        this.compactor = compactor;
        this.progressSink = progressSink;
        this.agentRunner = agentRunner;
        this.worldsDir = worldsDir;
        this.worldId = worldId;
    }

    /**
     * 执行 /compact 命令。
     * @param cacheId 要压缩的 cache session ID
     * @return 命令结果文本
     */
    public String execute(String cacheId) {
        if (cacheId == null || cacheId.isBlank()) {
            return "用法: /compact <cacheId>\n示例: /compact Orchestrator_2026-06-27T15-50-30.json";
        }

        String wid = worldId.get();

        // 1. 加载 CacheSession
        CacheSession session = cachesManager.loadCache(wid, cacheId);
        if (session == null) {
            // 尝试直接加载（可能是完整文件名）
            session = CacheStore.load(worldsDir, cacheId);
        }
        if (session == null) {
            return "Cache 未找到: " + cacheId + " (world=" + wid + ")";
        }

        progressSink.onProgress(com.gsim.agent.AgentProgressEvent.publicMessage(
                "📦 加载 Cache: " + cacheId + " (" + session.messageCount() + " 条消息)"));

        // 2. 压缩
        String compacted = compactor.compact(session, progressSink);

        progressSink.onProgress(com.gsim.agent.AgentProgressEvent.publicMessage(
                "\n━━━ 压缩结果 ━━━\n" + compacted + "\n━━━━━━━━━━━━━━"));

        // 3. 创建新 session
        CacheSession newSession = CacheStore.createNew(worldsDir, wid,
                session.agentName(), session.nodeId());
        newSession.previousSessionId(session.sessionId());
        newSession.compressionNote("compacted from " + session.sessionId()
                + " (" + session.messageCount() + " messages → " + compacted.length() + " chars)");
        CacheStore.save(worldsDir, newSession);

        progressSink.onProgress(com.gsim.agent.AgentProgressEvent.publicMessage(
                "📝 新会话: " + newSession.sessionId()));

        // 4. 注入压缩文本并开始对话
        List<LlmMessage> priorMessages = List.of(
                LlmMessage.user("以下是之前对话历史的压缩摘要，请基于此继续：\n\n" + compacted));

        progressSink.onProgress(com.gsim.agent.AgentProgressEvent.publicMessage(
                "\n🤖 正在基于压缩摘要开始新对话…\n"));

        AgentResult result = agentRunner.apply(
                "（上下文已压缩）请基于以上对话历史摘要，给出你的理解和分析。",
                priorMessages);

        if (result.success()) {
            return "";  // 流式输出已显示在 CLI
        }
        return "[Compact error] " + result.error();
    }
}
