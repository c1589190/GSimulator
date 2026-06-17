package com.gsim.task;

import com.gsim.storage.DataPaths;
import com.gsim.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 任务日志服务 — 保存和加载 TaskLog JSON。
 */
public class TaskLogService {

    private static final Logger log = LoggerFactory.getLogger(TaskLogService.class);

    private final DataPaths dataPaths;

    public TaskLogService(DataPaths dataPaths) {
        this.dataPaths = dataPaths;
    }

    /**
     * 保存 TaskLog 为 JSON 文件。
     */
    public Path save(TaskLog taskLog) {
        Path file = dataPaths.taskLogFile(
                taskLog.campaignId(), taskLog.turnId(), taskLog.taskId());
        try {
            Files.createDirectories(file.getParent());
            String json = JsonUtils.toJson(taskLog);
            Files.writeString(file, json);
            log.info("TaskLog saved: {}", file);
            return file;
        } catch (IOException e) {
            log.error("Failed to save TaskLog: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 加载 TaskLog。
     */
    public TaskLog load(String campaignId, String turnId, String taskId) {
        Path file = dataPaths.taskLogFile(campaignId, turnId, taskId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file);
            return JsonUtils.fromJson(json, TaskLog.class);
        } catch (IOException e) {
            log.error("Failed to load TaskLog: {}", e.getMessage(), e);
            return null;
        }
    }
}
