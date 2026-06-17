package com.gsim.world;

import com.gsim.storage.DataPaths;
import com.gsim.util.JsonUtils;
import com.gsim.util.TimeProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 世界状态服务 — 管理世界状态和状态变更。
 */
public class WorldStateService {

    private final DataPaths dataPaths;
    private final TimeProvider timeProvider;

    public WorldStateService(DataPaths dataPaths, TimeProvider timeProvider) {
        this.dataPaths = dataPaths;
        this.timeProvider = timeProvider;
    }

    /**
     * 保存状态变更。
     */
    public void saveStateChanges(String campaignId, String turnId, List<StateChange> changes) {
        try {
            Path dir = dataPaths.turnDir(campaignId, turnId);
            Files.createDirectories(dir);
            String json = JsonUtils.toJson(changes);
            Files.writeString(dataPaths.stateChangesFile(campaignId, turnId), json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save state changes: " + e.getMessage(), e);
        }
    }

    /**
     * 加载状态变更。
     */
    public List<StateChange> loadStateChanges(String campaignId, String turnId) {
        Path file = dataPaths.stateChangesFile(campaignId, turnId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String json = Files.readString(file);
            StateChange[] arr = JsonUtils.fromJson(json, StateChange[].class);
            return arr != null ? List.of(arr) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }
}
