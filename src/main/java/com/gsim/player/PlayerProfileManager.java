package com.gsim.player;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

/**
 * PlayerProfileManager — 玩家档案管理核心。
 * 读写 players.md 玩家档案文件。
 */
public class PlayerProfileManager {

    private static final Logger log = LoggerFactory.getLogger(PlayerProfileManager.class);

    private final Path playersFile;

    public PlayerProfileManager(Path playersFile) {
        this.playersFile = playersFile;
    }

    private void ensureFile() {
        if (!Files.exists(playersFile)) {
            try {
                Files.createDirectories(playersFile.getParent());
                Files.writeString(playersFile, "# 玩家档案\n\n", StandardOpenOption.CREATE);
            } catch (IOException e) {
                throw new RuntimeException("Cannot create players file: " + playersFile, e);
            }
        }
    }

    private String readRaw() {
        ensureFile();
        try {
            return Files.readString(playersFile);
        } catch (IOException e) {
            log.warn("Failed to read players file: {}", e.getMessage());
            return "";
        }
    }

    private void writeRaw(String content) {
        try {
            Files.writeString(playersFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Cannot write players file: " + playersFile, e);
        }
    }

    /** 列出所有玩家。 */
    public List<PlayerProfile> listPlayers() {
        String raw = readRaw();
        return PlayerProfileParser.parse(raw);
    }

    /** 按名获取玩家。 */
    public Optional<PlayerProfile> getPlayer(String name) {
        return listPlayers().stream()
                .filter(p -> p.name().equals(name))
                .findFirst();
    }

    /** 玩家是否存在。 */
    public boolean exists(String name) {
        return getPlayer(name).isPresent();
    }

    /** 添加新玩家档案（不检查重复）。 */
    public PlayerProfileUpdate addPlayer(PlayerProfile profile) {
        String raw = readRaw();
        String newSection = profile.toMarkdown();

        String updated;
        if (raw.isBlank()) {
            updated = "# 玩家档案\n\n" + newSection + "\n";
        } else {
            updated = raw.stripTrailing() + "\n\n" + newSection + "\n";
        }

        writeRaw(updated);
        log.info("Added player profile: {}", profile.name());
        return PlayerProfileUpdate.created(profile.name(), "all", newSection);
    }

    /** 更新玩家字段。如果玩家不存在，自动创建。 */
    public PlayerProfileUpdate updatePlayerField(String name, String field, String content) {
        Optional<PlayerProfile> existing = getPlayer(name);
        if (existing.isPresent()) {
            PlayerProfile updated = existing.get().withField(field, content);
            replaceProfile(updated);
            log.info("Updated player '{}' field '{}'", name, field);
            return PlayerProfileUpdate.updated(name, field, content);
        } else {
            PlayerProfile newProfile = PlayerProfile.createTemplate(name).withField(field, content);
            addPlayer(newProfile);
            log.info("Created player '{}' with field '{}' = '{}'", name, field, content);
            return PlayerProfileUpdate.created(name, field, content);
        }
    }

    /** 追加备注。 */
    public PlayerProfileUpdate appendPlayerNote(String name, String note) {
        Optional<PlayerProfile> existing = getPlayer(name);
        if (existing.isPresent()) {
            PlayerProfile updated = existing.get().withAppendedNote(note);
            replaceProfile(updated);
            log.info("Appended note to player '{}'", name);
            return PlayerProfileUpdate.updated(name, "notes", note);
        } else {
            PlayerProfile newProfile = PlayerProfile.createTemplate(name).withAppendedNote(note);
            addPlayer(newProfile);
            log.info("Created player '{}' with note", name);
            return PlayerProfileUpdate.created(name, "notes", note);
        }
    }

    /** 删除玩家档案。返回被删除的档案或 null。 */
    public Optional<PlayerProfile> removePlayer(String name) {
        Optional<PlayerProfile> target = getPlayer(name);
        if (target.isEmpty()) return Optional.empty();

        List<PlayerProfile> all = listPlayers();
        StringBuilder sb = new StringBuilder();
        String raw = readRaw();
        int firstH2 = raw.indexOf("\n## ");
        if (firstH2 >= 0) {
            String header = raw.substring(0, firstH2).trim();
            sb.append(header).append("\n\n");
        } else {
            sb.append("# 玩家档案\n\n");
        }

        boolean found = false;
        for (PlayerProfile p : all) {
            if (p.name().equals(name)) {
                found = true;
                continue;
            }
            sb.append(p.toMarkdown()).append("\n");
        }

        if (!found) return Optional.empty();

        writeRaw(sb.toString());
        log.info("Removed player profile: {}", name);
        return target;
    }

    /** 读取 players.md 原文。 */
    public String readRawPlayersMarkdown() {
        return readRaw();
    }

    /** 写入 players.md 原文。 */
    public void writeRawPlayersMarkdown(String markdown) {
        writeRaw(markdown);
    }

    /** 渲染新玩家模板（供 /players template 使用）。 */
    public String renderTemplateForNewPlayer(String name) {
        return PlayerProfile.createTemplate(name).toMarkdown();
    }

    /** 获取 players.md 路径。 */
    public Path getPlayersPath() {
        return playersFile;
    }

    // ---- private helpers ----

    /** 替换一个玩家档案段。 */
    private void replaceProfile(PlayerProfile updated) {
        List<PlayerProfile> all = listPlayers();
        String raw = readRaw();
        int firstH2 = raw.indexOf("\n## ");

        StringBuilder sb = new StringBuilder();
        if (firstH2 >= 0) {
            sb.append(raw, 0, firstH2).append("\n\n");
        } else {
            sb.append("# 玩家档案\n\n");
        }

        for (PlayerProfile p : all) {
            if (p.name().equals(updated.name())) {
                sb.append(updated.toMarkdown()).append("\n");
            } else {
                sb.append(p.toMarkdown()).append("\n");
            }
        }

        writeRaw(sb.toString());
    }
}
