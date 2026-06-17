package com.gsim.task;

import java.util.List;

/**
 * 任务执行结果。
 */
public record TaskResult(
        String taskId,
        boolean success,
        String summary,
        List<String> outputFilePaths,
        List<String> errors
) {
}
