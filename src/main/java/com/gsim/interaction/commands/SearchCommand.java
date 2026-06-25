package com.gsim.interaction.commands;

import com.gsim.agent.core.AbstractAgent;
import com.gsim.agent.core.AgentFactory;
import com.gsim.agent.core.AgentResult;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class SearchCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(SearchCommand.class);
    private final AgentFactory agentFactory;

    public SearchCommand(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    @Override public String name() { return "search"; }
    @Override public String description() { return "创建 SearchAgent 进行深度资料搜索"; }
    @Override public String usage() { return "/search <搜索查询>"; }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length == 0) return InteractionResult.fail("用法: /search <搜索查询>");
        String query = String.join(" ", args).trim();
        try {
            AbstractAgent agent = agentFactory.create("search", query, Map.of("query", query));
            AgentResult result = agent.run(query);
            if (result.success()) return InteractionResult.ok("search done", result.finalText());
            else return InteractionResult.fail("SearchAgent 失败: " + result.error());
        } catch (Exception e) {
            return InteractionResult.fail("SearchAgent 异常: " + e.getMessage());
        }
    }
}
