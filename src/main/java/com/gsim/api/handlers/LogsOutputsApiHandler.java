package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.app.ApplicationContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Logs / Outputs API handler。
 *
 * <p>路由：
 * <ul>
 *   <li>GET /api/logs          — 列出所有日志</li>
 *   <li>GET /api/logs/{taskId} — 获取指定日志内容</li>
 *   <li>GET /api/outputs          — 列出所有输出</li>
 *   <li>GET /api/outputs/{taskId} — 获取指定输出内容</li>
 * </ul>
 */
public class LogsOutputsApiHandler implements HttpHandler {

    private static final String LOGS_PREFIX = "/api/logs";
    private static final String OUTPUTS_PREFIX = "/api/outputs";

    private final ApplicationContext ctx;

    public LogsOutputsApiHandler(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        if (!"GET".equals(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
            return;
        }

        try {
            if (path.startsWith(LOGS_PREFIX)) {
                handleLogs(exchange);
            } else if (path.startsWith(OUTPUTS_PREFIX)) {
                handleOutputs(exchange);
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown endpoint");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        String[] segs = BaseApiHandler.pathSegments(exchange, LOGS_PREFIX);
        Path logDir = ctx.getConfig().getLogDir();

        if (segs.length == 0) {
            // GET /api/logs — 列出所有日志文件
            List<Map<String, Object>> files = listFiles(logDir);
            BaseApiHandler.sendOk(exchange, "Logs retrieved", Map.of("files", files, "logDir", logDir.toString()));
        } else {
            // GET /api/logs/{taskId}
            String rawTaskId = segs[0];
            if (!isValidTaskId(rawTaskId)) {
                BaseApiHandler.sendError(exchange, 400, "Invalid taskId: " + rawTaskId);
                return;
            }
            Path logFile = logDir.resolve(rawTaskId + ".json");
            if (Files.exists(logFile)) {
                String content = Files.readString(logFile);
                BaseApiHandler.sendOk(exchange, "Log found",
                        Map.of("taskId", rawTaskId, "content", content, "path", logFile.toString()));
            } else {
                BaseApiHandler.sendNotFound(exchange, "Log not found: " + rawTaskId);
            }
        }
    }

    private void handleOutputs(HttpExchange exchange) throws IOException {
        String[] segs = BaseApiHandler.pathSegments(exchange, OUTPUTS_PREFIX);
        Path outputDir = ctx.getConfig().getOutputDir();

        if (segs.length == 0) {
            // GET /api/outputs — 列出所有输出文件
            List<Map<String, Object>> files = listFiles(outputDir);
            BaseApiHandler.sendOk(exchange, "Outputs retrieved", Map.of("files", files, "outputDir", outputDir.toString()));
        } else {
            // GET /api/outputs/{taskId}
            String rawTaskId = segs[0];
            if (!isValidTaskId(rawTaskId)) {
                BaseApiHandler.sendError(exchange, 400, "Invalid taskId: " + rawTaskId);
                return;
            }
            // 尝试 .md 或 .json 扩展名
            Path outputFile = outputDir.resolve(rawTaskId + ".md");
            if (!Files.exists(outputFile)) {
                outputFile = outputDir.resolve(rawTaskId + ".json");
            }
            if (Files.exists(outputFile)) {
                String content = Files.readString(outputFile);
                BaseApiHandler.sendOk(exchange, "Output found",
                        Map.of("taskId", rawTaskId, "content", content, "path", outputFile.toString()));
            } else {
                BaseApiHandler.sendNotFound(exchange, "Output not found: " + rawTaskId);
            }
        }
    }

    /**
     * 校验 taskId 格式：只允许字母、数字、连字符和下划线。
     * 拒绝含路径分隔符、..、空格的输入，防止路径穿越。
     */
    private static boolean isValidTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) return false;
        if (taskId.contains("/") || taskId.contains("\\")) return false;
        if (taskId.contains("..")) return false;
        return taskId.matches("[a-zA-Z0-9_-]+");
    }

    private List<Map<String, Object>> listFiles(Path dir) {
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("name", p.getFileName().toString());
                            info.put("path", p.toString());
                            try {
                                info.put("size", Files.size(p));
                                info.put("modifiedAt", Files.getLastModifiedTime(p).toString());
                            } catch (IOException ignored) {
                            }
                            files.add(info);
                        });
            } catch (IOException ignored) {
            }
        }
        return files;
    }
}
