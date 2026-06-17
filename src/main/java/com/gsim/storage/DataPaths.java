package com.gsim.storage;

import com.gsim.app.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 管理所有数据目录的创建和路径解析。
 * 启动时自动创建必要目录。
 */
public class DataPaths {

    private final AppConfig config;

    public DataPaths(AppConfig config) {
        this.config = config;
    }

    /**
     * 初始化所有数据目录。
     */
    public void initialize() throws IOException {
        ensureDir(config.getDataDir());
        ensureDir(config.getImportDir());
        ensureDir(config.getImportDir().resolve("done"));
        ensureDir(config.getImportDir().resolve("failed"));
        ensureDir(config.getImportDir().resolve("web"));
        ensureDir(config.getOutputDir());
        ensureDir(config.getLogDir());
        ensureDir(config.getDataDir().resolve("campaigns"));
        ensureDir(config.getDataDir().resolve("pending-imports"));
        ensureDir(config.getDataDir().resolve("demo"));
    }

    /**
     * 获取 campaign 目录。
     */
    public Path campaignDir(String campaignId) {
        return config.getDataDir().resolve("campaigns").resolve(campaignId);
    }

    /**
     * 获取 campaign JSON 文件路径。
     */
    public Path campaignFile(String campaignId) {
        return campaignDir(campaignId).resolve("campaign.json");
    }

    /**
     * 获取 turns 目录。
     */
    public Path turnsDir(String campaignId) {
        return campaignDir(campaignId).resolve("turns");
    }

    /**
     * 获取 turn 目录。
     */
    public Path turnDir(String campaignId, String turnId) {
        return turnsDir(campaignId).resolve(turnId);
    }

    /**
     * 获取 turn 内 actions 文件。
     */
    public Path actionsFile(String campaignId, String turnId) {
        return turnDir(campaignId, turnId).resolve("actions.json");
    }

    /**
     * 获取 turn 内 timeline events 文件。
     */
    public Path timelineEventsFile(String campaignId, String turnId) {
        return turnDir(campaignId, turnId).resolve("timeline-events.json");
    }

    /**
     * 获取 turn 内 state changes 文件。
     */
    public Path stateChangesFile(String campaignId, String turnId) {
        return turnDir(campaignId, turnId).resolve("state-changes.json");
    }

    /**
     * 获取 turn 内 run result 文件。
     */
    public Path runResultFile(String campaignId, String turnId) {
        return turnDir(campaignId, turnId).resolve("run-result.json");
    }

    /**
     * 获取日志目录。
     */
    public Path logDir(String campaignId, String turnId) {
        return config.getLogDir().resolve(campaignId).resolve(turnId);
    }

    /**
     * 获取 task log 文件。
     */
    public Path taskLogFile(String campaignId, String turnId, String taskId) {
        return logDir(campaignId, turnId).resolve(taskId + ".json");
    }

    /**
     * 获取输出文件路径。
     */
    public Path outputFile(String campaignId, String turnId, String taskId) {
        return config.getOutputDir().resolve(campaignId).resolve(turnId).resolve(taskId + ".md");
    }

    /**
     * 获取 pending imports 目录。
     */
    public Path pendingImportsDir() {
        return config.getDataDir().resolve("pending-imports");
    }

    // ---- helpers ----

    private void ensureDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }
}
