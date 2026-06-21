package com.gsim.api;

import com.gsim.api.dto.CommandRequest;
import com.gsim.app.AppConfig;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.interaction.commands.StatusCommand;
import com.gsim.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tasks API 集成测试 — 启动 HTTP 服务器并验证任务端点。
 */
@DisplayName("Tasks API")
class TasksApiHandlerTest {

    private ApiManager apiManager;
    private ApplicationContext ctx;
    private EventBus eventBus;
    private TaskManager taskManager;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = AppConfig.forTesting();
        ctx = new ApplicationContext(config);
        ctx.initialize();
        ctx.getInteractionManager().registerCommand(new StatusCommand());

        eventBus = ctx.getEventBus();

        port = 18710 + (int) (Math.random() * 1000);
        ApiConfig apiConfig = new ApiConfig("127.0.0.1", port, true);
        apiManager = new ApiManager(apiConfig, ctx, eventBus);
        apiManager.start();

        // 使用 ApiManager 内置的 TaskManager，而非创建独立实例
        taskManager = apiManager.getTaskManager();
    }

    @AfterEach
    void tearDown() {
        if (apiManager != null) apiManager.stop();
        if (ctx != null) ctx.shutdown();
    }

    @Test
    @DisplayName("POST /api/tasks 应创建任务并返回 taskId")
    void shouldCreateTask() throws Exception {
        CommandRequest req = CommandRequest.of("default", "/status");
        String reqJson = JsonUtils.toJson(req);

        HttpURLConnection conn = post("/api/tasks", reqJson);
        assertEquals(201, conn.getResponseCode());

        String body = readBody(conn);
        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        assertEquals(true, result.get("success"));
        assertNotNull(result.get("data"));

        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertNotNull(data.get("taskId"));
        assertEquals("PENDING", data.get("status"));
    }

    @Test
    @DisplayName("POST /api/tasks 缺少 command 应返回 400")
    void shouldReturn400ForMissingCommand() throws Exception {
        HttpURLConnection conn = post("/api/tasks", "{\"sessionId\":\"default\"}");
        assertEquals(400, conn.getResponseCode());
    }

    @Test
    @DisplayName("GET /api/tasks 应返回任务列表")
    void shouldListTasks() throws Exception {
        // 创建并等待完成
        CommandRequest req = CommandRequest.of("default", "/status");
        String reqJson = JsonUtils.toJson(req);

        HttpURLConnection conn1 = post("/api/tasks", reqJson);
        Map<?, ?> createResult = JsonUtils.fromJson(readBody(conn1), Map.class);
        String taskId = (String) ((Map<?, ?>) createResult.get("data")).get("taskId");

        // 等待任务完成
        Thread.sleep(500);

        HttpURLConnection conn2 = get("/api/tasks");
        assertEquals(200, conn2.getResponseCode());

        String body = readBody(conn2);
        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        assertEquals(true, result.get("success"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertNotNull(data.get("tasks"));
    }

    @Test
    @DisplayName("GET /api/tasks/{taskId} 应返回任务状态")
    void shouldGetTaskStatus() throws Exception {
        ApiTask task = taskManager.createCommandTask("default", "/status");
        taskManager.waitForCompletion(task.taskId(), 10000);

        HttpURLConnection conn = get("/api/tasks/" + task.taskId());
        assertEquals(200, conn.getResponseCode());

        String body = readBody(conn);
        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        assertEquals(true, result.get("success"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertNotNull(data.get("task"));
    }

    @Test
    @DisplayName("GET /api/tasks/{taskId} 不存在应返回 404")
    void shouldReturn404ForUnknownTask() throws Exception {
        HttpURLConnection conn = get("/api/tasks/nonexistent");
        assertEquals(404, conn.getResponseCode());
    }

    @Test
    @DisplayName("GET /api/tasks/{taskId}/events 应返回 SSE 流")
    void shouldReturnSseStreamForTask() throws Exception {
        ApiTask task = taskManager.reserveTask("default", "/status");
        String taskId = task.taskId();

        // 在另一个线程中读取 SSE
        Thread readerThread = new Thread(() -> {
            try {
                HttpURLConnection conn = get("/api/tasks/" + taskId + "/events");
                // responseCode 检查在另一个线程，这里只验证可以连接
            } catch (Exception ignored) {}
        });

        // 执行任务
        taskManager.executePendingTask(taskId);
        taskManager.waitForCompletion(taskId, 10000);

        // 验证 SSE 内容
        HttpURLConnection conn = get("/api/tasks/" + taskId + "/events");
        assertEquals(200, conn.getResponseCode());
        assertEquals("text/event-stream; charset=utf-8",
                conn.getHeaderField("Content-Type"));

        String body = readBody(conn);
        assertTrue(body.contains("event: "));
        assertTrue(body.contains("task_status") || body.contains("command_started") || body.contains("done"));
    }

    @Test
    @DisplayName("POST /api/tasks/{taskId}/cancel 应取消 PENDING 任务")
    void shouldCancelTask() throws Exception {
        // 使用 reserveTask 创建 PENDING 但不执行的任务，确保可以取消
        ApiTask task = taskManager.reserveTask("default", "/status");

        HttpURLConnection conn = post("/api/tasks/" + task.taskId() + "/cancel", "");
        assertEquals(200, conn.getResponseCode());

        String body = readBody(conn);
        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        assertEquals(true, result.get("success"));
    }

    @Test
    @DisplayName("POST /api/tasks autoStart=false 应返回 PENDING 状态")
    void shouldCreatePendingTaskWithAutoStartFalse() throws Exception {
        String reqJson = "{\"sessionId\":\"default\",\"command\":\"/status\",\"autoStart\":false}";

        HttpURLConnection conn = post("/api/tasks", reqJson);
        assertEquals(201, conn.getResponseCode());

        String body = readBody(conn);
        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        assertEquals(true, result.get("success"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals("PENDING", data.get("status"));
        assertEquals(false, data.get("autoStart"));
        assertNotNull(data.get("taskId"));
    }

    @Test
    @DisplayName("POST /api/tasks/{taskId}/start 应启动 PENDING 任务")
    void shouldStartPendingTask() throws Exception {
        // 创建 PENDING 任务
        ApiTask task = taskManager.reserveTask("default", "/status");

        // 启动
        HttpURLConnection conn = post("/api/tasks/" + task.taskId() + "/start", "");
        assertEquals(200, conn.getResponseCode());

        String body = readBody(conn);
        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        assertEquals(true, result.get("success"));

        // 等待完成
        taskManager.waitForCompletion(task.taskId(), 10000);
        ApiTask finalTask = taskManager.getTask(task.taskId());
        assertNotNull(finalTask);
        assertTrue(finalTask.status() == ApiTaskStatus.DONE
                || finalTask.status() == ApiTaskStatus.FAILED);
    }

    @Test
    @DisplayName("POST /api/tasks/{taskId}/start 对非 PENDING 任务应返回 400")
    void shouldReturn400ForStartNonPendingTask() throws Exception {
        // 创建并自动启动
        ApiTask task = taskManager.createCommandTask("default", "/status");
        taskManager.waitForCompletion(task.taskId(), 10000);

        // 尝试再次启动
        HttpURLConnection conn = post("/api/tasks/" + task.taskId() + "/start", "");
        assertEquals(400, conn.getResponseCode());
    }

    @Test
    @DisplayName("cancel 后任务状态应保持 CANCELLED")
    void shouldKeepCancelledStatus() throws Exception {
        // 使用 reserveTask 创建 PENDING 任务
        ApiTask task = taskManager.reserveTask("default", "/status");

        // 取消
        taskManager.cancelTask(task.taskId());

        // 验证状态
        ApiTask cancelled = taskManager.getTask(task.taskId());
        assertNotNull(cancelled);
        assertEquals(ApiTaskStatus.CANCELLED, cancelled.status());
    }

    @Test
    @DisplayName("SSE 流中 done 不应重复")
    void shouldNotDuplicateDoneEvent() throws Exception {
        ApiTask task = taskManager.reserveTask("default", "/status");
        String taskId = task.taskId();

        // 先订阅 SSE
        HttpURLConnection conn = get("/api/tasks/" + taskId + "/events");
        assertEquals(200, conn.getResponseCode());
        assertEquals("text/event-stream; charset=utf-8",
                conn.getHeaderField("Content-Type"));

        // 启动任务
        taskManager.executePendingTask(taskId);
        taskManager.waitForCompletion(taskId, 10000);

        // 读取 SSE 内容
        String body = readBody(conn);

        // 统计 done 事件出现次数
        int doneCount = 0;
        for (String line : body.split("\n")) {
            if (line.trim().equals("event: done")) {
                doneCount++;
            }
        }
        // done 应只出现一次（由 TaskManager finally 块发布）
        assertTrue(doneCount <= 1, "Expected at most 1 done event, got " + doneCount);
    }

    // ---- helpers ----

    private HttpURLConnection get(String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        return conn;
    }

    private HttpURLConnection post(String path, String jsonBody) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        if (jsonBody != null && !jsonBody.isEmpty()) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }
        return conn;
    }

    private String readBody(HttpURLConnection conn) throws Exception {
        try (var in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            try (var errIn = conn.getErrorStream()) {
                if (errIn != null) {
                    return new String(errIn.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return "";
        }
    }
}
