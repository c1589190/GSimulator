package com.gsim.agent.management;

import com.gsim.event.EventBus;
import com.gsim.event.FilteredEventSink;
import com.gsim.event.GSimEvent;
import com.gsim.event.SseWriter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE 生命周期管理器 — 封装 EventBus + SseWriter 交互。
 *
 * <p>为每个 Agent 实例提供独立的 SSE 事件流订阅。
 */
public class AgentSseManager {

    private static final Logger log = LoggerFactory.getLogger(AgentSseManager.class);

    private final EventBus eventBus;

    /**
     * 构造 SSE 生命周期管理器。
     *
     * @param eventBus 全局 EventBus 实例，用于事件发布和订阅
     */
    public AgentSseManager(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 为指定 Agent 实例创建 SSE 事件流订阅。
     *
     * <p>使用 Agent 的独立 sessionId + taskId 进行事件过滤，
     * 只返回该 Agent 相关的事件。订阅者会阻塞等待 Agent 完成或客户端断开。
     *
     * @param exchange   HTTP exchange
     * @param instanceId Agent 实例 ID
     * @param sessionId  Agent 的独立 sessionId
     * @param taskId     Agent 的独立 taskId
     * @param agentsManager AgentsManager（用于等待完成）
     */
    public void streamEvents(
            HttpExchange exchange, String instanceId, String sessionId, String taskId, AgentsManager agentsManager)
            throws IOException {
        SseWriter sse = new SseWriter(exchange);
        sse.sendHeaders();

        FilteredEventSink sink = new FilteredEventSink(sse, sessionId, taskId);
        eventBus.subscribe(sink);

        try {
            // 等待 Agent 完成（最长 5 分钟）
            agentsManager.waitForCompletion(instanceId, 300_000);
        } catch (Exception e) {
            log.warn("SSE stream interrupted for {}: {}", instanceId, e.getMessage());
            try {
                sse.writeEvent("error", Map.of("message", "Stream interrupted", "instanceId", instanceId));
            } catch (IOException ignored) {
            }
        } finally {
            eventBus.unsubscribe(sink);
            sink.close();
            sse.close();
        }
    }

    /**
     * 直接发布事件到 EventBus。
     *
     * @param sessionId 会话 ID
     * @param taskId    任务 ID
     * @param type      事件类型
     * @param data      事件数据
     */
    public void publish(String sessionId, String taskId, String type, Map<String, Object> data) {
        eventBus.publish(GSimEvent.of(sessionId, taskId, type, data));
    }
}
