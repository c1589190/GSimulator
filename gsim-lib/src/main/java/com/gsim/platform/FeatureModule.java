package com.gsim.platform;

import com.gsim.app.AppConfig;

/**
 * FeatureModule -- 可插拔功能模块契约（如 {@code gsimap}、{@code gsim-agent}）。
 *
 * <p>由入口（{@code gsim-app} 的 {@code Main}）通过 {@link java.util.ServiceLoader}
 * 发现实现，并按 {@link #isEnabled(AppConfig)} 决定是否激活。核心平台
 * （MCP 协议、工具注册、World/Doc 管理）不依赖任何具体功能模块，功能模块
 * 通过本接口向核心注册工具、启动自己的 HTTP 服务器或 REPL。
 *
 * <p>生命周期：{@code isEnabled} → {@code register}（注册工具）→ {@code start}
 * （启动副作用）→ {@code stop}（关闭资源）。
 */
public interface FeatureModule {

    /**
     * 返回模块名称，用于日志与诊断（如 {@code "gsimap"}）。
     *
     * @return 模块名称
     */
    String name();

    /**
     * 判断模块是否启用。
     *
     * <p>例如 gsimap 默认启用；gsim-agent 默认关闭，仅当配置
     * {@code agent.enabled=true} 或传入 {@code --with-agent} 时启用。
     *
     * @param config 应用配置
     * @return true 表示启用
     */
    boolean isEnabled(AppConfig config);

    /**
     * 注册模块的工具到核心 {@code ToolRegistry}（纯注册，无外部副作用）。
     *
     * @param ctx 核心服务句柄
     */
    void register(FeatureContext ctx);

    /**
     * 启动模块的 HTTP 服务器 / REPL 等副作用。
     *
     * <p>默认空实现；仅在 {@link #register(FeatureContext)} 之后调用。
     */
    default void start() {}

    /**
     * 关闭模块持有的资源（HTTP 服务器、连接池等）。
     *
     * <p>默认空实现。
     */
    default void stop() {}
}
