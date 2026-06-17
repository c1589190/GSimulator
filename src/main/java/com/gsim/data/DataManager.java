package com.gsim.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * DataManager — 世界数据管理器。
 * Branch 是时间节点文件，当前世界状态 = base 文件 + active branch 父链增量。
 */
public class DataManager {

    private static final Logger log = LoggerFactory.getLogger(DataManager.class);
    private static final String DEFAULT_WORLD = "default";
    private static final String ROOT_BRANCH = "branch.b0000-start";

    private final Path dataRoot;
    private final Map<String, DataDocument> documents = new LinkedHashMap<>();
    private String activeWorld = DEFAULT_WORLD;
    private String activeBranch = ROOT_BRANCH;

    public DataManager(Path dataRoot) {
        this.dataRoot = dataRoot.toAbsolutePath();
        try {
            if (!Files.isDirectory(dataRoot)) initDefault();
            else autoLoad();
        } catch (IOException e) { log.error("DataManager init failed: {}", e.getMessage()); }
    }

    // ==================== init ====================

    private void initDefault() throws IOException {
        log.info("Creating default data structure");
        Files.createDirectories(dataRoot);
        Files.writeString(dataRoot.resolve("active-world.txt"), DEFAULT_WORLD, StandardCharsets.UTF_8);

        Path skillsDir = dataRoot.resolve("skills");
        Files.createDirectories(skillsDir);
        writeFile(skillsDir.resolve("simulation-method.md"),
                "id: skill.simulation-method\ntype: skill\nname: 推演方法\nscope: global\ntags: [推演, 方法论]\nupdated: 2026-06-18\n-------------------\n\n" +
                "# 推演方法\n\n## 适用范围\n架空历史文游推演。\n\n## 操作原则\n1. 基于已知事实推演，不凭空编造。\n2. 区分 facts / inferences / hypotheses。\n\n## 工具使用建议\n- 涉及世界观细节查 data。\n- 涉及PRTS设定查 wiki_search。\n\n## 输出要求\nMarkdown 格式，关键实体粗体。\n");
        writeFile(skillsDir.resolve("tool-policy.md"),
                "id: skill.tool-policy\ntype: skill\nname: 工具使用策略\nscope: global\ntags: [工具, 策略]\nupdated: 2026-06-18\n-------------------\n\n" +
                "# 工具使用策略\n\n## 适用范围\nAgent 调用工具时的决策。\n\n## 操作原则\n- 优先用 data_search 查世界数据。\n- 外部设定用 wiki_search。\n- 不确定时两者都查。\n- 最多 5 轮工具调用。\n");
        writeFile(skillsDir.resolve("output-style.md"),
                "id: skill.output-style\ntype: skill\nname: 输出风格\nscope: global\ntags: [输出, 风格]\nupdated: 2026-06-18\n-------------------\n\n" +
                "# 输出风格\n\n## 适用范围\n所有推演结果输出。\n\n## 操作原则\n- 使用 Markdown 格式。\n- 关键人名、地名、势力名粗体。\n- 区分事实、推断、假设。\n- 引用来源路径。\n");
        writeFile(skillsDir.resolve("failure-lessons.md"),
                "id: skill.failure-lessons\ntype: skill\nname: 失败教训\nscope: global\ntags: [失败, 教训]\nupdated: 2026-06-18\n-------------------\n\n" +
                "# 失败教训\n\n## 适用范围\n避免重复错误。\n\n## 常见问题\n暂无（待经验沉淀）。\n");
        writeFile(skillsDir.resolve("generated-skill.md"),
                "id: skill.generated\ntype: skill\nname: 自动生成技能\nscope: global\ntags: [自动生成]\nupdated: 2026-06-18\n-------------------\n\n" +
                "# 自动生成技能\n\n此文件由 /skill summarize 自动生成。\n");

        Path expDir = dataRoot.resolve("experience");
        Files.createDirectories(expDir);
        writeFile(expDir.resolve("e0001.md"),
                "id: experience.e0001\ntype: experience\nname: 交互经验 0001\nsource: user-interaction\ntags: [经验, 模板]\nupdated: 2026-06-18\n-------------------\n\n" +
                "# 交互经验 0001\n\n## 场景\n初始化数据系统。\n\n## 发生了什么\n系统自动创建了默认世界和初始时间节点。\n\n## 用户反馈\n暂无（待交互后记录）。\n\n## 经验结论\n待补充。\n");

        initWorld(DEFAULT_WORLD);
        this.activeWorld = DEFAULT_WORLD;
        this.activeBranch = ROOT_BRANCH;
        reload();
    }

    private void initWorld(String worldName) throws IOException {
        Path wd = dataRoot.resolve("worlds").resolve(worldName);
        for (String d : List.of("branches", "patches/pending", "patches/accepted", "patches/rejected"))
            Files.createDirectories(wd.resolve(d));
        Files.writeString(wd.resolve("active-branch.txt"), ROOT_BRANCH, StandardCharsets.UTF_8);

        writeFile(wd.resolve("world.md"),
                "id: world.base\ntype: always\nname: 世界观\nupdated: 2026-06-18\n-------------------\n\n" +
                "# 世界观\n\n这是一个架空历史世界。\n\n## 地理\n待补充。\n\n## 势力\n待补充。\n");
        writeFile(wd.resolve("entities.md"),
                "id: entities.base\ntype: entity\nname: 实体资料\nupdated: 2026-06-18\n-------------------\n\n" +
                "# 实体资料\n\n## 玩家\n暂无。\n\n## 人物\n暂无。\n\n## 势力\n暂无。\n");
        writeFile(wd.resolve("rules.md"),
                "id: rules.base\ntype: always\nname: 交互规则\nupdated: 2026-06-18\n-------------------\n\n" +
                "# 交互规则\n\n## 行动规则\n玩家提交行动，由主持人审核。\n\n## 推演规则\n基于现实逻辑推演，考虑多方利益。\n");
        writeFile(wd.resolve("input.md"),
                "id: input.current\ntype: input\nname: 当前输入\nupdated: 2026-06-18\n-------------------\n\n" +
                "# 当前输入\n\n暂无待结算内容。\n");

        writeFile(wd.resolve("branches/b0000-start.md"), buildBranchContent(
                ROOT_BRANCH, "时间原点", "none", 0, "时间原点",
                "世界初始化。", "无。",
                "无。", "无。", "无。", "无。", "无。", "无。", "待后续推演。"));

        log.info("Initialized world '{}'", worldName);
    }

    private String buildBranchContent(String id, String name, String parent, int turn, String worldTime,
                                       String input, String llmUser,
                                       String result, String worldDelta, String entityDelta,
                                       String ruleDelta, String interactionDelta, String skillDelta, String risks) {
        return "id: " + id + "\ntype: branch\nname: " + name + "\nparent: " + parent +
                "\nturn: " + turn + "\nworld_time: " + worldTime +
                "\nstatus: resolved\ntags: [时间节点, 推演记录, 上下文节点]\nupdated: 2026-06-18\n-------------------\n\n" +
                "# " + name + "\n\n" +
                "## 一、本节点输入\n\n" + input + "\n\n" +
                "## 二、LLM 上下文记录\n\n" +
                "### user\n\n" + llmUser + "\n\n" +
                "## 三、推演结果\n\n" + result + "\n\n" +
                "## 四、世界观/设定增量\n\n" + worldDelta + "\n\n" +
                "## 五、实体状态增量\n\n" + entityDelta + "\n\n" +
                "## 六、推演规则增量\n\n" + ruleDelta + "\n\n" +
                "## 七、交互逻辑增量\n\n" + interactionDelta + "\n\n" +
                "## 八、未总结 Skill 增量\n\n" + skillDelta + "\n\n" +
                "## 九、下节点风险\n\n" + risks + "\n";
    }

    private void autoLoad() throws IOException {
        Path aw = dataRoot.resolve("active-world.txt");
        if (Files.exists(aw)) activeWorld = Files.readString(aw, StandardCharsets.UTF_8).trim();
        Path wd = dataRoot.resolve("worlds").resolve(activeWorld);
        if (!Files.isDirectory(wd)) {
            activeWorld = DEFAULT_WORLD;
            if (!Files.isDirectory(dataRoot.resolve("worlds").resolve(DEFAULT_WORLD))) initWorld(DEFAULT_WORLD);
            Files.writeString(aw, DEFAULT_WORLD, StandardCharsets.UTF_8);
            wd = dataRoot.resolve("worlds").resolve(DEFAULT_WORLD);
        }
        Path ab = wd.resolve("active-branch.txt");
        if (Files.exists(ab)) {
            activeBranch = Files.readString(ab, StandardCharsets.UTF_8).trim();
        } else {
            activeBranch = ROOT_BRANCH;
        }
        validateActiveBranch();
        reload();
    }

    public void init() throws IOException {
        if (!Files.isDirectory(dataRoot)) { initDefault(); return; }
        Path wd = dataRoot.resolve("worlds").resolve("default");
        if (!Files.isDirectory(wd)) initWorld("default");
        this.activeWorld = "default"; this.activeBranch = ROOT_BRANCH;
        Files.writeString(dataRoot.resolve("active-world.txt"), "default", StandardCharsets.UTF_8);
        reload();
    }

    // ==================== world ====================

    public String getActiveWorld() { return activeWorld; }
    public String getActiveBranch() { return activeBranch; }

    public List<String> listWorlds() {
        Path d = dataRoot.resolve("worlds");
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) { return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList(); }
        catch (IOException e) { return List.of(); }
    }

    public void createWorld(String name) throws IOException {
        if (Files.exists(dataRoot.resolve("worlds").resolve(name))) throw new IOException("World exists: " + name);
        initWorld(name);
    }

    public void switchWorld(String name) throws IOException {
        Path wd = dataRoot.resolve("worlds").resolve(name);
        if (!Files.isDirectory(wd)) throw new IOException("World not found: " + name);
        activeWorld = name;
        Files.writeString(dataRoot.resolve("active-world.txt"), name, StandardCharsets.UTF_8);
        Path ab = wd.resolve("active-branch.txt");
        activeBranch = Files.exists(ab) ? Files.readString(ab, StandardCharsets.UTF_8).trim() : ROOT_BRANCH;
        validateActiveBranch();
        reload();
    }

    // ==================== branch ====================

    /** 规范化 branchId: b0001-contact → branch.b0001-contact */
    public static String normalizeBranchId(String input) {
        if (input == null || input.isBlank()) return ROOT_BRANCH;
        String trimmed = input.trim();
        if (trimmed.startsWith("branch.")) return trimmed;
        return "branch." + trimmed;
    }

    /** 验证 active branch 是否存在，不存在则回退并自动创建。 */
    private void validateActiveBranch() throws IOException {
        Path bFile = worldDir().resolve("branches/" + branchIdToFilename(activeBranch));
        if (!Files.exists(bFile)) {
            log.warn("Active branch '{}' not found at {}, falling back to {}", activeBranch, bFile, ROOT_BRANCH);
            activeBranch = ROOT_BRANCH;
            Files.writeString(worldDir().resolve("active-branch.txt"), ROOT_BRANCH, StandardCharsets.UTF_8);
            // 如果 b0000-start.md 也不存在则自动创建
            if (!Files.exists(worldDir().resolve("branches/" + branchIdToFilename(ROOT_BRANCH)))) {
                log.warn("Root branch file missing, auto-creating");
                writeFile(worldDir().resolve("branches/" + branchIdToFilename(ROOT_BRANCH)),
                        buildBranchContent(ROOT_BRANCH, "时间原点", "none", 0, "时间原点",
                                "世界初始化。", "无。",
                                "无。", "无。", "无。", "无。", "无。", "无。", "待后续推演。"));
            }
        }
    }

    public List<DataDocument> listBranches() {
        return documents.values().stream().filter(d -> "branch".equals(d.type()))
                .sorted(Comparator.comparing(DataDocument::id)).toList();
    }

    public void switchBranch(String rawId) throws IOException {
        String branchId = normalizeBranchId(rawId);
        if (documents.get(branchId) == null || !"branch".equals(documents.get(branchId).type()))
            throw new IOException("Branch not found: " + branchId + " (normalized from: " + rawId + ")");
        activeBranch = branchId;
        Files.writeString(worldDir().resolve("active-branch.txt"), branchId, StandardCharsets.UTF_8);
        log.info("Switched to branch {}", branchId);
    }

    public DataDocument createBranch(String rawId, String name, String worldTime) throws IOException {
        String branchId = normalizeBranchId(rawId);
        String parent = activeBranch;
        DataDocument parentDoc = documents.get(parent);
        int turn = 1;
        if (parentDoc != null) {
            String pt = parentDoc.frontMatter().getOrDefault("turn", "0");
            try { turn = Integer.parseInt(pt) + 1; } catch (NumberFormatException ignored) {}
        }
        String inputContent = readInputContent();
        String displayName = (name != null && !name.isBlank()) ? name : ("时间节点 " + branchId);
        String nodeInput = inputContent.isBlank() ? "无。" : inputContent;
        String llmUser = inputContent.isBlank() ? "无。" : inputContent;

        String content = buildBranchContent(branchId, displayName, parent, turn,
                worldTime != null ? worldTime : "",
                nodeInput, llmUser,
                "待推演。", "无。", "无。", "无。", "无。", "无。", "待后续推演。");

        writeFile(worldDir().resolve("branches/" + branchIdToFilename(branchId)), content);
        activeBranch = branchId;
        Files.writeString(worldDir().resolve("active-branch.txt"), branchId, StandardCharsets.UTF_8);
        clearInput();
        reload();
        log.info("Created branch '{}' parent='{}' turn={}", branchId, parent, turn);
        return documents.get(branchId);
    }

    public List<DataDocument> getBranchChain(String branchId) {
        List<DataDocument> chain = new ArrayList<>();
        String current = branchId;
        Set<String> seen = new HashSet<>();
        while (current != null && !current.equals("none") && seen.add(current)) {
            DataDocument doc = documents.get(current);
            if (doc == null || !"branch".equals(doc.type())) break;
            chain.add(doc);
            current = doc.frontMatter().getOrDefault("parent", "none");
        }
        return chain;
    }

    /** 从指定 branch 的父链中提取所有匹配 heading 的章节内容。 */
    public String extractBranchSectionChain(String branchId, String heading) {
        List<DataDocument> chain = getBranchChain(branchId);
        StringBuilder sb = new StringBuilder();
        for (int i = chain.size() - 1; i >= 0; i--) {
            DataDocument b = chain.get(i);
            String sec = extractSection(b.body(), heading);
            if (!sec.isBlank()) sb.append(sec).append("\n");
        }
        return sb.toString();
    }

    /** 获取父链中所有 "八、未总结 Skill 增量" 的汇总。 */
    public String getBranchSkillDeltaContext() {
        return extractBranchSectionChain(activeBranch, "八、未总结 Skill 增量");
    }

    /** 时间线树。 */
    public List<TreeNode> getTimelineTree() {
        Map<String, List<String>> children = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (DataDocument doc : documents.values()) {
            if (!"branch".equals(doc.type())) continue;
            String parent = doc.frontMatter().getOrDefault("parent", "none");
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(doc.id());
            names.put(doc.id(), doc.name());
        }
        List<TreeNode> roots = new ArrayList<>();
        if (children.containsKey("none")) for (String rid : children.get("none")) roots.add(buildTree(rid, children, names));
        return roots;
    }

    private TreeNode buildTree(String id, Map<String, List<String>> children, Map<String, String> names) {
        List<TreeNode> kids = new ArrayList<>();
        if (children.containsKey(id)) for (String cid : children.get(id)) kids.add(buildTree(cid, children, names));
        return new TreeNode(id, names.getOrDefault(id, id), kids);
    }
    public record TreeNode(String id, String name, List<TreeNode> children) {}

    // ==================== input ====================

    public void appendInput(String text) throws IOException {
        Path f = worldDir().resolve("input.md");
        String current = Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
        if (!current.endsWith("\n")) current += "\n";
        current += text + "\n";
        writeFile(f, current);
        reload();
    }

    public void clearInput() throws IOException {
        writeFile(worldDir().resolve("input.md"),
                "id: input.current\ntype: input\nname: 当前输入\nupdated: 2026-06-18\n-------------------\n\n# 当前输入\n\n暂无待结算内容。\n");
        reload();
    }

    private String readInputContent() throws IOException {
        Path f = worldDir().resolve("input.md");
        if (!Files.exists(f)) return "";
        return extractBody(Files.readString(f, StandardCharsets.UTF_8));
    }

    // ==================== access ====================

    public int docCount() { return documents.size(); }
    public DataDocument readById(String id) { return documents.get(id); }
    public List<DataDocument> listAll() { return documents.values().stream().sorted(Comparator.comparing(DataDocument::rawPath)).toList(); }
    public List<DataDocument> listByType(String type) { return documents.values().stream().filter(d -> type.equals(d.type())).toList(); }

    // ==================== effective context ====================

    public String getEffectiveContext() {
        StringBuilder sb = new StringBuilder();
        sb.append(getEffectiveWorldContext()).append("\n");
        sb.append(getEffectiveEntityContext()).append("\n");
        sb.append(getEffectiveRuleContext()).append("\n");
        sb.append(readFileContent(worldDir().resolve("input.md"))).append("\n");

        DataDocument ab = documents.get(activeBranch);
        if (ab != null) {
            sb.append("## 当前时间节点\n");
            sb.append("ID: ").append(ab.id()).append("\n名称: ").append(ab.name()).append("\n");
            sb.append("世界时间: ").append(ab.frontMatter().getOrDefault("world_time", "")).append("\n");
            sb.append("Turn: ").append(ab.frontMatter().getOrDefault("turn", "0")).append("\n\n");
        }

        sb.append("## 时间线增量\n\n");
        List<DataDocument> chain = getBranchChain(activeBranch);
        for (int i = chain.size() - 1; i >= 0; i--) {
            DataDocument b = chain.get(i);
            sb.append("### ").append(b.name()).append(" (").append(b.id()).append(")\n");
            sb.append(extractSection(b.body(), "一、本节点输入"));
            sb.append(extractSection(b.body(), "二、LLM 上下文记录"));
            sb.append(extractSection(b.body(), "三、推演结果"));
            sb.append(extractSection(b.body(), "四、世界观/设定增量"));
            sb.append(extractSection(b.body(), "五、实体状态增量"));
            sb.append(extractSection(b.body(), "六、推演规则增量"));
            sb.append(extractSection(b.body(), "七、交互逻辑增量"));
        }
        return sb.toString();
    }

    public String getEffectiveWorldContext() { return readFileContent(worldDir().resolve("world.md")); }
    public String getEffectiveEntityContext() { return readFileContent(worldDir().resolve("entities.md")); }
    public String getEffectiveRuleContext() { return readFileContent(worldDir().resolve("rules.md")); }

    /** 父链中的交互逻辑增量。 */
    public String getEffectiveInteractionContext() {
        return extractBranchSectionChain(activeBranch, "七、交互逻辑增量");
    }

    // ==================== search ====================

    public List<DataSearchResult> search(String keyword, int maxResults) {
        String lower = keyword.toLowerCase();
        List<DataSearchResult> results = new ArrayList<>();
        for (DataDocument doc : documents.values()) {
            double score = 0;
            if (doc.name().toLowerCase().contains(lower)) score += 5;
            for (String a : doc.aliases()) if (a.toLowerCase().contains(lower)) { score += 4; break; }
            for (String t : doc.tags()) if (t.toLowerCase().contains(lower)) { score += 3; break; }
            score += countMatches(doc.body().toLowerCase(), lower);
            if (score > 0) results.add(new DataSearchResult(doc.id(), doc.type(), doc.role(), doc.name(),
                    doc.rawPath(), buildSnippet(doc.body(), lower, 300), score));
        }
        results.sort(Comparator.comparingDouble(DataSearchResult::score).reversed());
        return results.size() > maxResults ? results.subList(0, maxResults) : results;
    }

    // ==================== reload / internal ====================

    public void reload() throws IOException {
        documents.clear();
        Path wd = worldDir();
        if (!Files.isDirectory(wd)) return;
        for (String fn : List.of("world.md", "entities.md", "rules.md", "input.md")) {
            Path f = wd.resolve(fn);
            if (Files.exists(f)) loadDoc(f, fn);
        }
        Path bd = wd.resolve("branches");
        if (Files.isDirectory(bd)) {
            try (Stream<Path> s = Files.walk(bd)) {
                s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".md"))
                        .forEach(f -> { String rel = wd.relativize(f).toString(); loadDoc(f, rel); });
            }
        }
        log.info("Loaded {} docs from world='{}' branch='{}'", documents.size(), activeWorld, activeBranch);
    }

    private void loadDoc(Path file, String relPath) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            ParseResult pr = parseFrontMatter(raw);
            DataDocument doc = pr.frontMatter.isEmpty()
                    ? DataDocument.withoutFrontMatter(pr.body, relPath)
                    : new DataDocument(pr.frontMatter, pr.body, relPath);
            documents.put(doc.id(), doc);
        } catch (IOException e) { log.warn("Failed to read {}: {}", file, e.getMessage()); }
    }

    private Path worldDir() { return dataRoot.resolve("worlds").resolve(activeWorld); }

    private void writeFile(Path f, String content) throws IOException {
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
    }

    private String readFileContent(Path f) {
        try { return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : ""; }
        catch (IOException e) { return ""; }
    }

    public static String extractBody(String raw) {
        int sep = raw.indexOf("-------------------");
        if (sep < 0) { sep = raw.indexOf("\n---\n"); if (sep < 0) sep = raw.indexOf("\n---"); }
        if (sep >= 0) {
            int start = raw.indexOf('\n', sep); if (start < 0) start = sep;
            String body = raw.substring(start).trim();
            if (body.startsWith("---")) { int nl = body.indexOf('\n'); body = nl >= 0 ? body.substring(nl+1).trim() : ""; }
            else if (body.startsWith("--")) { int nl = body.indexOf('\n'); body = nl >= 0 ? body.substring(nl+1).trim() : ""; }
            return body;
        }
        return raw;
    }

    private String extractSection(String body, String heading) {
        int start = body.indexOf("## " + heading);
        if (start < 0) return "";
        int end = body.indexOf("\n## ", start + heading.length() + 4);
        if (end < 0) end = body.length();
        return body.substring(start, end).trim() + "\n\n";
    }

    private String branchIdToFilename(String branchId) {
        return branchId.replace("branch.", "") + ".md";
    }

    // ==================== front matter ====================

    public static ParseResult parseFrontMatter(String raw) {
        if (raw == null || raw.isBlank()) return new ParseResult(Map.of(), "");
        String trimmed = raw.trim();
        int sep = trimmed.indexOf("-------------------");
        if (sep < 0) { sep = trimmed.indexOf("\n---\n"); if (sep < 0) sep = trimmed.indexOf("\n---"); }
        String fmPart, bodyPart;
        if (sep >= 0) {
            fmPart = trimmed.substring(0, sep);
            int bs = trimmed.indexOf('\n', sep); if (bs < 0) bs = sep;
            bodyPart = trimmed.substring(bs).trim();
            if (bodyPart.startsWith("---")) { int nl = bodyPart.indexOf('\n'); bodyPart = nl >= 0 ? bodyPart.substring(nl+1).trim() : ""; }
            else if (bodyPart.startsWith("--")) { int nl = bodyPart.indexOf('\n'); bodyPart = nl >= 0 ? bodyPart.substring(nl+1).trim() : ""; }
        } else { fmPart = trimmed; bodyPart = ""; }
        Map<String, String> fm = new LinkedHashMap<>();
        for (String line : fmPart.split("\n")) { line = line.trim(); if (line.isEmpty()) continue;
            int c = line.indexOf(':'); if (c > 0) fm.put(line.substring(0,c).trim(), line.substring(c+1).trim()); }
        return new ParseResult(fm, bodyPart);
    }
    public record ParseResult(Map<String, String> frontMatter, String body) {}

    private int countMatches(String t, String kw) { if(kw.isEmpty())return 0;int c=0,i=0; while((i=t.indexOf(kw,i))!=-1){c++;i+=kw.length();}return c; }
    private String buildSnippet(String b, String lk, int max) { if(b.isEmpty()||lk.isEmpty())return b.length()>max?b.substring(0,max)+"...":b; int p=b.toLowerCase().indexOf(lk);if(p<0)return b.length()>max?b.substring(0,max)+"...":b; int s=Math.max(0,p-max/3),e=Math.min(b.length(),p+max*2/3); return (s>0?"...":"")+b.substring(s,e)+(e<b.length()?"...":""); }
}
