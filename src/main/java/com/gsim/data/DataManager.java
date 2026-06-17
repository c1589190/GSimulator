package com.gsim.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gsim.resource.ResourceManager;

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
        try {
            writeFile(skillsDir.resolve("simulation-method.md"), ResourceManager.readText("gsim/templates/simulation-method.md"));
            writeFile(skillsDir.resolve("tool-policy.md"), ResourceManager.readText("gsim/templates/tool-policy-skill.md"));
            writeFile(skillsDir.resolve("output-style.md"), ResourceManager.readText("gsim/templates/output-style-skill.md"));
            writeFile(skillsDir.resolve("failure-lessons.md"), ResourceManager.readText("gsim/templates/failure-lessons-skill.md"));
            writeFile(skillsDir.resolve("generated-skill.md"), ResourceManager.readText("gsim/templates/generated-skill.md"));
        } catch (IOException e) { throw new UncheckedIOException(e); }

        Path expDir = dataRoot.resolve("experience");
        Files.createDirectories(expDir);
        try {
            writeFile(expDir.resolve("e0001.md"), ResourceManager.readText("gsim/templates/e0001-experience.md"));
        } catch (IOException e) { throw new UncheckedIOException(e); }

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

        String today = "2026-06-18";
        try {
            writeFile(wd.resolve("world.md"), ResourceManager.renderTemplate("gsim/templates/world-template.md", "updated", today));
            writeFile(wd.resolve("entities.md"), ResourceManager.renderTemplate("gsim/templates/entities-template.md", "updated", today));
            writeFile(wd.resolve("rules.md"), ResourceManager.renderTemplate("gsim/templates/rules-template.md", "updated", today));
            writeFile(wd.resolve("input.md"), ResourceManager.renderTemplate("gsim/templates/input-template.md", "updated", today));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

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
        try {
            return ResourceManager.renderTemplate("gsim/templates/branch-template.md",
                    "id", id, "name", name, "parent", parent,
                    "turn", String.valueOf(turn), "world_time", worldTime != null ? worldTime : "",
                    "input", input, "llm_user", llmUser,
                    "result", result, "world_delta", worldDelta, "entity_delta", entityDelta,
                    "rule_delta", ruleDelta, "interaction_delta", interactionDelta,
                    "skill_delta", skillDelta, "risks", risks,
                    "updated", "2026-06-18");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    // ==================== sim / nextturn helpers ====================

    /** 生成下一个 branch ID (b0001, b0002, ...) */
    public String generateNextBranchId() {
        int max = 0;
        for (DataDocument d : documents.values()) {
            if (!"branch".equals(d.type())) continue;
            String bid = d.id().replace("branch.b", "").replaceAll("-.*", "");
            try { int n = Integer.parseInt(bid); if (n > max) max = n; } catch (NumberFormatException ignored) {}
        }
        return "branch.b" + String.format("%04d", max + 1);
    }

    /** 创建下一回合节点，切换 active branch，清空 input。不调用 LLM。 */
    public DataDocument createNextTurnBranch(String worldTime, String note) throws IOException {
        String newId = generateNextBranchId();
        String displayName = "时间节点 " + newId;
        String parent = activeBranch;
        DataDocument parentDoc = documents.get(parent);
        int turn = 1;
        if (parentDoc != null) {
            String pt = parentDoc.frontMatter().getOrDefault("turn", "0");
            try { turn = Integer.parseInt(pt) + 1; } catch (NumberFormatException ignored) {}
        }
        String nodeInput = (note != null && !note.isBlank()) ? note : "无。";

        String content = buildBranchContent(newId, displayName, parent, turn,
                worldTime != null ? worldTime : "",
                nodeInput, "无。",
                "待推演。", "无。", "无。", "无。", "无。", "无。", "待后续推演。");

        writeFile(worldDir().resolve("branches/" + branchIdToFilename(newId)), content);
        activeBranch = newId;
        Files.writeString(worldDir().resolve("active-branch.txt"), newId, StandardCharsets.UTF_8);
        clearInputSilent();
        reload();
        log.info("Created next-turn branch '{}' parent='{}' turn={}", newId, parent, turn);
        return documents.get(newId);
    }

    /** 检查 branch 是否有推演结果。 */
    public boolean hasSimulationResult(String branchId) {
        DataDocument doc = documents.get(branchId);
        if (doc == null) return false;
        String sec = extractSection(doc.body(), "三、推演结果");
        String body = sec.replace("## 三、推演结果", "").trim();
        return !body.isEmpty() && !"无。".equals(body) && !"待推演。".equals(body);
    }

    /** 获取同级节点（parent 相同）。 */
    public List<DataDocument> getSiblingBranches(String branchId) {
        DataDocument doc = documents.get(branchId);
        if (doc == null) return List.of();
        String parent = doc.frontMatter().getOrDefault("parent", "none");
        return documents.values().stream()
                .filter(d -> "branch".equals(d.type()) && parent.equals(d.frontMatter().getOrDefault("parent", "none")))
                .sorted(Comparator.comparing(DataDocument::id)).toList();
    }

    /** 获取子节点。 */
    public List<DataDocument> getChildBranches(String branchId) {
        return documents.values().stream()
                .filter(d -> "branch".equals(d.type()) && branchId.equals(d.frontMatter().getOrDefault("parent", "")))
                .sorted(Comparator.comparing(DataDocument::id)).toList();
    }

    /** 渲染带 active 标记的时间线树文本。 */
    public String renderTimelineTreeWithActive() {
        StringBuilder sb = new StringBuilder();
        List<TreeNode> roots = getTimelineTree();
        for (TreeNode n : roots) renderTreeNode(n, sb, 0);
        return sb.toString();
    }

    private void renderTreeNode(TreeNode node, StringBuilder sb, int depth) {
        if (depth > 0) sb.append("  ".repeat(depth - 1)).append("├─ ");
        String marker = node.id().equals(activeBranch) ? " *" : "";
        sb.append(node.id()).append(" — ").append(node.name()).append(marker).append("\n");
        for (TreeNode child : node.children()) renderTreeNode(child, sb, depth + 1);
    }

    /** 覆盖写入 branch 的九个章节，保留 front matter / id / parent / turn / world_time。 */
    public void overwriteBranchSections(String branchId, BranchUpdate update) throws IOException {
        DataDocument doc = documents.get(branchId);
        if (doc == null || !"branch".equals(doc.type()))
            throw new IOException("Branch not found: " + branchId);

        Map<String, String> fm = doc.frontMatter();
        String newBody = buildBranchContent(
                branchId, fm.getOrDefault("name", "时间节点"),
                fm.getOrDefault("parent", "none"),
                Integer.parseInt(fm.getOrDefault("turn", "0")),
                fm.getOrDefault("world_time", ""),
                update.nodeInput() != null ? update.nodeInput() : "无。",
                update.llmContextLog() != null ? update.llmContextLog() : "### user\n\n无。\n",
                update.simulationResult() != null ? update.simulationResult() : "待推演。",
                update.worldDelta() != null ? update.worldDelta() : "无。",
                update.entityDelta() != null ? update.entityDelta() : "无。",
                update.ruleDelta() != null ? update.ruleDelta() : "无。",
                update.interactionDelta() != null ? update.interactionDelta() : "无。",
                update.skillDelta() != null ? update.skillDelta() : "无。",
                update.nextRisks() != null ? update.nextRisks() : "待后续推演。");

        writeFile(worldDir().resolve("branches/" + branchIdToFilename(branchId)), newBody);
        reload();
        log.info("Overwritten branch '{}'", branchId);
    }

    // ==================== input ====================

    /** 以玩家命令格式追加到 input.md */
    public void appendPlayerInput(String playerName, String content) throws IOException {
        appendInput("* " + playerName + "：" + content + "\n");
    }

    /** 获取 input.md 的 body 文本。 */
    public String getInputBody() {
        return extractBody(readFileContent(worldDir().resolve("input.md")));
    }

    private void clearInputSilent() throws IOException {
        writeFile(worldDir().resolve("input.md"),
                "id: input.current\ntype: input\nname: 当前输入\nupdated: 2026-06-18\n-------------------\n\n# 当前输入\n\n暂无待结算内容。\n");
    }

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
