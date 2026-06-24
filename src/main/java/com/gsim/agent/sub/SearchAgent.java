package com.gsim.agent.sub;

import com.gsim.agent.AgentProgressSink;
import com.gsim.llm.LlmManager;
import com.gsim.resource.PromptResourceManager;
import com.gsim.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * SearchAgent — 深度资料搜索子代理。
 *
 * <p>根据用户提供的搜索查询（searchQuery），使用只读工具进行多源搜索和资料收集。
 * 拥有独立的 ToolLoop 和 StreamPool。
 *
 * <p>Prompt 来源: gsim/prompts/search/system.md + user.md
 */
public class SearchAgent extends SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SearchAgent.class);

    private final String searchQuery;

    public SearchAgent(String agentId, LlmManager llmManager, ToolRegistry toolRegistry,
                       String model, AgentProgressSink progressSink, String searchQuery) {
        super(agentId, llmManager, toolRegistry, model, progressSink);
        this.searchQuery = searchQuery != null ? searchQuery : "";
    }

    @Override
    protected String buildSystemPrompt() {
        try {
            return PromptResourceManager.getAgentPrompt("search", "system");
        } catch (IOException e) {
            log.error("[SearchAgent:{}] failed to load search/system.md: {}", agentId, e.getMessage());
            throw new UncheckedIOException("SearchAgent system prompt not found on classpath", e);
        }
    }

    @Override
    protected String buildUserPrompt() {
        try {
            return PromptResourceManager.renderAgentPrompt("search", "user",
                    Map.of("query", searchQuery));
        } catch (IOException e) {
            log.error("[SearchAgent:{}] failed to render search/user.md: {}", agentId, e.getMessage());
            return "请搜索以下内容：\n\n" + searchQuery;
        }
    }
}
