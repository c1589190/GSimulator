package com.gsim.interaction;

import java.util.List;

/**
 * 交互结果 — 命令执行后返回给交互适配器的统一结果。
 */
public record InteractionResult(
        boolean success, String message, String displayText, List<String> outputFiles, List<String> errors) {

    /**
     * 创建成功结果。
     *
     * @param displayText 要显示的文本内容
     * @return 成功结果实例
     */
    public static InteractionResult ok(String displayText) {
        return new InteractionResult(true, "OK", displayText, List.of(), List.of());
    }

    /**
     * 创建带消息的成功结果。
     *
     * @param message     结果消息
     * @param displayText 要显示的文本内容
     * @return 成功结果实例
     */
    public static InteractionResult ok(String message, String displayText) {
        return new InteractionResult(true, message, displayText, List.of(), List.of());
    }

    /**
     * 创建带消息和输出文件的成功结果。
     *
     * @param message     结果消息
     * @param displayText 要显示的文本内容
     * @param outputFiles 输出文件路径列表
     * @return 成功结果实例
     */
    public static InteractionResult ok(String message, String displayText, List<String> outputFiles) {
        return new InteractionResult(true, message, displayText, outputFiles, List.of());
    }

    /**
     * 创建失败结果。
     *
     * @param errorMessage 错误消息
     * @return 失败结果实例
     */
    public static InteractionResult fail(String errorMessage) {
        return new InteractionResult(false, errorMessage, "Error: " + errorMessage, List.of(), List.of(errorMessage));
    }

    /**
     * 创建带显示文本和多错误的失败结果。
     *
     * @param message     结果消息
     * @param displayText 要显示的文本内容
     * @param errors      错误列表
     * @return 失败结果实例
     */
    public static InteractionResult fail(String message, String displayText, List<String> errors) {
        return new InteractionResult(false, message, displayText, List.of(), errors);
    }
}
