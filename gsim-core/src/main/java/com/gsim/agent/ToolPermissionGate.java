package com.gsim.agent;

/**
 * 工具权限门禁 — 当工具需要确认时，通过此接口向用户请求许可。
 *
 * <p>CLI 实现：阻塞等待用户输入（数字菜单 / JLine 选择）。
 * 测试实现：返回预编程的选择。
 */
public interface ToolPermissionGate {

    /**
     * 对给定的工具请求用户确认。阻塞直到用户做出选择。
     */
    ConfirmationChoice askConfirmation(ToolConfirmationRequest request);
}
