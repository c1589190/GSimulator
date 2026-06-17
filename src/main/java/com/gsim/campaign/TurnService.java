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
import java.util.Optional;

/**
 * Turn 管理服务。
 */
public class TurnService {

    private static final Logger log = LoggerFactory.getLogger(TurnService.class);

    private final DataPaths dataPaths;
    private final TimeProvider timeProvider;
    private volatile Turn currentTurn;

    public TurnService(DataPaths dataPaths, TimeProvider timeProvider) {
        this.dataPaths = dataPaths;
        this.timeProvider = timeProvider;
    }

    /**
     * 获取当前回合。
     */
    public Optional<Turn> getCurrentTurn() {
        return Optional.ofNullable(currentTurn);
    }

    /**
     * 获取或创建第一个回合。
     */
    public Turn getOrCreateFirst(String campaignId) {
        if (currentTurn != null) {
            return currentTurn;
        }
        return createTurn(campaignId, 1);
    }

    /**
     * 创建下一个回合。
     */
    public Turn createNext(String campaignId) {
        int nextIndex = (currentTurn != null) ? currentTurn.index() + 1 : 1;
        return createTurn(campaignId, nextIndex);
    }

    /**
     * 加载指定回合。
     */
    public Optional<Turn> load(String campaignId, String turnId) {
        Path dir = dataPaths.turnDir(campaignId, turnId);
        if (!Files.exists(dir)) {
            return Optional.empty();
        }
        // Turn metadata is stored in actions.json alongside actions
        // For now, we reconstruct from the turnId
        int index = extractIndex(turnId);
        Turn t = Turn.create(campaignId, turnId, index, timeProvider.now());
        currentTurn = t;
        return Optional.of(t);
    }

    /**
     * 标记当前回合为已结算。
     */
    public void resolveCurrent() {
        if (currentTurn == null || !currentTurn.isOpen()) {
            return;
        }
        currentTurn = currentTurn.resolved(timeProvider.now());
        saveToDisk(currentTurn);
        log.info("Resolved turn: {}", currentTurn.turnId());
    }

    /**
     * 保存当前回合。
     */
    public void save() {
        if (currentTurn == null) {
            return;
        }
        saveToDisk(currentTurn);
    }

    /**
     * 清空回合状态（不删除磁盘文件）。
     */
    public void clearCurrent() {
        currentTurn = null;
    }

    private Turn createTurn(String campaignId, int index) {
        String turnId = IdGenerator.turnId(index);
        Turn turn = Turn.create(campaignId, turnId, index, timeProvider.now());
        currentTurn = turn;
        saveToDisk(turn);
        log.info("Created turn: {}", turnId);
        return turn;
    }

    private void saveToDisk(Turn turn) {
        try {
            Path dir = dataPaths.turnDir(turn.campaignId(), turn.turnId());
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create turn directory: {}", e.getMessage(), e);
        }
    }

    private int extractIndex(String turnId) {
        try {
            return Integer.parseInt(turnId.replace("turn-", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
