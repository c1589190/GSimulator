package com.gsim.api;

import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.event.GSimEvent;
import com.gsim.interaction.InteractionManager;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务管理器 — 创建和管理长任务的生命周期。
 *
 * <p>职责：
 * <ul>
 *   <li>创建任务并后台执行</li>
 *   <li>通过 EventBus 发布任务事件</li>
 *   <li>支持任务查询和取消</li>
 * </ul>
 *
 * <p>事件流（每个任务）：
 * <pre>
 *   command_started → log* → result → command_done → done
 *   或
 *   command_started → log* → command_error → done
 * </pre>
 *
 * <p>done 事件仅由 executeTask() 的 finally 块发布一次。
 */
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;
    private final EventBus eventBus;
    private final Map<String, ApiTask> tasks = new ConcurrentHashMap<>();

    public TaskManager(ApplicationContext ctx, SessionManager sessionManager, EventBus eventBus) {
        this.ctx = ctx;
        this.sessionManager = sessionManager;
        this.eventBus = eventBus;
    }

    /**
     * 创建命令任务。
     *
     * @param sessionId 会话 ID
     * @param command   命令字符串
     * @param autoStart 是否自动启动（false 则只创建 PENDING 任务）
     * @return 创建的任务
     */
    public ApiTask createCommandTask(String sessionId, String command, boolean autoStart) {
        if (!autoStart) {
            return reserveTask(sessionId, command);
        }
        String taskId = IdGenerator.taskId();
        ApiTask task = ApiTask.createPending(taskId, sessionId, command);
        tasks.put(taskId, task);

        log.info("Task created: {} ({})", taskId, command);

        // 在虚拟线程中执行
        Thread.startVirtualThread(() -> executeTask(task));

        return task;
    }

    /**
     * 创建命令任务并立即开始后台执行（向后兼容）。
     */
    public ApiTask createCommandTask(String sessionId, String command) {
        return createCommandTask(sessionId, command, true);
    }

    /**
     * 预留一个任务 ID（PENDING 状态），不开始执行。
     * 调用者应先订阅 EventBus，再调用 {@link #executePendingTask(String)}。
     *
     * @param sessionId 会话 ID
     * @param command   命令字符串
     * @return 预留的任务
     */
    public ApiTask reserveTask(String sessionId, String command) {
        String taskId = IdGenerator.taskId();
        ApiTask task = ApiTask.createPending(taskId, sessionId, command);
        tasks.put(taskId, task);
        log.info("Task reserved: {} ({})", taskId, command);
        return task;
    }

    /**
     * 执行已预留的 PENDING 任务。
     *
     * @param taskId 任务 ID
     * @throws IllegalStateException 如果任务不存在或状态不是 PENDING
     */
    public void executePendingTask(String taskId) {
        ApiTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (task.status() != ApiTaskStatus.PENDING) {
            throw new IllegalStateException("Task " + taskId + " is not PENDING: " + task.status());
        }
        Thread.startVirtualThread(() -> executeTask(task));
    }

    /**
     * 获取任务。
     */
    public ApiTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 列出所有任务（按创建时间倒序）。
     */
    public List<ApiTask> listTasks() {
        List<ApiTask> list = new ArrayList<>(tasks.values());
        list.sort((a, b) -> b.startedAt().compareTo(a.startedAt()));
        return list;
    }

    /**
     * 取消任务（标记取消，不强杀线程）。
     * 发布 command_error 事件通知客户端，done 由 executeTask finally 统一发布。
     */
    public boolean cancelTask(String taskId) {
        ApiTask task = tasks.get(taskId);
        if (task == null) return false;

        ApiTaskStatus current = task.status();
        if (current == ApiTaskStatus.PENDING || current == ApiTaskStatus.RUNNING) {
            ApiTask cancelled = new ApiTask(
                    task.taskId(), task.sessionId(), task.command(),
                    ApiTaskStatus.CANCELLED, task.startedAt(), Instant.now(),
                    task.result(), task.error()
            );
            tasks.put(taskId, cancelled);
            eventBus.publish(GSimEvent.of(task.sessionId(), taskId, "command_error",
                    Map.of("error", "Task cancelled")));
            // done 由 executeTask() finally 统一发布，此处不重复
            log.info("Task cancelled: {}", taskId);
            return true;
        }
        return false;
    }

    /**
     * 阻塞等待任务完成。
     */
    public ApiTask waitForCompletion(String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ApiTask task = tasks.get(taskId);
            if (task == null) return null;
            if (isTerminal(task.status())) return task;
            Thread.sleep(100);
        }
        return tasks.get(taskId);
    }

    private boolean isTerminal(ApiTaskStatus status) {
        return status == ApiTaskStatus.DONE
                || status == ApiTaskStatus.FAILED
                || status == ApiTaskStatus.CANCELLED;
    }

    /**
     * 检查任务是否已被取消（检查 Map 中的最新状态）。
     */
    private boolean isCancelled(String taskId) {
        ApiTask task = tasks.get(taskId);
        return task != null && task.status() == ApiTaskStatus.CANCELLED;
    }

    private void executeTask(ApiTask pendingTask) {
        String taskId = pendingTask.taskId();
        String sessionId = pendingTask.sessionId();
        String command = pendingTask.command();

        // 标记 RUNNING（除非已被取消）
        if (isCancelled(taskId)) return;
        ApiTask running = new ApiTask(
                taskId, sessionId, command,
                ApiTaskStatus.RUNNING, pendingTask.startedAt(), null, null, null
        );
        tasks.put(taskId, running);

        // 发布 command_started
        Map<String, Object> startedData = new LinkedHashMap<>();
        startedData.put("command", command);
        eventBus.publish(GSimEvent.of(sessionId, taskId, "command_started", startedData));

        try {
            // 获取 session 并执行命令
            InteractionSession session = sessionManager.getOrCreateSession(sessionId);
            InteractionManager manager = ctx.getInteractionManager();

            eventBus.publish(GSimEvent.of(sessionId, taskId, "log",
                    Map.of("message", "Executing: " + command)));

            InteractionResult result = manager.handle(command, session);

            // 构建结果数据
            Map<String, Object> resultData = new LinkedHashMap<>();
            resultData.put("command", command);
            resultData.put("success", result.success());
            resultData.put("message", result.message());
            resultData.put("displayText", result.displayText());
            if (result.outputFiles() != null && !result.outputFiles().isEmpty()) {
                resultData.put("outputFiles", result.outputFiles());
            }
            if (result.errors() != null && !result.errors().isEmpty()) {
                resultData.put("errors", result.errors());
            }

            // 发布 result 事件
            eventBus.publish(GSimEvent.of(sessionId, taskId, "result", resultData));

            // 写入最终状态前检查是否已被取消
            if (isCancelled(taskId)) return;

            if (result.success()) {
                // 标记 DONE
                ApiTask done = new ApiTask(
                        taskId, sessionId, command,
                        ApiTaskStatus.DONE, running.startedAt(), Instant.now(),
                        resultData, null
                );
                tasks.put(taskId, done);
                eventBus.publish(GSimEvent.of(sessionId, taskId, "command_done", Map.of()));
            } else {
                // 标记 FAILED
                ApiTask failed = new ApiTask(
                        taskId, sessionId, command,
                        ApiTaskStatus.FAILED, running.startedAt(), Instant.now(),
                        resultData, result.message()
                );
                tasks.put(taskId, failed);
                eventBus.publish(GSimEvent.of(sessionId, taskId, "command_error",
                        Map.of("error", result.message())));
            }
        } catch (Exception e) {
            log.error("Task {} failed: {}", taskId, e.getMessage(), e);
            if (!isCancelled(taskId)) {
                ApiTask failed = new ApiTask(
                        taskId, sessionId, command,
                        ApiTaskStatus.FAILED, running.startedAt(), Instant.now(),
                        null, e.getMessage()
                );
                tasks.put(taskId, failed);
                eventBus.publish(GSimEvent.of(sessionId, taskId, "command_error",
                        Map.of("error", e.getMessage())));
            }
        } finally {
            // done 仅在此处发布一次
            if (!isCancelled(taskId)) {
                eventBus.publish(GSimEvent.of(sessionId, taskId, "done", Map.of()));
            }
            ApiTask finalTask = tasks.get(taskId);
            log.info("Task {} finished: {}", taskId, finalTask != null ? finalTask.status() : "unknown");
        }
    }

    /**
     * 获取任务数量。
     */
    public int taskCount() {
        return tasks.size();
    }
}
