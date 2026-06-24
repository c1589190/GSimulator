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
 * SimAgent — 推演内容生成子代理。
 *
 * <p>根据用户提供的推演方向（simPrompt），使用只读工具查询上下文，
 * 生成推演叙事文本。拥有独立的 ToolLoop 和 StreamPool。
 *
 * <p>Prompt 来源: gsim/prompts/sim/system.md + user.md
 */
public class SimAgent extends SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SimAgent.class);

    private final String simPrompt;

    public SimAgent(String agentId, LlmManager llmManager, ToolRegistry toolRegistry,
                    String model, AgentProgressSink progressSink, String simPrompt) {
        super(agentId, llmManager, toolRegistry, model, progressSink);
        this.simPrompt = simPrompt != null ? simPrompt : "";
    }

    @Override
    protected String buildSystemPrompt() {
        try {
            return PromptResourceManager.getAgentPrompt("sim", "system");
        } catch (IOException e) {
            log.error("[SimAgent:{}] failed to load sim/system.md: {}", agentId, e.getMessage());
            throw new UncheckedIOException("SimAgent system prompt not found on classpath", e);
        }
    }

    @Override
    protected String buildUserPrompt() {
        try {
            return PromptResourceManager.renderAgentPrompt("sim", "user",
                    Map.of("prompt", simPrompt));
        } catch (IOException e) {
            log.error("[SimAgent:{}] failed to render sim/user.md: {}", agentId, e.getMessage());
            return "请基于以下指令进行推演：\n\n" + simPrompt;
        }
    }
}
