package com.gsim.context.memory;

import com.gsim.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PinnedConstraint JSONL 持久化存储。
 *
 * <p>文件路径: data/worlds/{world}/context/pins.jsonl
 */
public class PinnedConstraintStore {

    private static final Logger log = LoggerFactory.getLogger(PinnedConstraintStore.class);

    private final Path pinsFile;

    public PinnedConstraintStore(Path worldDir) {
        Path contextDir = worldDir.resolve("context");
        this.pinsFile = contextDir.resolve("pins.jsonl");
    }

    /**
     * 添加一个 pin。
     */
    public void add(PinnedConstraint pin) {
        try {
            Files.createDirectories(pinsFile.getParent());
            String line = JsonUtils.toJsonCompact(pin) + "\n";
            Files.writeString(pinsFile, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to save pin {}: {}", pin.id(), e.getMessage());
        }
    }

    /**
     * 按 branchId 查找所有 pins。
     */
    public List<PinnedConstraint> findByBranch(String branchId) {
        return loadAll().stream()
                .filter(p -> p.branchId().equals(branchId))
                .toList();
    }

    /**
     * 查找与给定分支集合相关的 pins（用于父链）。
     */
    public List<PinnedConstraint> findByBranches(Set<String> branchIds) {
        return loadAll().stream()
                .filter(p -> branchIds.contains(p.branchId()))
                .toList();
    }

    /**
     * 加载所有 pins。
     */
    public List<PinnedConstraint> loadAll() {
        List<PinnedConstraint> pins = new ArrayList<>();
        if (!Files.exists(pinsFile)) return pins;

        try {
            List<String> lines = Files.readAllLines(pinsFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    pins.add(JsonUtils.fromJson(line, PinnedConstraint.class));
                } catch (Exception e) {
                    log.warn("Failed to parse pin: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to read pins: {}", e.getMessage());
        }
        return pins;
    }

    /**
     * 按 ID 删除 pin。
     */
    public boolean remove(String pinId) {
        List<PinnedConstraint> all = loadAll();
        boolean removed = all.removeIf(p -> p.id().equals(pinId));
        if (removed) {
            rewriteAll(all);
        }
        return removed;
    }

    private void rewriteAll(List<PinnedConstraint> pins) {
        try {
            Files.createDirectories(pinsFile.getParent());
            StringBuilder sb = new StringBuilder();
            for (PinnedConstraint p : pins) {
                sb.append(JsonUtils.toJsonCompact(p)).append("\n");
            }
            Files.writeString(pinsFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to rewrite pins: {}", e.getMessage());
        }
    }

    public Path getPinsFile() {
        return pinsFile;
    }
}
