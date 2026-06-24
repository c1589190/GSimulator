package com.gsim.interaction.commands;

import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.sub.SearchAgent;
import com.gsim.agent.sub.SubAgentResult;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.llm.LlmManager;
import com.gsim.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * /search — 直接创建 SearchAgent 进行深度资料搜索。
 *
 * <p>绕过 OrchestratorAgent，直接在当前线程同步执行。
 * 适用于 CLI 直接调用场景。
 */
public class SearchCommand implements InteractionCommand {

    private static final Logger log = LoggerFactory.getLogger(SearchCommand.class);

    private final LlmManager llmManager;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final AgentProgressSink progressSink;
    private final AtomicInteger counter = new AtomicInteger(0);

    public SearchCommand(LlmManager llmManager, ToolRegistry toolRegistry,
                         String model, AgentProgressSink progressSink) {
        this.llmManager = llmManager;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.progressSink = progressSink != null ? progressSink : AgentProgressSink.NOOP;
    }

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "创建 SearchAgent 进行深度资料搜索（绕过主 Agent，直接执行）";
    }

    @Override
    public String usage() {
        return "/search <搜索查询>";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length == 0) {
            return InteractionResult.fail("用法: /search <搜索查询>");
        }
        if (llmManager == null || !llmManager.isAvailable()) {
            return InteractionResult.fail("LLM 未配置。请先运行 /config init 设置 LLM。");
        }

        String query = String.join(" ", args).trim();
        String agentId = "search-cmd-" + counter.incrementAndGet();

        SearchAgent agent = new SearchAgent(agentId, llmManager, toolRegistry, model,
                progressSink, query);

        log.info("[SearchCommand] running {} synchronously, queryLen={}", agentId, query.length());

        // 同步执行（不走 VT，直接在当前线程跑 ToolLoop）
        agent.run();
        SubAgentResult result;
        try {
            result = agent.future().get();
        } catch (Exception e) {
            return InteractionResult.fail("SearchAgent 执行异常: " + e.getMessage());
        }

        if (result.success()) {
            return InteractionResult.ok("search done (" + agentId + ")", result.text());
        } else {
            return InteractionResult.fail("SearchAgent (" + agentId + ") 失败: "
                    + (result.error() != null ? result.error() : "未知错误"));
        }
    }
}
