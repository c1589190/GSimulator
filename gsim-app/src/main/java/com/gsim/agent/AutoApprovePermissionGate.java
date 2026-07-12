package com.gsim.agent;

/**
 * API/Headless 模式下的工具权限门禁 — 自动批准所有工具调用。
 */
public class AutoApprovePermissionGate implements ToolPermissionGate {

    @Override
    public ConfirmationChoice askConfirmation(ToolConfirmationRequest request) {
        return ConfirmationChoice.ALLOW_ALL_THIS_TURN;
    }
}
