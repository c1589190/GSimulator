package com.gsim.context;

import java.util.List;

/**
 * 渲染后的完整上下文。
 */
public record RenderedContext(
        String activeWorld,
        String activeBranch,
        int chainLength,
        boolean systemPromptExists,
        List<RenderedMessage> messages
) {}
