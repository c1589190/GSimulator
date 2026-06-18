package com.gsim.api;

import com.gsim.app.AppConfig;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.interaction.InteractionManager;
import com.gsim.interaction.commands.StatusCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskManager 测试 — 不真实调用 LLM，不真实访问外网。
 */
@DisplayName("TaskManager")
class TaskManagerTest {

    private ApplicationContext ctx;
    private SessionManager sessionManager;
    private TaskManager taskManager;
    private EventBus eventBus;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = AppConfig.forTesting();
        ctx = new ApplicationContext(config);
        ctx.initialize();
        ctx.getInteractionManager().registerCommand(new StatusCommand());

        sessionManager = new SessionManager(ctx);
        eventBus = ctx.getEventBus();
        taskManager = new TaskManager(ctx, sessionManager, eventBus);
    }

    @AfterEach
    void tearDown() {
        ctx.shutdown();
    }

    @Test
    @DisplayName("createCommandTask 应创建 PENDING 任务")
    void shouldCreatePendingTask() {
        var task = taskManager.createCommandTask("default", "/status");
        assertNotNull(task);
        assertEquals("default", task.sessionId());
        assertEquals("/status", task.command());
        assertNotNull(task.taskId());
    }

    @Test
    @DisplayName("任务应从 PENDING 流转到 DONE")
    void taskShouldTransitionToDone() throws Exception {
        List<String> receivedEvents = new ArrayList<>();
        var sink = new com.gsim.event.EventSink() {
            @Override
            public void accept(com.gsim.event.GSimEvent event) {
                receivedEvents.add(event.type());
            }
        };
        eventBus.subscribe(sink);

        var task = taskManager.createCommandTask("default", "/status");
        taskManager.waitForCompletion(task.taskId(), 10000);

        var finalTask = taskManager.getTask(task.taskId());
        assertNotNull(finalTask);
        assertEquals(ApiTaskStatus.DONE, finalTask.status());
        assertNotNull(finalTask.finishedAt());

        // 验证事件类型
        assertTrue(receivedEvents.contains("command_started"), "should have command_started");
        assertTrue(receivedEvents.contains("command_done"), "should have command_done");
        assertTrue(receivedEvents.contains("done"), "should have done");
    }

    @Test
    @DisplayName("getTask 不存在的任务应返回 null")
    void getTaskShouldReturnNullForUnknown() {
        assertNull(taskManager.getTask("nonexistent"));
    }

    @Test
    @DisplayName("listTasks 应列出所有任务")
    void listTasksShouldReturnAll() throws Exception {
        var t1 = taskManager.createCommandTask("default", "/status");
        var t2 = taskManager.createCommandTask("default", "/status");

        taskManager.waitForCompletion(t1.taskId(), 10000);
        taskManager.waitForCompletion(t2.taskId(), 10000);

        var tasks = taskManager.listTasks();
        assertTrue(tasks.size() >= 2);
    }

    @Test
    @DisplayName("cancelTask 应标记 CANCELLED")
    void shouldCancelTask() {
        // 使用 reserveTask 确保任务仍在 PENDING，不会被虚拟线程覆盖
        var task = taskManager.reserveTask("default", "/status");
        boolean cancelled = taskManager.cancelTask(task.taskId());
        assertTrue(cancelled);

        var finalTask = taskManager.getTask(task.taskId());
        assertEquals(ApiTaskStatus.CANCELLED, finalTask.status());
    }

    @Test
    @DisplayName("reserveTask 应创建 PENDING 但不执行")
    void reserveTaskShouldNotExecute() throws Exception {
        var task = taskManager.reserveTask("default", "/status");
        assertEquals(ApiTaskStatus.PENDING, task.status());

        // 等待一下确认没有自动执行
        Thread.sleep(200);
        var stillPending = taskManager.getTask(task.taskId());
        assertEquals(ApiTaskStatus.PENDING, stillPending.status());
        assertNull(stillPending.finishedAt());
    }

    @Test
    @DisplayName("executePendingTask 应开始执行预留任务")
    void executePendingTaskShouldStartExecution() throws Exception {
        var task = taskManager.reserveTask("default", "/status");
        assertEquals(ApiTaskStatus.PENDING, task.status());

        taskManager.executePendingTask(task.taskId());
        taskManager.waitForCompletion(task.taskId(), 10000);

        var finalTask = taskManager.getTask(task.taskId());
        assertEquals(ApiTaskStatus.DONE, finalTask.status());
    }

    @Test
    @DisplayName("失败命令应标记 FAILED")
    void invalidCommandShouldBeFailed() throws Exception {
        var task = taskManager.createCommandTask("default", "/invalid_cmd_xyz");
        taskManager.waitForCompletion(task.taskId(), 10000);

        var finalTask = taskManager.getTask(task.taskId());
        assertEquals(ApiTaskStatus.FAILED, finalTask.status());
        assertNotNull(finalTask.error());
    }
}
