package com.gsim.campaign;

import com.gsim.storage.DataPaths;
import com.gsim.util.JsonUtils;
import com.gsim.util.TimeProvider;
import com.gsim.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Campaign 管理服务。
 */
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    private final DataPaths dataPaths;
    private final TimeProvider timeProvider;
    private volatile Campaign currentCampaign;

    public CampaignService(DataPaths dataPaths, TimeProvider timeProvider) {
        this.dataPaths = dataPaths;
        this.timeProvider = timeProvider;
    }

    /**
     * 获取当前内存中的 campaign。
     */
    public Optional<Campaign> getCurrentCampaign() {
        return Optional.ofNullable(currentCampaign);
    }

    /**
     * 获取或创建默认 campaign。
     */
    public Campaign getOrCreateDefault() {
        if (currentCampaign != null) {
            return currentCampaign;
        }
        String id = IdGenerator.campaignId();
        currentCampaign = Campaign.createDefault(id, timeProvider.now());
        saveToDisk(currentCampaign);
        log.info("Created default campaign: {}", id);
        return currentCampaign;
    }

    /**
     * 创建一个指定名称的 campaign。
     *
     * @param name campaign 名称
     * @return 新创建的 campaign
     */
    public Campaign createNamed(String name) {
        String id = IdGenerator.campaignId();
        Campaign c = new Campaign(id, name, timeProvider.now(), null, List.of());
        currentCampaign = c;
        saveToDisk(c);
        log.info("Created campaign: {} ({})", id, name);
        return c;
    }

    /**
     * 列出所有已保存的 campaign。
     */
    public List<Campaign> listAll() {
        List<Campaign> result = new ArrayList<>();
        Path campaignsDir = dataPaths.campaignDir("").getParent(); // data/campaigns/
        if (campaignsDir == null || !Files.isDirectory(campaignsDir)) {
            return result;
        }
        try (Stream<Path> dirs = Files.list(campaignsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path file = dir.resolve("campaign.json");
                if (Files.exists(file)) {
                    try {
                        String json = Files.readString(file);
                        Campaign c = JsonUtils.fromJson(json, Campaign.class);
                        result.add(c);
                    } catch (IOException e) {
                        log.warn("Failed to read campaign from {}: {}", file, e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list campaigns: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 加载指定 campaign。
     */
    public Optional<Campaign> load(String campaignId) {
        Path file = dataPaths.campaignFile(campaignId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file);
            Campaign c = JsonUtils.fromJson(json, Campaign.class);
            currentCampaign = c;
            log.info("Loaded campaign: {}", campaignId);
            return Optional.of(c);
        } catch (IOException e) {
            log.error("Failed to load campaign {}: {}", campaignId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 保存当前 campaign 到磁盘。
     */
    public void save() {
        if (currentCampaign == null) {
            return;
        }
        saveToDisk(currentCampaign);
    }

    /**
     * 设置当前回合。
     */
    public void setCurrentTurnId(String turnId) {
        if (currentCampaign == null) {
            return;
        }
        currentCampaign = currentCampaign.withCurrentTurnId(turnId);
        saveToDisk(currentCampaign);
    }

    /**
     * 追加 turn ID。
     */
    public void addTurnId(String turnId) {
        if (currentCampaign == null) {
            return;
        }
        currentCampaign = currentCampaign.withAddedTurnId(turnId);
        saveToDisk(currentCampaign);
    }

    private void saveToDisk(Campaign campaign) {
        try {
            Path dir = dataPaths.campaignDir(campaign.campaignId());
            Files.createDirectories(dir);
            String json = JsonUtils.toJson(campaign);
            Files.writeString(dataPaths.campaignFile(campaign.campaignId()), json);
        } catch (IOException e) {
            log.error("Failed to save campaign: {}", e.getMessage(), e);
        }
    }
}
