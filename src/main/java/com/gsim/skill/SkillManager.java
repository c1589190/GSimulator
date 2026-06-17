package com.gsim.skill;

import com.gsim.data.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * SkillManager — 管理 data/skills/ 下的 Agent 推演技能。
 */
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final Path skillsDir;
    private final Path expDir;
    private final Map<String, SkillDoc> skills = new LinkedHashMap<>();

    public SkillManager(Path dataRoot) {
        this.skillsDir = dataRoot.resolve("skills");
        this.expDir = dataRoot.resolve("experience");
        reload();
    }

    public void initIfNeeded() throws IOException {
        if (!Files.isDirectory(skillsDir)) Files.createDirectories(skillsDir);
    }

    public void reload() {
        skills.clear();
        if (!Files.isDirectory(skillsDir)) return;
        try (Stream<Path> s = Files.walk(skillsDir)) {
            s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".md"))
                    .forEach(this::loadSkill);
        } catch (IOException e) { log.error("reload skills: {}", e.getMessage()); }
    }

    private void loadSkill(Path f) {
        try {
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            String rel = skillsDir.relativize(f).toString();
            DataManager.ParseResult pr = DataManager.parseFrontMatter(raw);
            skills.put(pr.frontMatter().getOrDefault("id", rel), new SkillDoc(pr.frontMatter(), pr.body(), rel));
        } catch (IOException e) { log.warn("read skill {}: {}", f, e.getMessage()); }
    }

    // ==================== access ====================

    public List<SkillDoc> listSkills() { return List.copyOf(skills.values()); }

    public SkillDoc readSkill(String id) { return skills.get(id); }

    public List<SkillDoc> searchSkills(String query, int limit) {
        String lq = query.toLowerCase();
        List<SkillDoc> results = new ArrayList<>();
        for (SkillDoc s : skills.values()) {
            double score = 0;
            if (s.name().toLowerCase().contains(lq)) score += 5;
            for (String t : s.tags()) if (t.toLowerCase().contains(lq)) { score += 3; break; }
            score += countMatches(s.body().toLowerCase(), lq);
            if (score > 0) results.add(s);
        }
        results.sort((a,b)->Double.compare(b.searchScore(lq), a.searchScore(lq)));
        return results.size() > limit ? results.subList(0, limit) : results;
    }

    /** 合成 Agent Skill 上下文。 */
    public String getSkillContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Agent 技能上下文\n\n");
        for (SkillDoc s : skills.values()) {
            sb.append("## ").append(s.name()).append("\n");
            sb.append(s.body()).append("\n\n");
        }
        return sb.toString();
    }

    public String buildSkillContextForRun() {
        return getSkillContext();
    }

    /** 从 experience 汇总生成 skill 草稿。 */
    public String summarizeExperienceToSkill() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("id: skill.generated\ntype: skill\nname: 自动生成技能\nscope: global\ntags: [自动生成]\nupdated: 2026-06-18\n-------------------\n\n");
        sb.append("# 自动生成技能\n\n## 经验汇总\n\n");

        if (Files.isDirectory(expDir)) {
            try (Stream<Path> s = Files.walk(expDir)) {
                s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".md"))
                        .forEach(f -> {
                            try {
                                String raw = Files.readString(f, StandardCharsets.UTF_8);
                                String body = DataManager.extractBody(raw);
                                // 提取关键段落
                                for (String section : List.of("场景", "发生了什么", "经验结论")) {
                                    int start = body.indexOf("## " + section);
                                    if (start >= 0) {
                                        int end = body.indexOf("\n## ", start + 3 + section.length());
                                        if (end < 0) end = body.length();
                                        String sec = body.substring(start, end).trim();
                                        sb.append(sec).append("\n\n");
                                    }
                                }
                            } catch (IOException ignored) {}
                        });
            }
        }
        String content = sb.toString();
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("generated-skill.md"), content, StandardCharsets.UTF_8);
        reload();
        log.info("Generated skill summary to generated-skill.md");
        return content;
    }

    /** Skill 文档。 */
    public record SkillDoc(Map<String, String> fm, String body, String path) {
        public String id() { return fm.getOrDefault("id", ""); }
        public String name() { return fm.getOrDefault("name", ""); }
        public String type() { return fm.getOrDefault("type", "skill"); }
        public List<String> tags() {
            String raw = fm.getOrDefault("tags", "");
            if (raw.isBlank()) return List.of();
            String c = raw.trim(); if (c.startsWith("[")&&c.endsWith("]")) c=c.substring(1,c.length()-1);
            return List.of(c.split("\\s*,\\s*"));
        }
        double searchScore(String lq) {
            double s=0; if(name().toLowerCase().contains(lq))s+=5;
            for(String t:tags())if(t.toLowerCase().contains(lq)){s+=3;break;}
            s+=SkillManager.countMatches(body().toLowerCase(),lq); return s;
        }
    }

    static int countMatches(String t, String kw) {
        if(kw.isEmpty())return 0;int c=0,i=0;
        while((i=t.indexOf(kw,i))!=-1){c++;i+=kw.length();}return c;
    }
}
