package com.gsim.interaction;

/**
 * Web 交互适配器 — 预留给未来 Web UI。
 * 第一版为空实现，仅保留接口约定。
 */
public class WebInteractionAdapter {

    private final InteractionManager manager;

    public WebInteractionAdapter(InteractionManager manager) {
        this.manager = manager;
    }

    /**
     * 通过 HTTP 请求处理命令。
     * 未来实现：接收 JSON 请求，返回 JSON 响应。
     *
     * @param rawInput  用户输入
     * @param session   会话
     * @return 交互结果（可序列化为 JSON）
     */
    public InteractionResult handle(String rawInput, InteractionSession session) {
        // 第一版：直接委托给 InteractionManager
        return manager.handle(rawInput, session);
    }
}
