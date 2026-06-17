package com.gsim.interaction;

import java.util.List;

/**
 * 交互结果 — 命令执行后返回给交互适配器的统一结果。
 */
public record InteractionResult(
        boolean success,
        String message,
        String displayText,
        List<String> outputFiles,
        List<String> errors
) {
    public static InteractionResult ok(String displayText) {
        return new InteractionResult(true, "OK", displayText, List.of(), List.of());
    }

    public static InteractionResult ok(String message, String displayText) {
        return new InteractionResult(true, message, displayText, List.of(), List.of());
    }

    public static InteractionResult ok(String message, String displayText, List<String> outputFiles) {
        return new InteractionResult(true, message, displayText, outputFiles, List.of());
    }

    public static InteractionResult fail(String errorMessage) {
        return new InteractionResult(false, errorMessage, "Error: " + errorMessage, List.of(), List.of(errorMessage));
    }

    public static InteractionResult fail(String message, String displayText, List<String> errors) {
        return new InteractionResult(false, message, displayText, List.of(), errors);
    }
}
