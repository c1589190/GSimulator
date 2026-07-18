package com.gsim.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.llm.EmbeddingClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skill 嵌入向量索引 — 管理 skills/.embdb/index.json。
 *
 * <p>每个条目含：id, name, summary, vector (float[]), updatedAt。
 */
public class SkillIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillIndex.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path indexFile;

    /**
     * 构造 Skill 索引管理器。
     *
     * @param skillsDir Skill 目录路径，索引文件存放于 {skillsDir}/.embdb/index.json
     */
    public SkillIndex(Path skillsDir) {
        this.indexFile = skillsDir.resolve(".embdb").resolve("index.json");
    }

    /**
     * 确保 .embdb 目录存在，不存在时自动创建。
     *
     * @throws IOException 目录创建失败时抛出
     */
    public void ensureDir() throws IOException {
        Path dir = indexFile.getParent();
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * 加载所有已索引条目。
     *
     * @return SkillEntry 列表，索引文件不存在时返回空列表
     */
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
                long updatedAt = s.containsKey("updatedAt") ? ((Number) s.get("updatedAt")).longValue() : 0;

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

    /**
     * 添加或更新索引条目。
     *
     * @param id      Skill ID
     * @param name    Skill 名称
     * @param summary Skill 摘要文本
     * @param vector  嵌入向量（float 数组）
     */
    public void upsert(String id, String name, String summary, float[] vector) {
        List<SkillEntry> entries = new ArrayList<>(loadAll());
        entries.removeIf(e -> e.id().equals(id));
        entries.add(new SkillEntry(id, name, summary, vector, System.currentTimeMillis()));
        saveAll(entries);
    }

    /**
     * 删除索引条目。
     *
     * @param id 要删除的 Skill ID
     */
    public void remove(String id) {
        List<SkillEntry> entries = new ArrayList<>(loadAll());
        entries.removeIf(e -> e.id().equals(id));
        saveAll(entries);
    }

    /**
     * 语义搜索：使用余弦相似度计算，返回 topK 结果。
     *
     * @param queryVector 查询嵌入向量
     * @param topK        返回的最多结果数
     * @return SearchResult 列表，按相似度降序排列
     */
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

    /**
     * 降级关键词搜索（无 embedding 时使用的备用方案）。
     *
     * @param query 搜索关键词
     * @param topK  返回的最多结果数
     * @return SearchResult 列表，按匹配得分降序排列
     */
    public List<SearchResult> keywordSearch(String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase(Locale.ROOT);
        List<SkillEntry> entries = loadAll();
        List<SearchResult> results = new ArrayList<>();

        for (SkillEntry entry : entries) {
            String text = (entry.name() + " " + entry.summary()).toLowerCase(Locale.ROOT);
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

    /**
     * 检查指定 skill 是否已有索引。
     *
     * @param skillId Skill ID
     * @return 已索引返回 true，否则返回 false
     */
    public boolean isIndexed(String skillId) {
        return loadAll().stream().anyMatch(e -> e.id().equals(skillId));
    }

    /**
     * 获取索引条目数量。
     *
     * @return 索引条目总数
     */
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

    /**
     * Skill 索引条目记录。
     *
     * @param id        Skill ID
     * @param name      Skill 名称
     * @param summary   Skill 摘要文本
     * @param vector    嵌入向量（float 数组）
     * @param updatedAt 最后更新时间戳
     */
    public record SkillEntry(String id, String name, String summary, float[] vector, long updatedAt) {}

    /**
     * 搜索结果记录。
     *
     * @param id      Skill ID
     * @param name    Skill 名称
     * @param score   搜索匹配得分
     * @param summary Skill 摘要文本
     */
    public record SearchResult(String id, String name, double score, String summary) {}
}
