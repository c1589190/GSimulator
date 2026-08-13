package com.gsim.platform;

import com.gsim.app.AppConfig;
import com.gsim.tool.ToolRegistry;
import java.nio.file.Path;

/**
 * FeatureContext -- 提供给 {@link FeatureModule} 的核心服务句柄。
 *
 * <p>在入口装配阶段构建，承载功能模块注册工具所需的公共资源。功能模块
 * 通过它访问核心 {@link ToolRegistry} 与配置，而不直接依赖核心装配类。
 */
public final class FeatureContext {

    private final ToolRegistry toolRegistry;
    private final AppConfig config;

    /**
     * 创建功能模块上下文。
     *
     * @param toolRegistry 核心工具注册表（工具统一注册入口）
     * @param config       应用配置
     */
    public FeatureContext(ToolRegistry toolRegistry, AppConfig config) {
        this.toolRegistry = toolRegistry;
        this.config = config;
    }

    /**
     * 返回核心工具注册表。
     *
     * @return 工具注册表
     */
    public ToolRegistry toolRegistry() {
        return toolRegistry;
    }

    /**
     * 返回应用配置。
     *
     * @return 应用配置
     */
    public AppConfig config() {
        return config;
    }

    /**
     * 返回 GSim worlds 目录。
     *
     * @return worlds 目录
     */
    public Path worldsDir() {
        return config.worldsDir();
    }

    /**
     * 返回导入资料目录（可能为 null）。
     *
     * @return import 目录
     */
    public Path importDir() {
        return config.getImportDir();
    }
}
