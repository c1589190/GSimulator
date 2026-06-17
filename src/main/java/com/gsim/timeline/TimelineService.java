package com.gsim.timeline;

import com.gsim.storage.DataPaths;
import com.gsim.util.JsonUtils;
import com.gsim.util.TimeProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 时间线服务 — 管理 TimelineEvent 的持久化。
 */
public class TimelineService {

    private final DataPaths dataPaths;
    private final TimeProvider timeProvider;

    public TimelineService(DataPaths dataPaths, TimeProvider timeProvider) {
        this.dataPaths = dataPaths;
        this.timeProvider = timeProvider;
    }

    /**
     * 保存时间线事件。
     */
    public void saveEvents(String campaignId, String turnId, List<TimelineEvent> events) {
        try {
            Path dir = dataPaths.turnDir(campaignId, turnId);
            Files.createDirectories(dir);
            String json = JsonUtils.toJson(events);
            Files.writeString(dataPaths.timelineEventsFile(campaignId, turnId), json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save timeline events: " + e.getMessage(), e);
        }
    }

    /**
     * 加载时间线事件。
     */
    public List<TimelineEvent> loadEvents(String campaignId, String turnId) {
        Path file = dataPaths.timelineEventsFile(campaignId, turnId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String json = Files.readString(file);
            TimelineEvent[] arr = JsonUtils.fromJson(json, TimelineEvent[].class);
            return arr != null ? List.of(arr) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }
}
