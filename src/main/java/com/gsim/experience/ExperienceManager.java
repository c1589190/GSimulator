package com.gsim.experience;

import com.gsim.data.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * ExperienceManager — 管理 data/experience/ 下的交互经验。
 * Experience 属于 Agent 实践经验，与世界正史分离。
 */
public class ExperienceManager {

    private static final Logger log = LoggerFactory.getLogger(ExperienceManager.class);

    private final Path expDir;
    private final Map<String, ExpDoc> experiences = new LinkedHashMap<>();

    public ExperienceManager(Path dataRoot) {
        this.expDir = dataRoot.resolve("experience");
        reload();
    }

    public void initIfNeeded() throws IOException {
        if (!Files.isDirectory(expDir)) Files.createDirectories(expDir);
    }

    public void reload() {
        experiences.clear();
        if (!Files.isDirectory(expDir)) return;
        try (Stream<Path> s = Files.walk(expDir)) {
            s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".md"))
                    .forEach(this::loadExp);
        } catch (IOException e) { log.error("reload experiences: {}", e.getMessage()); }
    }

    private void loadExp(Path f) {
        try {
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            String rel = expDir.relativize(f).toString();
            DataManager.ParseResult pr = DataManager.parseFrontMatter(raw);
            experiences.put(pr.frontMatter().getOrDefault("id", rel), new ExpDoc(pr.frontMatter(), pr.body(), rel));
        } catch (IOException e) { log.warn("read exp {}: {}", f, e.getMessage()); }
    }

    // ==================== access ====================

    public List<ExpDoc> listExperiences() { return List.copyOf(experiences.values()); }

    public ExpDoc readExperience(String id) { return experiences.get(id); }

    public List<ExpDoc> searchExperiences(String query, int limit) {
        String lq = query.toLowerCase();
        List<ExpDoc> results = new ArrayList<>();
        for (ExpDoc e : experiences.values()) {
            double score = 0;
            if (e.name().toLowerCase().contains(lq)) score += 5;
            for (String t : e.tags()) if (t.toLowerCase().contains(lq)) { score += 3; break; }
            score += countMatches(e.body().toLowerCase(), lq);
            if (score > 0) results.add(e);
        }
        results.sort((a,b)->Double.compare(b.searchScore(lq), a.searchScore(lq)));
        return results.size() > limit ? results.subList(0, limit) : results;
    }

    /** 新增一条经验。 */
    public ExpDoc writeExperience(String title, String content) throws IOException {
        initIfNeeded();
        String nextId = String.format("experience.e%04d", experiences.size() + 1);
        String timestamp = java.time.LocalDate.now().toString();

        String full = "id: " + nextId + "\n" +
                "type: experience\n" +
                "name: " + title + "\n" +
                "source: user-interaction\n" +
                "tags: [经验]\n" +
                "updated: " + timestamp + "\n" +
                "-------------------\n\n" +
                "# " + title + "\n\n" + content + "\n";

        Files.createDirectories(expDir);
        Files.writeString(expDir.resolve("e" + String.format("%04d", experiences.size() + 1) + ".md"),
                full, StandardCharsets.UTF_8);
        reload();
        return experiences.get(nextId);
    }

    public ExpDoc createExperienceFromRun(String runId, String summary, String lessons) throws IOException {
        return writeExperience("Run " + runId, "## 场景\n" + summary + "\n\n## 经验结论\n" + lessons + "\n");
    }

    /** 经验文档。 */
    public record ExpDoc(Map<String, String> fm, String body, String path) {
        public String id() { return fm.getOrDefault("id", ""); }
        public String name() { return fm.getOrDefault("name", ""); }
        public String type() { return "experience"; }
        public List<String> tags() {
            String raw = fm.getOrDefault("tags", "");
            if (raw.isBlank()) return List.of();
            String c = raw.trim(); if (c.startsWith("[")&&c.endsWith("]")) c=c.substring(1,c.length()-1);
            return List.of(c.split("\\s*,\\s*"));
        }
        double searchScore(String lq) {
            double s=0; if(name().toLowerCase().contains(lq))s+=5;
            for(String t:tags())if(t.toLowerCase().contains(lq)){s+=3;break;}
            s+=ExperienceManager.countMatches(body().toLowerCase(),lq); return s;
        }
    }

    static int countMatches(String t, String kw) {
        if(kw.isEmpty())return 0;int c=0,i=0;
        while((i=t.indexOf(kw,i))!=-1){c++;i+=kw.length();}return c;
    }
}
