package com.gsim.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.llm.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Skill 嵌入向量索引 — 管理 skills/.embdb/index.json。
 *
 * <p>每个条目含：id, name, summary, vector (float[]), updatedAt。
 */
public class SkillIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillIndex.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path indexFile;

    public SkillIndex(Path skillsDir) {
        this.indexFile = skillsDir.resolve(".embdb").resolve("index.json");
    }

    /** 确保 .embdb 目录存在。 */
    public void ensureDir() throws IOException {
        Path dir = indexFile.getParent();
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
    }

    /** 加载所有已索引条目。 */
    @SuppressWarnings("unchecked")
    public List<SkillEntry> loadAll() {
        if (!Files.isRegularFile(indexFile)) return List.of();
        try {
            Map<String, Object> root = MAPPER.readValue(indexFile.toFile(), Map.class);
            List<Map<String, Object>> skills = (List<Map<String, Object>>) root.get("skills");
            if (skills == null) return List.of();

            List<SkillEntry> entries = new ArrayList<>();
            for (Map<String, Object> s : skills) {
                String id = (String) s.get("id");
                String name = (String) s.get("name");
                String summary = (String) s.get("summary");
                long updatedAt = s.containsKey("updatedAt")
                        ? ((Number) s.get("updatedAt")).longValue() : 0;

                float[] vector = null;
                Object vObj = s.get("vector");
                if (vObj instanceof List<?> vList) {
                    vector = new float[vList.size()];
                    for (int i = 0; i < vList.size(); i++) {
                        vector[i] = ((Number) vList.get(i)).floatValue();
                    }
                }

                entries.add(new SkillEntry(id, name, summary, vector, updatedAt));
            }
            return entries;
        } catch (IOException e) {
            log.warn("[SkillIndex] Failed to load index: {}", e.getMessage());
            return List.of();
        }
    }

    /** 添加或更新索引条目。 */
    public void upsert(String id, String name, String summary, float[] vector) {
        List<SkillEntry> entries = new ArrayList<>(loadAll());
        entries.removeIf(e -> e.id().equals(id));
        entries.add(new SkillEntry(id, name, summary, vector, System.currentTimeMillis()));
        saveAll(entries);
    }

    /** 删除索引条目。 */
    public void remove(String id) {
        List<SkillEntry> entries = new ArrayList<>(loadAll());
        entries.removeIf(e -> e.id().equals(id));
        saveAll(entries);
    }

    /** 语义搜索：余弦相似度，返回 topK。 */
    public List<SearchResult> search(float[] queryVector, int topK) {
        List<SkillEntry> entries = loadAll();
        if (entries.isEmpty()) return List.of();

        List<SearchResult> results = new ArrayList<>();
        for (SkillEntry entry : entries) {
            if (entry.vector() == null || entry.vector().length == 0) continue;
            double score = EmbeddingClient.cosineSimilarity(queryVector, entry.vector());
            results.add(new SearchResult(entry.id(), entry.name(), score, entry.summary()));
        }

        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (results.size() > topK) {
            return results.subList(0, topK);
        }
        return results;
    }

    /** 降级关键词搜索（无 embedding 时使用）。 */
    public List<SearchResult> keywordSearch(String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase();
        List<SkillEntry> entries = loadAll();
        List<SearchResult> results = new ArrayList<>();

        for (SkillEntry entry : entries) {
            String text = (entry.name() + " " + entry.summary()).toLowerCase();
            // 简单包含匹配 + 分值估算（匹配次数 / 查询词数）
            int matchCount = 0;
            for (String word : lower.split("\\s+")) {
                if (text.contains(word)) matchCount++;
            }
            if (matchCount > 0) {
                double score = (double) matchCount / lower.split("\\s+").length;
                results.add(new SearchResult(entry.id(), entry.name(), score, entry.summary()));
            }
        }

        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (results.size() > topK) {
            return results.subList(0, topK);
        }
        return results;
    }

    /** 检查指定 skill 是否已有索引。 */
    public boolean isIndexed(String skillId) {
        return loadAll().stream().anyMatch(e -> e.id().equals(skillId));
    }

    /** 获取索引数量。 */
    public int count() {
        return loadAll().size();
    }

    // ── 内部 ──

    @SuppressWarnings("unchecked")
    private void saveAll(List<SkillEntry> entries) {
        try {
            ensureDir();
            List<Map<String, Object>> skillList = new ArrayList<>();
            for (SkillEntry e : entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.id());
                m.put("name", e.name());
                m.put("summary", e.summary());
                if (e.vector() != null) {
                    List<Float> vList = new ArrayList<>();
                    for (float v : e.vector()) vList.add(v);
                    m.put("vector", vList);
                }
                m.put("updatedAt", e.updatedAt());
                skillList.add(m);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("skills", skillList);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(indexFile.toFile(), root);
            log.debug("[SkillIndex] saved {} entries", entries.size());
        } catch (IOException e) {
            log.error("[SkillIndex] Failed to save index: {}", e.getMessage());
        }
    }

    // ── records ──

    public record SkillEntry(String id, String name, String summary, float[] vector, long updatedAt) {}

    public record SearchResult(String id, String name, double score, String summary) {}
}
