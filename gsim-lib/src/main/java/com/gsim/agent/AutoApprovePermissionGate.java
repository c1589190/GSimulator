package com.gsim.agent;

/**
 * AutoApprovePermissionGate -- API/Headless 模式下的工具权限门禁，自动批准所有工具调用。
 *
 * <p>适用于 HTTP API 调用场景，跳过人工确认环节。
 * 与 CliToolPermissionGate 相对，后者需要在终端等待用户输入。
 */
public class AutoApprovePermissionGate implements ToolPermissionGate {

    @Override
    public ConfirmationChoice askConfirmation(ToolConfirmationRequest request) {
        return ConfirmationChoice.ALLOW_ALL_THIS_TURN;
    }
}
