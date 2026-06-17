package com.gsim.campaign;

import com.gsim.storage.DataPaths;
import com.gsim.util.IdGenerator;
import com.gsim.util.JsonUtils;
import com.gsim.util.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PlayerAction 管理服务。
 */
public class PlayerActionService {

    private static final Logger log = LoggerFactory.getLogger(PlayerActionService.class);

    private final DataPaths dataPaths;
    private final TimeProvider timeProvider;
    private final List<PlayerAction> actions = new ArrayList<>();

    public PlayerActionService(DataPaths dataPaths, TimeProvider timeProvider) {
        this.dataPaths = dataPaths;
        this.timeProvider = timeProvider;
    }

    /**
     * 添加玩家行动。
     */
    public PlayerAction addAction(String campaignId, String turnId, String playerName, String content) {
        String id = IdGenerator.playerActionId();
        PlayerAction action = PlayerAction.create(id, campaignId, turnId, playerName, content, timeProvider.now());
        actions.add(action);
        saveActions(campaignId, turnId);
        return action;
    }

    /**
     * 获取当前所有行动。
     */
    public List<PlayerAction> getActions() {
        return Collections.unmodifiableList(actions);
    }

    /**
     * 获取当前行动数量。
     */
    public int getActionCount() {
        return actions.size();
    }

    /**
     * 加载指定回合的行动。
     */
    public List<PlayerAction> loadActions(String campaignId, String turnId) {
        Path file = dataPaths.actionsFile(campaignId, turnId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String json = Files.readString(file);
            PlayerAction[] loaded = JsonUtils.fromJson(json, PlayerAction[].class);
            actions.clear();
            if (loaded != null) {
                Collections.addAll(actions, loaded);
            }
            return getActions();
        } catch (IOException e) {
            log.error("Failed to load actions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 清空当前行动（不删除磁盘文件，下次保存时覆盖）。
     */
    public void clearActions() {
        actions.clear();
    }

    /**
     * 是否有未结算的行动。
     */
    public boolean hasActions() {
        return !actions.isEmpty();
    }

    private void saveActions(String campaignId, String turnId) {
        try {
            Path dir = dataPaths.turnDir(campaignId, turnId);
            Files.createDirectories(dir);
            String json = JsonUtils.toJson(actions);
            Files.writeString(dataPaths.actionsFile(campaignId, turnId), json);
        } catch (IOException e) {
            log.error("Failed to save actions: {}", e.getMessage(), e);
        }
    }
}
