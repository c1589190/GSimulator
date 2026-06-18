package com.gsim.context.summary;

import com.gsim.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * NodeSummary JSONL 持久化存储。
 *
 * <p>文件路径: data/worlds/{world}/context/node_summaries.jsonl
 */
public class NodeSummaryStore {

    private static final Logger log = LoggerFactory.getLogger(NodeSummaryStore.class);

    private final Path summariesFile;

    public NodeSummaryStore(Path worldDir) {
        Path contextDir = worldDir.resolve("context");
        this.summariesFile = contextDir.resolve("node_summaries.jsonl");
    }

    /**
     * 保存 NodeSummary。
     */
    public void save(NodeSummary summary) {
        try {
            Files.createDirectories(summariesFile.getParent());
            // 全量重写以避免重复
            Map<String, NodeSummary> all = loadAllAsMap();
            all.put(summary.nodeId(), summary);
            rewriteAll(new ArrayList<>(all.values()));
        } catch (IOException e) {
            log.error("Failed to save NodeSummary {}: {}", summary.nodeId(), e.getMessage());
        }
    }

    /**
     * 按 nodeId 查找。
     */
    public Optional<NodeSummary> findByNodeId(String nodeId) {
        return loadAll().stream()
                .filter(s -> s.nodeId().equals(nodeId))
                .findFirst();
    }

    /**
     * 加载所有 summaries。
     */
    public List<NodeSummary> loadAll() {
        List<NodeSummary> summaries = new ArrayList<>();
        if (!Files.exists(summariesFile)) return summaries;

        try {
            List<String> lines = Files.readAllLines(summariesFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    summaries.add(JsonUtils.fromJson(line, NodeSummary.class));
                } catch (Exception e) {
                    log.warn("Failed to parse NodeSummary: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to read node summaries: {}", e.getMessage());
        }
        return summaries;
    }

    private Map<String, NodeSummary> loadAllAsMap() {
        Map<String, NodeSummary> map = new LinkedHashMap<>();
        for (NodeSummary s : loadAll()) {
            map.put(s.nodeId(), s);
        }
        return map;
    }

    private void rewriteAll(List<NodeSummary> summaries) throws IOException {
        Files.createDirectories(summariesFile.getParent());
        StringBuilder sb = new StringBuilder();
        for (NodeSummary s : summaries) {
            sb.append(JsonUtils.toJsonCompact(s)).append("\n");
        }
        Files.writeString(summariesFile, sb.toString(), StandardCharsets.UTF_8);
    }

    public Path getSummariesFile() {
        return summariesFile;
    }
}
