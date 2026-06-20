package com.gsim.agent;

import java.util.Collections;
import java.util.Set;

/**
 * 工具权限配置 — v2 默认工具集已移至 {@link ToolGroup#DEFAULT_TOOLS}。
 *
 * <p>TODO: 改为从 config/tool-permissions.yml 加载。
 * 当前为空壳保留，供 ToolExecutionPolicy 初始化使用。
 */
public class ToolPermissionConfig {

    /** 返回空集合 — 默认工具由 ToolGroupManager 管理。 */
    public Set<String> defaultEnabledTools() {
        return Collections.emptySet();
    }

    /** 是否默认允许 MUTATING 工具 */
    private boolean defaultAllowMutating = false;

    /** 是否默认允许 DESTRUCTIVE 工具 */
    private boolean defaultAllowDestructive = false;

    /** 当前是否允许 MUTATING 工具（需确认后设置）。 */
    public boolean isDefaultAllowMutating() {
        return defaultAllowMutating;
    }

    /** 设置默认允许 MUTATING（如"一直允许本轮"）。 */
    public void setDefaultAllowMutating(boolean v) {
        this.defaultAllowMutating = v;
    }

    public boolean isDefaultAllowDestructive() {
        return defaultAllowDestructive;
    }

    public void setDefaultAllowDestructive(boolean v) {
        this.defaultAllowDestructive = v;
    }
}
