package com.gsim.agent;

import java.util.List;

/**
 * 出文结果 — WriterAgent 生成的输出。
 */
public record WriterOutput(
        String title,
        String publicText,
        String privateNotes,
        List<String> citations,
        List<String> warnings
) {
}
