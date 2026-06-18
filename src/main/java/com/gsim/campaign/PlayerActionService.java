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
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * PlayerAction 管理服务。
 * 线程安全：使用 ReentrantReadWriteLock 保护内部 actions 列表。
 */
public class PlayerActionService {

    private static final Logger log = LoggerFactory.getLogger(PlayerActionService.class);

    private final DataPaths dataPaths;
    private final TimeProvider timeProvider;
    private final List<PlayerAction> actions = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

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
        lock.writeLock().lock();
        try {
            actions.add(action);
            saveActionsLocked(campaignId, turnId);
        } finally {
            lock.writeLock().unlock();
        }
        return action;
    }

    /**
     * 获取当前所有行动（defensive copy）。
     */
    public List<PlayerAction> getActions() {
        lock.readLock().lock();
        try {
            return List.copyOf(actions);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取当前行动数量。
     */
    public int getActionCount() {
        lock.readLock().lock();
        try {
            return actions.size();
        } finally {
            lock.readLock().unlock();
        }
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
            lock.writeLock().lock();
            try {
                actions.clear();
                if (loaded != null) {
                    Collections.addAll(actions, loaded);
                }
            } finally {
                lock.writeLock().unlock();
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
        lock.writeLock().lock();
        try {
            actions.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 是否有未结算的行动。
     */
    public boolean hasActions() {
        lock.readLock().lock();
        try {
            return !actions.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void saveActions(String campaignId, String turnId) {
        lock.readLock().lock();
        try {
            saveActionsLocked(campaignId, turnId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 必须在已持有 writeLock 或 readLock 时调用（调用者负责加锁）。 */
    private void saveActionsLocked(String campaignId, String turnId) {
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
