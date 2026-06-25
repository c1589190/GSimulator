package com.gsim.interaction.commands;

import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.core.AbstractAgent;
import com.gsim.agent.core.AgentFactory;
import com.gsim.agent.core.AgentResult;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class SimCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(SimCommand.class);
    private final AgentFactory agentFactory;
    private final AgentProgressSink progressSink;

    public SimCommand(AgentFactory agentFactory, AgentProgressSink progressSink) {
        this.agentFactory = agentFactory;
        this.progressSink = progressSink != null ? progressSink : AgentProgressSink.NOOP;
    }

    @Override public String name() { return "sim"; }
    @Override public String description() { return "创建 SimAgent 进行推演叙事生成（独立子代理，只读工具）"; }
    @Override public String usage() { return "/sim <推演指令>"; }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length == 0) return InteractionResult.fail("用法: /sim <推演指令>");
        String prompt = String.join(" ", args).trim();
        try {
            AbstractAgent agent = agentFactory.create("sim", prompt, Map.of("prompt", prompt));
            AgentResult result = agent.run(prompt);
            if (result.success()) return InteractionResult.ok("sim done", result.finalText());
            else return InteractionResult.fail("SimAgent 失败: " + result.error());
        } catch (Exception e) {
            return InteractionResult.fail("SimAgent 异常: " + e.getMessage());
        }
    }
}
