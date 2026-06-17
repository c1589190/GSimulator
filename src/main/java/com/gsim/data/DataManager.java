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
 * DataManager — 管理 data/ 目录下的 Markdown 世界数据。
 *
 * 启动行为：
 * - data/ 不存在 → 自动创建 default world + main branch
 * - data/ 存在 → 读取 active-world.txt → 读取 world/active-branch.txt → 加载文档
 */
public class DataManager {

    private static final Logger log = LoggerFactory.getLogger(DataManager.class);

    private final Path dataRoot;
    private final Map<String, DataDocument> documents = new LinkedHashMap<>();
    private String currentWorld = "default";
    private String currentBranch = "main";

    public DataManager(Path dataRoot) {
        this.dataRoot = dataRoot.toAbsolutePath();
        try {
            if (!Files.isDirectory(dataRoot)) {
                initDefault();
            } else {
                autoLoad();
            }
        } catch (IOException e) {
            log.error("Failed to initialize DataManager: {}", e.getMessage());
        }
    }

    // ==================== auto init / load ====================

    private void initDefault() throws IOException {
        log.info("data/ not found, creating default world");
        Files.createDirectories(dataRoot);
        Files.writeString(dataRoot.resolve("active-world.txt"), "default", StandardCharsets.UTF_8);
        initWorld("default");
        this.currentWorld = "default";
        this.currentBranch = "main";
        reload();
    }

    private void autoLoad() throws IOException {
        Path awFile = dataRoot.resolve("active-world.txt");
        if (Files.exists(awFile)) {
            this.currentWorld = Files.readString(awFile, StandardCharsets.UTF_8).trim();
        }
        Path worldDir = dataRoot.resolve("worlds").resolve(currentWorld);
        if (!Files.isDirectory(worldDir)) {
            log.warn("World '{}' not found, falling back to 'default'", currentWorld);
            this.currentWorld = "default";
            if (!Files.isDirectory(dataRoot.resolve("worlds").resolve("default"))) {
                initWorld("default");
            }
            Files.writeString(awFile, "default", StandardCharsets.UTF_8);
        }
        Path abFile = worldDir().resolve("active-branch.txt");
        if (Files.exists(abFile)) {
            this.currentBranch = Files.readString(abFile, StandardCharsets.UTF_8).trim();
        }
        reload();
    }

    // ==================== init ====================

    /** 显式初始化默认世界（CLI /data init）。 */
    public void init() throws IOException {
        if (!Files.isDirectory(dataRoot)) {
            Files.createDirectories(dataRoot);
            Files.writeString(dataRoot.resolve("active-world.txt"), "default", StandardCharsets.UTF_8);
        }
        initWorld("default");
        this.currentWorld = "default";
        this.currentBranch = "main";
        reload();
    }

    private void initWorld(String worldName) throws IOException {
        Path worldDir = dataRoot.resolve("worlds").resolve(worldName);
        Path branchDir = worldDir.resolve("branches").resolve("main");

        for (String sub : List.of("always", "entities", "turns",
                "patches/pending", "patches/accepted", "patches/rejected")) {
            Files.createDirectories(branchDir.resolve(sub));
        }

        Files.writeString(worldDir.resolve("active-branch.txt"), "main", StandardCharsets.UTF_8);

        writeDocAt(branchDir, "always/worldview.md",
                "id: always.worldview\n" +
                "type: always\n" +
                "name: 世界观\n" +
                "tags: [世界观]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 世界观\n\n这是一个示例世界观。\n\n## 世界设定\n\n待补充。\n");

        writeDocAt(branchDir, "always/rules.md",
                "id: always.rules\n" +
                "type: always\n" +
                "name: 规则\n" +
                "tags: [规则]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 规则\n\n文游基本规则。\n\n## 行动规则\n\n待补充。\n");

        writeDocAt(branchDir, "always/current-state.md",
                "id: always.current-state\n" +
                "type: always\n" +
                "name: 当前状态\n" +
                "tags: [状态]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 当前状态\n\n当前世界状态的简要描述。\n\n## 近期事件\n\n暂无。\n");

        writeDocAt(branchDir, "entities/example-player.md",
                "id: entity.example-player\n" +
                "type: entity\n" +
                "role: player\n" +
                "name: 示例玩家\n" +
                "aliases: []\n" +
                "tags: [玩家, 测试]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 示例玩家\n\n## 当前状态\n\n这是一个玩家资料示例。\n\n## 已知行动\n\n暂无。\n");

        writeDocAt(branchDir, "entities/example-character.md",
                "id: entity.example-character\n" +
                "type: entity\n" +
                "role: character\n" +
                "name: 示例人物\n" +
                "aliases: []\n" +
                "tags: [人物, 测试]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 示例人物\n\n## 当前状态\n\n这是一个人物资料示例。\n\n## 推演影响\n\n暂无。\n");

        writeDocAt(branchDir, "turns/turn-0001.md",
                "id: turn.0001\n" +
                "type: turn\n" +
                "name: 第1回合\n" +
                "tags: [回合]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 第1回合\n\n## 玩家行动\n\n暂无。\n\n## 推演结果\n\n暂无。\n");

        writeDocAt(branchDir, "branch.md",
                "id: branch.main\n" +
                "type: branch\n" +
                "name: 主分支\n" +
                "tags: [分支]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 主分支\n\n这是默认的世界线主分支。\n");

        log.info("Initialized world '{}' at {}", worldName, worldDir);
    }

    // ==================== world ====================

    public String getCurrentWorld() { return currentWorld; }
    public String getCurrentBranch() { return currentBranch; }

    public List<String> listWorlds() {
        Path worldsDir = dataRoot.resolve("worlds");
        if (!Files.isDirectory(worldsDir)) return List.of();
        try (Stream<Path> s = Files.list(worldsDir)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public void createWorld(String worldName) throws IOException {
        Path worldDir = dataRoot.resolve("worlds").resolve(worldName);
        if (Files.exists(worldDir)) {
            throw new IOException("World already exists: " + worldName);
        }
        initWorld(worldName);
        log.info("Created world '{}'", worldName);
    }

    public void switchWorld(String worldName) throws IOException {
        Path worldDir = dataRoot.resolve("worlds").resolve(worldName);
        if (!Files.isDirectory(worldDir)) {
            throw new IOException("World does not exist: " + worldName);
        }
        this.currentWorld = worldName;
        Files.writeString(dataRoot.resolve("active-world.txt"), worldName, StandardCharsets.UTF_8);

        Path abFile = worldDir.resolve("active-branch.txt");
        if (Files.exists(abFile)) {
            this.currentBranch = Files.readString(abFile, StandardCharsets.UTF_8).trim();
        } else {
            this.currentBranch = "main";
        }
        reload();
        log.info("Switched to world '{}', branch '{}'", currentWorld, currentBranch);
    }

    // ==================== branch ====================

    public List<String> listBranches() {
        Path bd = currentBranchesDir();
        if (!Files.isDirectory(bd)) return List.of();
        try (Stream<Path> s = Files.list(bd)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public void createBranch(String branchName, String fromBranch) throws IOException {
        Path bd = currentBranchesDir();
        Path fromDir = bd.resolve(fromBranch);
        Path toDir = bd.resolve(branchName);
        if (!Files.isDirectory(fromDir))
            throw new IOException("Source branch does not exist: " + fromBranch);
        if (Files.exists(toDir))
            throw new IOException("Branch already exists: " + branchName);
        copyDir(fromDir, toDir);
        log.info("Created branch '{}' from '{}' in world '{}'", branchName, fromBranch, currentWorld);
    }

    public void switchBranch(String branchName) throws IOException {
        Path bd = currentBranchesDir();
        if (!Files.isDirectory(bd.resolve(branchName)))
            throw new IOException("Branch does not exist: " + branchName);
        this.currentBranch = branchName;
        Files.writeString(worldDir().resolve("active-branch.txt"), branchName, StandardCharsets.UTF_8);
        reload();
        log.info("Switched to branch '{}' in world '{}'", branchName, currentWorld);
    }

    // ==================== load / reload ====================

    public void reload() throws IOException {
        documents.clear();
        Path bd = currentBranchDir();
        if (!Files.isDirectory(bd)) return;
        try (Stream<Path> files = Files.walk(bd)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(this::loadDocument);
        }
        log.info("Loaded {} documents from world='{}' branch='{}'", documents.size(), currentWorld, currentBranch);
    }

    private void loadDocument(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String relPath = currentBranchDir().relativize(file).toString();
            ParseResult parsed = parseFrontMatter(raw);
            DataDocument doc = parsed.frontMatter.isEmpty()
                    ? DataDocument.withoutFrontMatter(parsed.body, relPath)
                    : new DataDocument(parsed.frontMatter, parsed.body, relPath);
            documents.put(doc.id(), doc);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", file, e.getMessage());
        }
    }

    // ==================== access ====================

    public Collection<DataDocument> listAll() {
        return Collections.unmodifiableCollection(documents.values());
    }

    public List<DataDocument> listByType(String type) {
        return documents.values().stream().filter(d -> type.equals(d.type())).toList();
    }

    public DataDocument readById(String id) {
        return documents.get(id);
    }

    public int docCount() { return documents.size(); }

    public String getAlwaysContext() {
        StringBuilder sb = new StringBuilder();
        for (DataDocument doc : documents.values()) {
            if ("always".equals(doc.type())) sb.append(doc.fullContent()).append("\n\n");
        }
        return sb.toString();
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
            if (score > 0) {
                results.add(new DataSearchResult(doc.id(), doc.type(), doc.role(), doc.name(),
                        doc.rawPath(), buildSnippet(doc.body(), lower, 300), score));
            }
        }
        results.sort(Comparator.comparingDouble(DataSearchResult::score).reversed());
        return results.size() > maxResults ? results.subList(0, maxResults) : results;
    }

    // ==================== write ====================

    public void writeDoc(String relativePath, String content) throws IOException {
        writeDocAt(currentBranchDir(), relativePath, content);
    }

    private void writeDocAt(Path branchDir, String relativePath, String content) throws IOException {
        Path target = branchDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    // ==================== path helpers ====================

    private Path worldDir() { return dataRoot.resolve("worlds").resolve(currentWorld); }
    private Path currentBranchesDir() { return worldDir().resolve("branches"); }
    private Path currentBranchDir() { return currentBranchesDir().resolve(currentBranch); }

    // ==================== front matter parsing ====================

    static ParseResult parseFrontMatter(String raw) {
        if (raw == null || raw.isBlank()) return new ParseResult(Map.of(), "");
        String trimmed = raw.trim();
        if (!trimmed.startsWith("id:") && !trimmed.startsWith("type:") && !trimmed.startsWith("name:"))
            return new ParseResult(Map.of(), raw);

        int sep = trimmed.indexOf("-------------------");
        if (sep < 0) { sep = trimmed.indexOf("\n---\n"); if (sep < 0) sep = trimmed.indexOf("\n---"); }

        String fmPart, bodyPart;
        if (sep >= 0) {
            fmPart = trimmed.substring(0, sep);
            int bodyStart = trimmed.indexOf('\n', sep);
            if (bodyStart < 0) bodyStart = sep;
            bodyPart = trimmed.substring(bodyStart).trim();
            if (bodyPart.startsWith("---")) {
                int nl = bodyPart.indexOf('\n');
                bodyPart = nl >= 0 ? bodyPart.substring(nl + 1).trim() : "";
            } else if (bodyPart.startsWith("--")) {
                int nl = bodyPart.indexOf('\n');
                bodyPart = nl >= 0 ? bodyPart.substring(nl + 1).trim() : "";
            }
        } else { fmPart = trimmed; bodyPart = ""; }

        Map<String, String> fm = new LinkedHashMap<>();
        for (String line : fmPart.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon > 0) fm.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return new ParseResult(fm, bodyPart);
    }

    record ParseResult(Map<String, String> frontMatter, String body) {}

    private void copyDir(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(s -> {
                try {
                    Path d = target.resolve(source.relativize(s));
                    if (Files.isDirectory(s)) Files.createDirectories(d);
                    else Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        } catch (UncheckedIOException e) { throw e.getCause(); }
    }

    private int countMatches(String text, String keyword) {
        if (keyword.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) { count++; idx += keyword.length(); }
        return count;
    }

    private String buildSnippet(String body, String lowerKeyword, int maxLen) {
        if (body.isEmpty() || lowerKeyword.isEmpty())
            return body.length() > maxLen ? body.substring(0, maxLen) + "..." : body;
        int pos = body.toLowerCase().indexOf(lowerKeyword);
        if (pos < 0) return body.length() > maxLen ? body.substring(0, maxLen) + "..." : body;
        int start = Math.max(0, pos - maxLen / 3);
        int end = Math.min(body.length(), pos + maxLen * 2 / 3);
        StringBuilder sb = new StringBuilder();
        if (start > 0) sb.append("...");
        sb.append(body, start, end);
        if (end < body.length()) sb.append("...");
        return sb.toString();
    }
}
