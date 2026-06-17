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
 * 支持：
 * - 初始化默认世界
 * - 世界线分支（完整复制）
 * - YAML front matter 解析
 * - 关键词搜索
 * - 文档读写
 */
public class DataManager {

    private static final Logger log = LoggerFactory.getLogger(DataManager.class);

    private final Path dataRoot;
    private final Map<String, DataDocument> documents = new LinkedHashMap<>();
    private String currentWorld = "default";
    private String currentBranch = "main";

    public DataManager(Path dataRoot) {
        this.dataRoot = dataRoot.toAbsolutePath();
    }

    // ==================== init ====================

    /**
     * 初始化默认世界目录结构和示例文件。
     */
    public void init() throws IOException {
        Path worldDir = dataRoot.resolve("worlds").resolve("default");
        Path branchDir = worldDir.resolve("branches").resolve("main");

        // 目录
        for (String sub : List.of("always", "entities", "turns",
                "patches/pending", "patches/accepted", "patches/rejected")) {
            Files.createDirectories(branchDir.resolve(sub));
        }

        // active-branch.txt
        Files.writeString(worldDir.resolve("active-branch.txt"), "main", StandardCharsets.UTF_8);

        // always/ 文件
        writeDoc(branchDir, "always/worldview.md",
                "id: always.worldview\n" +
                "type: always\n" +
                "name: 世界观\n" +
                "tags: [世界观]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 世界观\n\n这是一个示例世界观。\n\n## 世界设定\n\n待补充。\n");

        writeDoc(branchDir, "always/rules.md",
                "id: always.rules\n" +
                "type: always\n" +
                "name: 规则\n" +
                "tags: [规则]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 规则\n\n文游基本规则。\n\n## 行动规则\n\n待补充。\n");

        writeDoc(branchDir, "always/current-state.md",
                "id: always.current-state\n" +
                "type: always\n" +
                "name: 当前状态\n" +
                "tags: [状态]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 当前状态\n\n当前世界状态的简要描述。\n\n## 近期事件\n\n暂无。\n");

        // entities/
        writeDoc(branchDir, "entities/example-player.md",
                "id: entity.example-player\n" +
                "type: entity\n" +
                "role: player\n" +
                "name: 示例玩家\n" +
                "aliases: []\n" +
                "tags: [玩家, 测试]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 示例玩家\n\n## 当前状态\n\n这是一个玩家资料示例。\n\n## 已知行动\n\n暂无。\n");

        writeDoc(branchDir, "entities/example-character.md",
                "id: entity.example-character\n" +
                "type: entity\n" +
                "role: character\n" +
                "name: 示例人物\n" +
                "aliases: []\n" +
                "tags: [人物, 测试]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 示例人物\n\n## 当前状态\n\n这是一个人物资料示例。\n\n## 推演影响\n\n暂无。\n");

        // turns/
        writeDoc(branchDir, "turns/turn-0001.md",
                "id: turn.0001\n" +
                "type: turn\n" +
                "name: 第1回合\n" +
                "tags: [回合]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 第1回合\n\n## 玩家行动\n\n暂无。\n\n## 推演结果\n\n暂无。\n");

        // branch.md
        writeDoc(branchDir, "branch.md",
                "id: branch.main\n" +
                "type: branch\n" +
                "name: 主分支\n" +
                "tags: [分支]\n" +
                "updated: 2026-06-18\n" +
                "-------------------\n\n" +
                "# 主分支\n\n这是默认的世界线主分支。\n");

        log.info("Initialized data world 'default' at {}", worldDir);

        // 加载
        this.currentWorld = "default";
        this.currentBranch = "main";
        reload();
    }

    // ==================== branch ====================

    public String getCurrentWorld() { return currentWorld; }
    public String getCurrentBranch() { return currentBranch; }

    public List<String> listBranches() {
        Path branchesDir = branchesDir();
        if (!Files.isDirectory(branchesDir)) return List.of();
        try (Stream<Path> s = Files.list(branchesDir)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list branches: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 fromBranch 完整复制创建新分支。
     */
    public void createBranch(String branchName, String fromBranch) throws IOException {
        Path branchesDir = branchesDir();
        Path fromDir = branchesDir.resolve(fromBranch);
        Path toDir = branchesDir.resolve(branchName);

        if (!Files.isDirectory(fromDir)) {
            throw new IOException("Source branch does not exist: " + fromBranch);
        }
        if (Files.exists(toDir)) {
            throw new IOException("Branch already exists: " + branchName);
        }

        copyDir(fromDir, toDir);
        log.info("Created branch '{}' from '{}'", branchName, fromBranch);
    }

    /**
     * 切换当前分支。
     */
    public void switchBranch(String branchName) throws IOException {
        Path branchesDir = branchesDir();
        Path targetDir = branchesDir.resolve(branchName);
        if (!Files.isDirectory(targetDir)) {
            throw new IOException("Branch does not exist: " + branchName);
        }
        Path worldDir = dataRoot.resolve("worlds").resolve(currentWorld);
        Files.writeString(worldDir.resolve("active-branch.txt"), branchName, StandardCharsets.UTF_8);
        this.currentBranch = branchName;
        reload();
        log.info("Switched to branch '{}'", branchName);
    }

    // ==================== load / reload ====================

    public void load(String world) throws IOException {
        this.currentWorld = world;
        Path worldDir = dataRoot.resolve("worlds").resolve(world);
        if (!Files.isDirectory(worldDir)) {
            throw new IOException("World does not exist: " + world + ". Run /data init first.");
        }
        Path activeFile = worldDir.resolve("active-branch.txt");
        if (Files.exists(activeFile)) {
            this.currentBranch = Files.readString(activeFile, StandardCharsets.UTF_8).trim();
        }
        reload();
    }

    public void reload() throws IOException {
        documents.clear();
        Path branchDir = branchDir();
        if (!Files.isDirectory(branchDir)) return;

        try (Stream<Path> files = Files.walk(branchDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(this::loadDocument);
        }
        log.info("Loaded {} documents from branch '{}'", documents.size(), currentBranch);
    }

    private void loadDocument(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            Path branchDir = branchDir();
            String relPath = branchDir.relativize(file).toString();

            ParseResult parsed = parseFrontMatter(raw);
            DataDocument doc;
            if (parsed.frontMatter.isEmpty()) {
                doc = DataDocument.withoutFrontMatter(parsed.body, relPath);
            } else {
                doc = new DataDocument(parsed.frontMatter, parsed.body, relPath);
            }
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
        return documents.values().stream()
                .filter(d -> type.equals(d.type()))
                .toList();
    }

    public DataDocument readById(String id) {
        return documents.get(id);
    }

    /**
     * 获取 always/ 下所有文档的拼接内容，用于每轮推演的固定输入。
     */
    public String getAlwaysContext() {
        StringBuilder sb = new StringBuilder();
        for (DataDocument doc : documents.values()) {
            if ("always".equals(doc.type())) {
                sb.append(doc.fullContent()).append("\n\n");
            }
        }
        return sb.toString();
    }

    // ==================== search ====================

    /**
     * 在当前分支中搜索关键词。
     * score = name 命中 * 5 + aliases 命中 * 4 + tags 命中 * 3 + 正文命中
     */
    public List<DataSearchResult> search(String keyword, int maxResults) {
        String lower = keyword.toLowerCase();
        List<DataSearchResult> results = new ArrayList<>();

        for (DataDocument doc : documents.values()) {
            double score = 0;

            if (doc.name().toLowerCase().contains(lower)) score += 5;

            for (String alias : doc.aliases()) {
                if (alias.toLowerCase().contains(lower)) { score += 4; break; }
            }

            for (String tag : doc.tags()) {
                if (tag.toLowerCase().contains(lower)) { score += 3; break; }
            }

            int bodyHits = countMatches(doc.body().toLowerCase(), lower);
            score += bodyHits;

            if (score > 0) {
                String snippet = buildSnippet(doc.body(), lower, 300);
                results.add(new DataSearchResult(
                        doc.id(), doc.type(), doc.role(), doc.name(),
                        doc.rawPath(), snippet, score));
            }
        }

        results.sort(Comparator.comparingDouble(DataSearchResult::score).reversed());
        if (results.size() > maxResults) {
            return results.subList(0, maxResults);
        }
        return results;
    }

    // ==================== write ====================

    /**
     * 写入或覆盖文档。
     * @param relativePath 相对于 branch 根目录的路径
     */
    public void writeDoc(String relativePath, String content) throws IOException {
        Path branchDir = branchDir();
        writeDoc(branchDir, relativePath, content);
    }

    private void writeDoc(Path branchDir, String relativePath, String content) throws IOException {
        Path target = branchDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    // ==================== internal ====================

    private Path branchesDir() {
        return dataRoot.resolve("worlds").resolve(currentWorld).resolve("branches");
    }

    private Path branchDir() {
        return branchesDir().resolve(currentBranch);
    }

    /**
     * 解析 YAML front matter。
     * 格式：
     * key: value
     * ---(三个以上的减号)---
     * body
     */
    static ParseResult parseFrontMatter(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult(Map.of(), "");
        }

        String trimmed = raw.trim();
        if (!trimmed.startsWith("id:") && !trimmed.startsWith("type:") && !trimmed.startsWith("name:")) {
            // 无 front matter
            return new ParseResult(Map.of(), raw);
        }

        // 查找分隔符 -------------------
        int sep = trimmed.indexOf("-------------------");
        if (sep < 0) {
            // 尝试 --- 作为分隔符
            sep = trimmed.indexOf("\n---\n");
            if (sep < 0) sep = trimmed.indexOf("\n---");
        }

        String fmPart;
        String bodyPart;
        if (sep >= 0) {
            fmPart = trimmed.substring(0, sep);
            // 跳过分隔符行
            int bodyStart = trimmed.indexOf('\n', sep);
            if (bodyStart < 0) bodyStart = sep;
            bodyPart = trimmed.substring(bodyStart).trim();
            // 跳过分隔符残骸
            if (bodyPart.startsWith("---")) {
                int nl = bodyPart.indexOf('\n');
                bodyPart = nl >= 0 ? bodyPart.substring(nl + 1).trim() : "";
            } else if (bodyPart.startsWith("--")) {
                int nl = bodyPart.indexOf('\n');
                bodyPart = nl >= 0 ? bodyPart.substring(nl + 1).trim() : "";
            }
        } else {
            // 整个内容都算 front matter（无正文）
            fmPart = trimmed;
            bodyPart = "";
        }

        Map<String, String> fm = new LinkedHashMap<>();
        for (String line : fmPart.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                fm.put(key, value);
            }
        }

        return new ParseResult(fm, bodyPart);
    }

    record ParseResult(Map<String, String> frontMatter, String body) {}

    private void copyDir(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(s -> {
                try {
                    Path d = target.resolve(source.relativize(s));
                    if (Files.isDirectory(s)) {
                        Files.createDirectories(d);
                    } else {
                        Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private int countMatches(String text, String keyword) {
        if (keyword.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }

    private String buildSnippet(String body, String lowerKeyword, int maxLen) {
        if (body.isEmpty() || lowerKeyword.isEmpty()) {
            return body.length() > maxLen ? body.substring(0, maxLen) + "..." : body;
        }
        int pos = body.toLowerCase().indexOf(lowerKeyword);
        if (pos < 0) {
            return body.length() > maxLen ? body.substring(0, maxLen) + "..." : body;
        }
        int start = Math.max(0, pos - maxLen / 3);
        int end = Math.min(body.length(), pos + maxLen * 2 / 3);
        StringBuilder sb = new StringBuilder();
        if (start > 0) sb.append("...");
        sb.append(body, start, end);
        if (end < body.length()) sb.append("...");
        return sb.toString();
    }
}
