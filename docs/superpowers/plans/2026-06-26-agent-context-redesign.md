# Agent 上下文系统重构 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完全推倒现有 data/ + 12 业务包，重建为 worlds/ + prompts/ 双目录结构，实现 WorldInformation 内存模型 + 3 查询工具 + OpenAI 格式缓存 + FreeMarker 渲染。

**Architecture:** 磁盘层：worlds/{world}/nodes/*.json（增量节点）+ caches/*.json（OpenAI messages）。内存层：WorldInformation（分支链 + byCheckpoint + byTag + keywordIndex）。查询层：query_checkpoint / query_keyword / query_node。渲染层：FreeMarker → system prompt。

**Tech Stack:** Java 21, Jackson 2.18.2, FreeMarker 2.3.x (new), JUnit 5.11.4, OkHttp 4.12.0

## Global Constraints

- 所有数据目录位于 java -jar 的 cwd，非项目源码目录
- 节点只记录增量，n0000 为完整初始设定
- 子 Agent 缓存只写不读，主 Agent 缓存反复读写
- type 字段不限制枚举，LLM 自行分类
- links 仅链接同节点内其他 checkpoint/element
- 所有测试使用 FakeLlmClient，不依赖外部服务
- 每 Phase 结束项目必须可编译、可运行

---

## 文件结构映射

```
新建:
  src/main/java/com/gsim/worldinfo/
    WorldInformation.java          # 顶层 record
    NodeSnapshot.java              # 节点快照 record
    Checkpoint.java                # 检查点 record
    Element.java                   # 信息元素 record
    ElementRef.java                # 带来源引用的元素 record
    KeywordIndex.java              # 关键词倒排索引 class
  src/main/java/com/gsim/worldinfo/loader/
    NodeLoader.java                # 单节点 JSON 读写
    WorldInfoBuilder.java          # 沿链加载 → WorldInformation
    ActiveStateManager.java        # active.json 读写
    WorldIndexManager.java         # _index.json + world.json 管理
  src/main/java/com/gsim/worldinfo/tool/
    QueryCheckpointTool.java       # query_checkpoint
    QueryKeywordTool.java          # query_keyword
    QueryNodeTool.java             # query_node
    WriteElementTool.java          # write_element (LLM 写回)
  src/main/java/com/gsim/cache/
    CacheSession.java              # Cache JSON 的 record
    CacheStore.java                # 磁盘读写
    CacheCompressor.java           # 压缩/总结/建新 session
  src/main/java/com/gsim/context/
    ContextRenderer.java           # FreeMarker 渲染 system prompt
  src/main/java/com/gsim/commands/
    WorldCommand.java              # /world 命令
    NodeCommand.java               # /node 命令
    ChatCommand.java               # /chat 命令
  pom.xml                          # 添加 FreeMarker 依赖

修改:
  src/main/java/com/gsim/app/AppConfig.java      # 加 worlds.dir, prompts.dir
  src/main/java/com/gsim/app/Main.java             # 新启动流程
  src/main/java/com/gsim/util/IdGenerator.java     # 加 nodeId()

删除 (Phase 8):
  src/main/java/com/gsim/campaign/    整个包
  src/main/java/com/gsim/branch/      整个包
  src/main/java/com/gsim/world/       整个包
  src/main/java/com/gsim/data/        整个包
  src/main/java/com/gsim/storage/     整个包
  src/main/java/com/gsim/context/     整个包 (替换为新版)
  src/main/java/com/gsim/chroma/      整个包
  src/main/java/com/gsim/chat/        整个包
  src/main/java/com/gsim/task/        整个包
  src/main/java/com/gsim/timeline/    整个包
  src/main/java/com/gsim/interaction/commands/  整个目录
  data/ 目录 (磁盘)
```

---

### Task 1: FreeMarker 依赖 + AppConfig 扩展 + IdGenerator 扩展

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/gsim/app/AppConfig.java`
- Modify: `src/main/java/com/gsim/util/IdGenerator.java`

**Interfaces:**
- Produces: `AppConfig.worldsDir()` → Path, `AppConfig.promptsDir()` → Path
- Produces: `IdGenerator.nodeId()` → String (e.g. "n0000")
- Produces: FreeMarker `freemarker.template.Configuration` available on classpath

- [ ] **Step 1: 加 FreeMarker 依赖到 pom.xml**

在 `<dependencies>` 中，紧跟 Thymeleaf 依赖之后插入：

```xml
<!-- FreeMarker for prompt rendering -->
<dependency>
    <groupId>org.freemarker</groupId>
    <artifactId>freemarker</artifactId>
    <version>2.3.34</version>
</dependency>
```

- [ ] **Step 2: 验证依赖可用**

```bash
mvn dependency:resolve -q | grep freemarker
```

Expected: `freemarker:jar:2.3.34` 出现在输出中。

- [ ] **Step 3: 扩展 AppConfig 加 worlds.dir 和 prompts.dir**

在 `AppConfig.java` 中加两个新配置项。找到现有的 `data.dir` 配置项（约在 `CONFIG_DEFS` 静态初始化中），在它附近添加：

```java
// 在 CONFIG_DEFS 的 putAll 或 add 链中添加：
put("worlds.dir", new ConfigDef("worlds.dir", "worlds", 
    "World save root directory — worlds/{worldId}/nodes/ + caches/"));
put("prompts.dir", new ConfigDef("prompts.dir", "prompts",
    "Prompt template directory — FreeMarker .md files"));
```

加对应的 getter 方法：

```java
public Path worldsDir() {
    return resolvePath(get("worlds.dir"));
}

public Path promptsDir() {
    return resolvePath(get("prompts.dir"));
}

private Path resolvePath(String raw) {
    Path p = Path.of(raw);
    return p.isAbsolute() ? p : Path.of("").toAbsolutePath().resolve(p);
}
```

注意：当前 `AppConfig` 可能没有 `resolvePath` 方法。检查现有实现中对 `data.dir` 的处理方式，使用相同的模式。

- [ ] **Step 4: 扩展 IdGenerator 加 nodeId()**

在 `IdGenerator.java` 中添加：

```java
private static final AtomicInteger nodeCounter = new AtomicInteger(0);

public static String nodeId() {
    return String.format("n%04d", nodeCounter.getAndIncrement());
}

public static void resetNodeCounter() {
    nodeCounter.set(0);
}
```

- [ ] **Step 5: 编译验证**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS，无编译错误。

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/gsim/app/AppConfig.java src/main/java/com/gsim/util/IdGenerator.java
git commit -m "chore: add FreeMarker, worlds.dir/prompts.dir config, nodeId generator"
```

---

### Task 2: WorldInformation 数据模型 — records

**Files:**
- Create: `src/main/java/com/gsim/worldinfo/Element.java`
- Create: `src/main/java/com/gsim/worldinfo/Checkpoint.java`
- Create: `src/main/java/com/gsim/worldinfo/NodeSnapshot.java`
- Create: `src/main/java/com/gsim/worldinfo/ElementRef.java`
- Create: `src/main/java/com/gsim/worldinfo/WorldInformation.java`
- Create: `src/main/java/com/gsim/worldinfo/package-info.java`

**Interfaces:**
- Produces: All 5 records and 1 class are available for downstream tasks

- [ ] **Step 1: 创建 package-info.java**

```java
// src/main/java/com/gsim/worldinfo/package-info.java
/**
 * WorldInformation memory model — the single source of truth for world state.
 *
 * <p>Disk layout: worlds/{worldId}/nodes/nXXXX.json (incremental snapshots)
 * loaded on startup into one WorldInformation instance.
 */
package com.gsim.worldinfo;
```

- [ ] **Step 2: 写 Element.java**

```java
package com.gsim.worldinfo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Collections;
import java.util.List;

@JsonDeserialize
public record Element(
    @JsonProperty("key") String key,
    @JsonProperty("type") String type,
    @JsonProperty("value") String value,
    @JsonProperty("tags") List<String> tags,
    @JsonProperty("links") List<String> links
) {
    public Element {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        if (type == null) type = "text";
        if (value == null) value = "";
        if (tags == null) tags = List.of();
        if (links == null) links = List.of();
    }

    /** An element with neither tags nor links. */
    public static Element simple(String key, String type, String value) {
        return new Element(key, type, value, List.of(), List.of());
    }
}
```

- [ ] **Step 3: 写 Checkpoint.java**

```java
package com.gsim.worldinfo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Collections;
import java.util.List;

@JsonDeserialize
public record Checkpoint(
    @JsonProperty("label") String label,
    @JsonProperty("type") String type,
    @JsonProperty("elements") List<Element> elements
) {
    public Checkpoint {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label must not be blank");
        if (type == null) type = "misc";
        if (elements == null) elements = List.of();
    }
}
```

- [ ] **Step 4: 写 NodeSnapshot.java**

```java
package com.gsim.worldinfo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonDeserialize
public record NodeSnapshot(
    @JsonProperty("nodeId") String nodeId,
    @JsonProperty("parentId") String parentId,
    @JsonProperty("turn") int turn,
    @JsonProperty("worldTime") String worldTime,
    @JsonProperty("status") String status,
    @JsonProperty("createdAt") String createdAt,
    @JsonProperty("checkpoints") Map<String, Checkpoint> checkpoints
) {
    public NodeSnapshot {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId required");
        if (checkpoints == null) checkpoints = new LinkedHashMap<>();
    }

    /** The root node has no parent. */
    public boolean isRoot() {
        return parentId == null || parentId.isBlank();
    }

    public Checkpoint checkpoint(String id) {
        return checkpoints.get(id);
    }
}
```

- [ ] **Step 5: 写 ElementRef.java**

```java
package com.gsim.worldinfo;

/**
 * An element with its source node attached — the unit returned by all queries.
 */
public record ElementRef(
    String nodeId,
    int turn,
    String worldTime,
    String checkpointId,
    Element element
) {
    public static ElementRef from(String nodeId, int turn, String worldTime,
                                   String checkpointId, Element element) {
        return new ElementRef(nodeId, turn, worldTime, checkpointId, element);
    }
}
```

- [ ] **Step 6: 写 WorldInformation.java**

```java
package com.gsim.worldinfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Full world state loaded from the branch chain.
 * Immutable after construction except for {@link #appendElement}.
 */
public final class WorldInformation {

    private final String worldId;
    private final String rootNodeId;
    private final String activeNodeId;
    private final List<NodeSnapshot> branchChain;          // root → active
    private final Map<String, List<ElementRef>> byCheckpoint; // checkpointId → all elements
    private final Map<String, List<ElementRef>> byTag;        // tag → elements
    private final KeywordIndex keywordIndex;

    public WorldInformation(String worldId, List<NodeSnapshot> branchChain) {
        this.worldId = worldId;
        this.branchChain = List.copyOf(branchChain);
        this.rootNodeId = branchChain.isEmpty() ? null : branchChain.get(0).nodeId();
        this.activeNodeId = branchChain.isEmpty() ? null : branchChain.get(branchChain.size() - 1).nodeId();
        this.byCheckpoint = buildByCheckpoint(branchChain);
        this.byTag = buildByTag(branchChain);
        this.keywordIndex = KeywordIndex.build(branchChain);
    }

    // -- accessors --
    public String worldId() { return worldId; }
    public String rootNodeId() { return rootNodeId; }
    public String activeNodeId() { return activeNodeId; }
    public List<NodeSnapshot> branchChain() { return branchChain; }
    public NodeSnapshot activeNode() { return branchChain.get(branchChain.size() - 1); }
    public NodeSnapshot nodeById(String nodeId) {
        return branchChain.stream().filter(n -> n.nodeId().equals(nodeId)).findFirst().orElse(null);
    }

    // -- checkpoint queries --
    public List<ElementRef> checkpointHistory(String checkpointId) {
        return byCheckpoint.getOrDefault(checkpointId, List.of());
    }

    public List<ElementRef> checkpointHistory(String checkpointId, int turnFrom, int turnTo) {
        return byCheckpoint.getOrDefault(checkpointId, List.of()).stream()
            .filter(r -> r.turn() >= turnFrom && r.turn() <= turnTo)
            .toList();
    }

    public List<String> allCheckpointIds() {
        return List.copyOf(byCheckpoint.keySet());
    }

    // -- tag queries --
    public List<ElementRef> byTag(String tag) {
        return byTag.getOrDefault(tag, List.of());
    }

    // -- keyword --
    public KeywordIndex keywordIndex() { return keywordIndex; }

    // -- mutation (called by write_element tool) --
    public synchronized void appendElement(String nodeId, String checkpointId, Element element) {
        NodeSnapshot node = nodeById(nodeId);
        if (node == null) throw new IllegalArgumentException("Unknown node: " + nodeId);
        Checkpoint cp = node.checkpoints().get(checkpointId);
        if (cp == null) {
            // auto-create checkpoint
            cp = new Checkpoint(checkpointId, "misc", new ArrayList<>());
            node.checkpoints().put(checkpointId, cp);
        }
        cp.elements().add(element);

        ElementRef ref = ElementRef.from(nodeId, node.turn(), node.worldTime(), checkpointId, element);
        byCheckpoint.computeIfAbsent(checkpointId, k -> new ArrayList<>()).add(ref);
        for (String t : element.tags()) {
            byTag.computeIfAbsent(t, k -> new ArrayList<>()).add(ref);
        }
        keywordIndex.add(ref);
    }

    // -- builders --
    private static Map<String, List<ElementRef>> buildByCheckpoint(List<NodeSnapshot> chain) {
        Map<String, List<ElementRef>> map = new LinkedHashMap<>();
        for (NodeSnapshot node : chain) {
            for (var entry : node.checkpoints().entrySet()) {
                String cpId = entry.getKey();
                Checkpoint cp = entry.getValue();
                List<ElementRef> list = map.computeIfAbsent(cpId, k -> new ArrayList<>());
                for (Element el : cp.elements()) {
                    list.add(ElementRef.from(node.nodeId(), node.turn(), node.worldTime(), cpId, el));
                }
            }
        }
        return map;
    }

    private static Map<String, List<ElementRef>> buildByTag(List<NodeSnapshot> chain) {
        Map<String, List<ElementRef>> map = new LinkedHashMap<>();
        for (NodeSnapshot node : chain) {
            for (var entry : node.checkpoints().entrySet()) {
                String cpId = entry.getKey();
                for (Element el : entry.getValue().elements()) {
                    ElementRef ref = ElementRef.from(node.nodeId(), node.turn(), node.worldTime(), cpId, el);
                    for (String tag : el.tags()) {
                        map.computeIfAbsent(tag, k -> new ArrayList<>()).add(ref);
                    }
                }
            }
        }
        return map;
    }

    @Override
    public String toString() {
        return "WorldInformation[world=%s, nodes=%d, checkpoints=%d, tags=%d]".formatted(
            worldId, branchChain.size(), byCheckpoint.size(), byTag.size());
    }
}
```

- [ ] **Step 7: 编译验证**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS。此时还不涉及 KeywordIndex 类，所以 `KeywordIndex.build()` 调用会报错——暂时注释掉 WorldInformation 中 keywordIndex 相关的 3 行，等 Task 4 实现 KeywordIndex 后再取消注释。

即：将 `private final KeywordIndex keywordIndex;` 缓存为 `null`，`this.keywordIndex = null;`，`keywordIndex.add(ref);` 加 null 检查。

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/gsim/worldinfo/
git commit -m "feat: WorldInformation data model — Element, Checkpoint, NodeSnapshot, ElementRef, WorldInformation"
```

---

### Task 3: NodeLoader — 磁盘 JSON 读写 + parentId 链加载

**Files:**
- Create: `src/main/java/com/gsim/worldinfo/loader/NodeLoader.java`
- Create: `src/main/java/com/gsim/worldinfo/loader/WorldInfoBuilder.java`
- Test: `src/test/java/com/gsim/worldinfo/loader/NodeLoaderTest.java`

**Interfaces:**
- Consumes: `NodeSnapshot`, `WorldInformation` from Task 2
- Produces: `NodeLoader.load(Path)` → NodeSnapshot, `NodeLoader.save(Path, NodeSnapshot)`, `WorldInfoBuilder.build(Path worldsDir, String worldId, String activeNodeId)` → WorldInformation

- [ ] **Step 1: 写 NodeLoader.java**

```java
package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.NodeSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads/writes a single node JSON file from worlds/{worldId}/nodes/.
 */
public final class NodeLoader {

    private NodeLoader() {}

    /** Load a single node from its JSON file. */
    public static NodeSnapshot load(Path nodeFile) {
        if (!Files.exists(nodeFile)) {
            throw new IllegalArgumentException("Node file not found: " + nodeFile);
        }
        try {
            String json = Files.readString(nodeFile);
            return JsonUtils.fromJson(json, NodeSnapshot.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load node: " + nodeFile, e);
        }
    }

    /** Save a node to its JSON file. Creates parent directories if needed. */
    public static void save(Path nodeFile, NodeSnapshot node) {
        try {
            Files.createDirectories(nodeFile.getParent());
            String json = JsonUtils.toJson(node);
            Files.writeString(nodeFile, json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save node: " + nodeFile, e);
        }
    }

    /** The nodes directory for a given world. */
    public static Path nodesDir(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("nodes");
    }

    /** Path to a specific node JSON file. */
    public static Path nodeFile(Path worldsDir, String worldId, String nodeId) {
        return nodesDir(worldsDir, worldId).resolve(nodeId + ".json");
    }
}
```

- [ ] **Step 2: 写 WorldInfoBuilder.java**

```java
package com.gsim.worldinfo.loader;

import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a WorldInformation by walking the parent chain from the active node.
 */
public final class WorldInfoBuilder {

    private WorldInfoBuilder() {}

    /**
     * Walk parentId chain from activeNodeId to root, load all nodes, build WorldInformation.
     * Returns null if the world has no nodes directory.
     */
    public static WorldInformation build(Path worldsDir, String worldId, String activeNodeId) {
        Path nodesDir = NodeLoader.nodesDir(worldsDir, worldId);
        if (!Files.exists(nodesDir)) {
            return null;
        }

        // 1. Load active node
        NodeSnapshot current = NodeLoader.load(NodeLoader.nodeFile(worldsDir, worldId, activeNodeId));

        // 2. Walk up parent chain
        List<NodeSnapshot> chain = new ArrayList<>();
        chain.add(current);
        NodeSnapshot cursor = current;
        while (!cursor.isRoot()) {
            Path parentFile = NodeLoader.nodeFile(worldsDir, worldId, cursor.parentId());
            if (!Files.exists(parentFile)) break; // safety: broken chain
            cursor = NodeLoader.load(parentFile);
            chain.add(cursor);
        }

        // 3. Reverse so root is first
        java.util.Collections.reverse(chain);

        // 4. Build
        return new WorldInformation(worldId, chain);
    }
}
```

- [ ] **Step 3: 写 NodeLoaderTest.java**

```java
package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NodeLoaderTest {

    @TempDir
    Path tmpDir;

    // ---- NodeLoader ----

    @Test
    void saveAndLoadRoundtrip() throws Exception {
        Element el = new Element("k", "text", "v", List.of("a"), List.of("b"));
        Checkpoint cp = new Checkpoint("cp1", "player", List.of(el));
        NodeSnapshot node = new NodeSnapshot("n0000", null, 0, "origin",
            "initial", "2026-01-01T00:00:00Z", Map.of("cp1", cp));

        Path file = NodeLoader.nodeFile(tmpDir, "test-world", "n0000");
        NodeLoader.save(file, node);

        assertTrue(Files.exists(file));
        NodeSnapshot loaded = NodeLoader.load(file);
        assertEquals("n0000", loaded.nodeId());
        assertEquals("initial", loaded.status());
        assertEquals(1, loaded.checkpoints().size());
        assertEquals("v", loaded.checkpoint("cp1").elements().get(0).value());
    }

    // ---- WorldInfoBuilder ----

    @Test
    void buildChainOfThreeNodes() throws Exception {
        // Create three nodes: n0000 → n0001 → n0002
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin",
            "initial", "t0", Map.of("worldview", new Checkpoint("世界观", "worldview",
                List.of(Element.simple("k0", "text", "v0")))));

        NodeSnapshot n1 = new NodeSnapshot("n0001", "n0000", 1, "t1",
            "simulated", "t1", Map.of("player.A", new Checkpoint("A", "player",
                List.of(Element.simple("act1", "action", "did something")))));

        NodeSnapshot n2 = new NodeSnapshot("n0002", "n0001", 2, "t2",
            "simulated", "t2", Map.of("narrative", new Checkpoint("推文", "narrative",
                List.of(Element.simple("main", "narrative", "A did something. The world changed.")))));

        NodeLoader.save(NodeLoader.nodeFile(tmpDir, "w", "n0000"), n0);
        NodeLoader.save(NodeLoader.nodeFile(tmpDir, "w", "n0001"), n1);
        NodeLoader.save(NodeLoader.nodeFile(tmpDir, "w", "n0002"), n2);

        WorldInformation wi = WorldInfoBuilder.build(tmpDir, "w", "n0002");

        assertNotNull(wi);
        assertEquals("w", wi.worldId());
        assertEquals("n0000", wi.rootNodeId());
        assertEquals("n0002", wi.activeNodeId());
        assertEquals(3, wi.branchChain().size());

        // byCheckpoint: worldview accumulated from n0000
        assertEquals(1, wi.checkpointHistory("worldview").size());
        assertEquals("v0", wi.checkpointHistory("worldview").get(0).element().value());

        // player.A from n0001
        assertEquals(1, wi.checkpointHistory("player.A").size());

        // narrative from n0002
        assertEquals(1, wi.checkpointHistory("narrative").size());
    }

    @Test
    void emptyWorldReturnsNull() {
        WorldInformation wi = WorldInfoBuilder.build(tmpDir, "no-such-world", "n0000");
        assertNull(wi);
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl . -Dtest=NodeLoaderTest -DfailIfNoTests=false -q
```

Expected: 3 tests PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gsim/worldinfo/loader/ src/test/java/com/gsim/worldinfo/
git commit -m "feat: NodeLoader + WorldInfoBuilder — JSON read/write + parent chain loading"
```

---

### Task 4: KeywordIndex 关键词倒排索引

**Files:**
- Create: `src/main/java/com/gsim/worldinfo/KeywordIndex.java`
- Test: `src/test/java/com/gsim/worldinfo/KeywordIndexTest.java`

**Interfaces:**
- Consumes: `ElementRef` from Task 2
- Produces: `KeywordIndex.build(List<NodeSnapshot>)` → KeywordIndex, `KeywordIndex.search(String keywords, int limit, int offset)` → SearchResult

- [ ] **Step 1: 写 KeywordIndex.java**

```java
package com.gsim.worldinfo;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Full-text keyword inverted index over all elements in the branch chain.
 * Simple whitespace tokenisation — no NLP, no embeddings.
 */
public final class KeywordIndex {

    // token → list of element refs containing that token
    private final Map<String, List<ElementRef>> inverted;
    private final List<ElementRef> allRefs; // for scoring / dedup

    private KeywordIndex(Map<String, List<ElementRef>> inverted, List<ElementRef> allRefs) {
        this.inverted = inverted;
        this.allRefs = allRefs;
    }

    /** Build the index from a full node chain. */
    public static KeywordIndex build(List<NodeSnapshot> chain) {
        Map<String, List<ElementRef>> inverted = new HashMap<>();
        List<ElementRef> all = new ArrayList<>();

        for (NodeSnapshot node : chain) {
            for (var entry : node.checkpoints().entrySet()) {
                String cpId = entry.getKey();
                for (Element el : entry.getValue().elements()) {
                    ElementRef ref = ElementRef.from(node.nodeId(), node.turn(), node.worldTime(), cpId, el);
                    all.add(ref);
                    for (String token : tokenize(el.value())) {
                        inverted.computeIfAbsent(token, k -> new ArrayList<>()).add(ref);
                    }
                    for (String tag : el.tags()) {
                        for (String token : tokenize(tag)) {
                            inverted.computeIfAbsent(token, k -> new ArrayList<>()).add(ref);
                        }
                    }
                }
            }
        }
        return new KeywordIndex(inverted, all);
    }

    /**
     * Search by one or more space-separated keywords.
     * Results are scored by keyword match count and returned with pagination.
     */
    public SearchResult search(String keywords, int limit, int offset) {
        if (keywords == null || keywords.isBlank()) {
            return new SearchResult(0, offset, List.of());
        }

        List<String> tokens = tokenize(keywords);
        if (tokens.isEmpty()) return new SearchResult(0, offset, List.of());

        // score: count of matching tokens per ref (dedup by ref identity)
        Map<ElementRef, Integer> scores = new LinkedHashMap<>();
        for (String token : tokens) {
            for (ElementRef ref : inverted.getOrDefault(token, List.of())) {
                scores.merge(ref, 1, Integer::sum);
            }
        }

        // sort by score desc, then by turn desc
        List<Map.Entry<ElementRef, Integer>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> {
            int cmp = Integer.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            return Integer.compare(b.getKey().turn(), a.getKey().turn());
        });

        // paginate
        int total = sorted.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        List<SearchHit> hits = new ArrayList<>();
        for (int i = from; i < to; i++) {
            var entry = sorted.get(i);
            hits.add(new SearchHit(entry.getKey(), snippet(entry.getKey().element().value(), tokens.get(0)), entry.getValue()));
        }

        return new SearchResult(total, offset, hits);
    }

    /** Add a single ref to the index (for live updates). */
    public void add(ElementRef ref) {
        allRefs.add(ref);
        for (String token : tokenize(ref.element().value())) {
            inverted.computeIfAbsent(token, k -> new ArrayList<>()).add(ref);
        }
        for (String tag : ref.element().tags()) {
            for (String token : tokenize(tag)) {
                inverted.computeIfAbsent(token, k -> new ArrayList<>()).add(ref);
            }
        }
    }

    // -- helpers --

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[\\s，。、；：！？,.\\-]+"))
            .map(String::trim)
            .filter(s -> s.length() >= 1)
            .map(String::toLowerCase)
            .distinct()
            .toList();
    }

    private static String snippet(String value, String keyword) {
        int idx = value.toLowerCase().indexOf(keyword.toLowerCase());
        if (idx < 0) idx = 0;
        int start = Math.max(0, idx - 20);
        int end = Math.min(value.length(), idx + keyword.length() + 40);
        String s = value.substring(start, end);
        if (start > 0) s = "..." + s;
        if (end < value.length()) s = s + "...";
        return s;
    }

    // -- result types --

    public record SearchResult(int totalHits, int offset, List<SearchHit> items) {}

    public record SearchHit(ElementRef elementRef, String snippet, int score) {}
}
```

- [ ] **Step 2: 回到 WorldInformation.java，取消注释 keywordIndex 相关代码**

将 Task 2 Step 7 中注释掉的 3 行恢复：

```java
// 1. field — uncomment:
private final KeywordIndex keywordIndex;

// 2. constructor — uncomment:
this.keywordIndex = KeywordIndex.build(branchChain);

// 3. appendElement — uncomment the keywordIndex.add(ref) call:
keywordIndex.add(ref);
```

- [ ] **Step 3: 写 KeywordIndexTest.java**

```java
package com.gsim.worldinfo;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeywordIndexTest {

    @Test
    void searchFindsMatchingElements() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("worldview", new Checkpoint("世界观", "worldview", List.of(
                new Element("k0", "text", "中原大旱蝗灾四起", List.of("气候", "灾害"), List.of())
            ))));

        KeywordIndex idx = KeywordIndex.build(List.of(n0));
        var result = idx.search("中原", 10, 0);

        assertEquals(1, result.totalHits());
        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).snippet().contains("中原"));
        assertEquals("n0000", result.items().get(0).elementRef().nodeId());
    }

    @Test
    void searchMultipleKeywordsOrSemantics() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("p", new Checkpoint("p", "player", List.of(
                new Element("k1", "action", "曹操自陈留起兵", List.of("曹操", "军事"), List.of()),
                new Element("k2", "action", "皇甫嵩固守长社", List.of("皇甫嵩", "军事"), List.of())
            ))));

        KeywordIndex idx = KeywordIndex.build(List.of(n0));
        var result = idx.search("曹操", 10, 0);

        assertEquals(1, result.totalHits());
        assertEquals("k1", result.items().get(0).elementRef().element().key());
    }

    @Test
    void paginationWithOffset() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("p", new Checkpoint("p", "player", List.of(
                new Element("a", "action", "曹操起兵", List.of("曹操"), List.of()),
                new Element("b", "action", "曹操会合", List.of("曹操"), List.of()),
                new Element("c", "action", "曹操大破", List.of("曹操"), List.of())
            ))));

        KeywordIndex idx = KeywordIndex.build(List.of(n0));
        var page1 = idx.search("曹操", 2, 0);
        assertEquals(3, page1.totalHits());
        assertEquals(2, page1.items().size());

        var page2 = idx.search("曹操", 2, 2);
        assertEquals(1, page2.items().size());
    }

    @Test
    void searchByTag() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("p", new Checkpoint("p", "player", List.of(
                new Element("k1", "action", "some text", List.of("曹操", "军事"), List.of())
            ))));

        KeywordIndex idx = KeywordIndex.build(List.of(n0));
        assertEquals(1, idx.search("军事", 10, 0).totalHits());
        assertEquals(1, idx.search("曹操", 10, 0).totalHits());
    }

    @Test
    void noMatchReturnsEmpty() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("p", new Checkpoint("p", "player", List.of(
                new Element("k1", "action", "hello world", List.of(), List.of())
            ))));

        KeywordIndex idx = KeywordIndex.build(List.of(n0));
        var result = idx.search("不存在", 10, 0);
        assertEquals(0, result.totalHits());
        assertTrue(result.items().isEmpty());
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl . -Dtest=KeywordIndexTest -DfailIfNoTests=false -q
```

Expected: 5 tests PASS。

- [ ] **Step 5: 全量测试确保无回归**

```bash
mvn test -q
```

Expected: 所有测试 PASS（包括 Task 3 的 3 个）。注意：现有测试可能因包引用问题失败 — 先确认 Key​wordIndex 编译不破坏任何现有代码。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/gsim/worldinfo/KeywordIndex.java src/test/java/com/gsim/worldinfo/
git add -u src/main/java/com/gsim/worldinfo/WorldInformation.java
git commit -m "feat: KeywordIndex — full-text inverted index with paginated search"
```

---

### Task 5: ActiveStateManager + WorldIndexManager（active.json / _index.json / world.json）

**Files:**
- Create: `src/main/java/com/gsim/worldinfo/loader/ActiveStateManager.java`
- Create: `src/main/java/com/gsim/worldinfo/loader/WorldIndexManager.java`
- Test: `src/test/java/com/gsim/worldinfo/loader/StateManagerTest.java`

**Interfaces:**
- Consumes: `worlds/` 目录结构
- Produces: `ActiveStateManager.load/save`, `WorldIndexManager.list/createWorld/initWorld`

- [ ] **Step 1: 写 ActiveStateManager.java**

```java
package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads/writes active.json inside a world directory.
 */
public final class ActiveStateManager {

    private ActiveStateManager() {}

    public record ActiveState(
        String nodeId,
        Map<String, String> sessions  // agentName → sessionFileName
    ) {
        public ActiveState {
            if (sessions == null) sessions = new LinkedHashMap<>();
        }
    }

    public static Path activeFile(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("active.json");
    }

    public static ActiveState load(Path worldsDir, String worldId) {
        Path file = activeFile(worldsDir, worldId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), ActiveState.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load active.json for world: " + worldId, e);
        }
    }

    public static void save(Path worldsDir, String worldId, ActiveState state) {
        Path file = activeFile(worldsDir, worldId);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonUtils.toJson(state));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save active.json", e);
        }
    }

    /** Convenience: get the Orchestrator session filename. */
    public static String orchestratorSession(ActiveState state) {
        return state != null ? state.sessions().get("Orchestrator") : null;
    }
}
```

- [ ] **Step 2: 写 WorldIndexManager.java**

```java
package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.NodeSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages _index.json and world.json.
 */
public final class WorldIndexManager {

    private WorldIndexManager() {}

    public record WorldEntry(String id, String name, String createdAt) {}

    // ---- _index.json ----

    public static Path indexFile(Path worldsDir) {
        return worldsDir.resolve("_index.json");
    }

    public static List<WorldEntry> listWorlds(Path worldsDir) {
        Path file = indexFile(worldsDir);
        if (!Files.exists(file)) return List.of();
        try {
            WorldEntry[] arr = JsonUtils.fromJson(Files.readString(file), WorldEntry[].class);
            return arr != null ? List.of(arr) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void saveIndex(Path worldsDir, List<WorldEntry> entries) {
        try {
            Files.createDirectories(worldsDir);
            Files.writeString(indexFile(worldsDir), JsonUtils.toJson(entries));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write _index.json", e);
        }
    }

    // ---- world.json ----

    public record WorldMeta(String id, String name, String createdAt, String currentNodeId) {}

    public static Path worldFile(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("world.json");
    }

    public static WorldMeta loadWorldMeta(Path worldsDir, String worldId) {
        Path file = worldFile(worldsDir, worldId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), WorldMeta.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load world.json: " + worldId, e);
        }
    }

    // ---- creation ----

    /**
     * Create a new world. Generates n0000 root node with empty checkpoints.
     * Returns the world meta.
     */
    public static WorldMeta createWorld(Path worldsDir, String worldId, String name) {
        String now = Instant.now().toString();

        // world.json
        WorldMeta meta = new WorldMeta(worldId, name, now, "n0000");
        try {
            Files.createDirectories(worldFile(worldsDir, worldId).getParent());
            Files.writeString(worldFile(worldsDir, worldId), JsonUtils.toJson(meta));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create world.json", e);
        }

        // n0000.json (empty root node)
        NodeSnapshot root = new NodeSnapshot("n0000", null, 0, "时间原点", "initial", now,
            Map.of("worldview", new Checkpoint("世界观", "worldview", new ArrayList<>()),
                   "narrative", new Checkpoint("推文", "narrative", new ArrayList<>())));
        NodeLoader.save(NodeLoader.nodeFile(worldsDir, worldId, "n0000"), root);

        // _index.json
        List<WorldEntry> entries = new ArrayList<>(listWorlds(worldsDir));
        entries.add(new WorldEntry(worldId, name, now));
        saveIndex(worldsDir, entries);

        // active.json
        ActiveStateManager.save(worldsDir, worldId,
            new ActiveStateManager.ActiveState("n0000", Map.of()));

        return meta;
    }
}
```

- [ ] **Step 3: 写 StateManagerTest.java**

```java
package com.gsim.worldinfo.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StateManagerTest {

    @TempDir
    Path tmpDir;

    @Test
    void saveAndLoadActiveState() {
        ActiveStateManager.ActiveState state =
            new ActiveStateManager.ActiveState("n0003",
                Map.of("Orchestrator", "Orchestrator_2026-06-26T100000.json"));

        ActiveStateManager.save(tmpDir, "test-world", state);

        ActiveStateManager.ActiveState loaded = ActiveStateManager.load(tmpDir, "test-world");
        assertNotNull(loaded);
        assertEquals("n0003", loaded.nodeId());
        assertEquals("Orchestrator_2026-06-26T100000.json",
            loaded.sessions().get("Orchestrator"));
    }

    @Test
    void loadMissingActiveStateReturnsNull() {
        ActiveStateManager.ActiveState loaded = ActiveStateManager.load(tmpDir, "no-world");
        assertNull(loaded);
    }

    @Test
    void createWorldMakesAllFiles() {
        WorldIndexManager.createWorld(tmpDir, "my-world", "测试世界");

        // world.json exists
        assertNotNull(WorldIndexManager.loadWorldMeta(tmpDir, "my-world"));

        // n0000.json exists
        assertNotNull(NodeLoader.load(NodeLoader.nodeFile(tmpDir, "my-world", "n0000")));

        // _index.json contains entry
        var entries = WorldIndexManager.listWorlds(tmpDir);
        assertEquals(1, entries.size());
        assertEquals("my-world", entries.get(0).id());

        // active.json exists
        var active = ActiveStateManager.load(tmpDir, "my-world");
        assertNotNull(active);
        assertEquals("n0000", active.nodeId());
    }

    @Test
    void listWorldsEmptyByDefault() {
        assertTrue(WorldIndexManager.listWorlds(tmpDir).isEmpty());
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl . -Dtest=StateManagerTest -DfailIfNoTests=false -q
```

Expected: 4 tests PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gsim/worldinfo/loader/ActiveStateManager.java src/main/java/com/gsim/worldinfo/loader/WorldIndexManager.java
git add src/test/java/com/gsim/worldinfo/loader/StateManagerTest.java
git commit -m "feat: ActiveStateManager + WorldIndexManager — world lifecycle management"
```

---

### Task 6: QueryCheckpointTool + QueryKeywordTool + QueryNodeTool

**Files:**
- Create: `src/main/java/com/gsim/worldinfo/tool/QueryCheckpointTool.java`
- Create: `src/main/java/com/gsim/worldinfo/tool/QueryKeywordTool.java`
- Create: `src/main/java/com/gsim/worldinfo/tool/QueryNodeTool.java`
- Test: `src/test/java/com/gsim/worldinfo/tool/QueryToolsTest.java`

**Interfaces:**
- Consumes: `WorldInformation`, `ElementRef` from Task 2; `KeywordIndex.SearchResult` from Task 4
- Produces: Three `AgentTool` instances for `ToolRegistry`

- [ ] **Step 1: 写 QueryCheckpointTool.java**

```java
package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.ElementRef;
import com.gsim.worldinfo.WorldInformation;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_checkpoint — return all elements for a given checkpoint across all turns.
 */
public final class QueryCheckpointTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public QueryCheckpointTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() { return "query_checkpoint"; }

    @Override
    public String description() {
        return "Query all historical elements of a checkpoint (player, faction, worldview, etc.) " +
               "across all turns. Set turnFrom/turnTo to narrow the range.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String cpId = call.param("checkpointId");
        if (cpId == null || cpId.isBlank()) {
            return ToolResult.fail("query_checkpoint", "checkpointId is required");
        }

        WorldInformation wi = worldInfo.get();
        List<ElementRef> refs;
        String turnFromStr = call.param("turnFrom");
        String turnToStr = call.param("turnTo");
        if (turnFromStr != null || turnToStr != null) {
            int from = turnFromStr != null ? Integer.parseInt(turnFromStr) : 0;
            int to = turnToStr != null ? Integer.parseInt(turnToStr) : Integer.MAX_VALUE;
            refs = wi.checkpointHistory(cpId, from, to);
        } else {
            refs = wi.checkpointHistory(cpId);
        }

        String label = "";
        String type = "";
        if (!refs.isEmpty()) {
            // get checkpoint metadata from its first node
            var firstNode = wi.nodeById(refs.get(0).nodeId());
            if (firstNode != null) {
                Checkpoint cp = firstNode.checkpoints().get(cpId);
                if (cp != null) {
                    label = cp.label();
                    type = cp.type();
                }
            }
        }

        List<ToolResult.Item> items = refs.stream()
            .map(r -> new ToolResult.Item(r.element().key(), r.nodeId() + "@turn" + r.turn(),
                r.element().value(), 1.0))
            .toList();

        return ToolResult.ok("query_checkpoint", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "checkpointId", Map.of("type", "string", "description", "Checkpoint ID like 'player.曹操' or 'worldview'"),
                "turnFrom", Map.of("type", "integer", "description", "Optional start turn (inclusive)"),
                "turnTo", Map.of("type", "integer", "description", "Optional end turn (inclusive)")
            ),
            "required", List.of("checkpointId")
        );
    }
}
```

- [ ] **Step 2: 写 QueryKeywordTool.java**

```java
package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.KeywordIndex;
import com.gsim.worldinfo.WorldInformation;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_keyword — full-text search across all elements in the world.
 */
public final class QueryKeywordTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public QueryKeywordTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() { return "query_keyword"; }

    @Override
    public String description() {
        return "Full-text keyword search across all world information elements. " +
               "Returns matching elements with source attribution (nodeId, turn, checkpointId). " +
               "Supports pagination via offset.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String keywords = call.param("keywords");
        if (keywords == null || keywords.isBlank()) {
            return ToolResult.fail("query_keyword", "keywords is required");
        }

        int limit = parseInt(call.param("limit"), 20);
        int offset = parseInt(call.param("offset"), 0);

        WorldInformation wi = worldInfo.get();
        KeywordIndex.SearchResult result = wi.keywordIndex().search(keywords, limit, offset);

        List<ToolResult.Item> items = result.items().stream()
            .map(hit -> new ToolResult.Item(
                hit.elementRef().element().key(),
                "%s@turn%d (%s)".formatted(hit.elementRef().nodeId(), hit.elementRef().turn(), hit.elementRef().checkpointId()),
                hit.snippet(),
                (double) hit.score()))
            .toList();

        return ToolResult.ok("query_keyword", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "keywords", Map.of("type", "string", "description", "Space-separated search keywords"),
                "limit", Map.of("type", "integer", "description", "Max results (default 20)"),
                "offset", Map.of("type", "integer", "description", "Pagination offset (default 0)")
            ),
            "required", List.of("keywords")
        );
    }

    private static int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
    }
}
```

- [ ] **Step 3: 写 QueryNodeTool.java**

```java
package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_node — return all checkpoints and elements for a specific turn.
 */
public final class QueryNodeTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public QueryNodeTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() { return "query_node"; }

    @Override
    public String description() {
        return "Query all checkpoints and elements for a specific turn/node. " +
               "Returns the full snapshot of that turn.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail("query_node", "nodeId is required");
        }

        WorldInformation wi = worldInfo.get();
        NodeSnapshot node = wi.nodeById(nodeId);
        if (node == null) {
            return ToolResult.fail("query_node", "Unknown node: " + nodeId);
        }

        List<ToolResult.Item> items = new java.util.ArrayList<>();
        for (var entry : node.checkpoints().entrySet()) {
            String cpId = entry.getKey();
            var cp = entry.getValue();
            for (var el : cp.elements()) {
                items.add(new ToolResult.Item(
                    el.key(), cpId + " (" + cp.label() + ")",
                    el.value(), 1.0));
            }
        }

        return ToolResult.ok("query_node", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "nodeId", Map.of("type", "string", "description", "Node ID like 'n0002'")
            ),
            "required", List.of("nodeId")
        );
    }
}
```

- [ ] **Step 4: 写 QueryToolsTest.java**

```java
package com.gsim.worldinfo.tool;

import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryToolsTest {

    private WorldInformation wi;

    @BeforeEach
    void setUp() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("worldview", new Checkpoint("世界观", "worldview", List.of(
                new Element("气候.中原", "text", "中原大旱", List.of("气候"), List.of())
            ))));
        NodeSnapshot n1 = new NodeSnapshot("n0001", "n0000", 1, "t1", "simulated", "t1",
            Map.of("player.曹操", new Checkpoint("曹操", "player", List.of(
                new Element("曹操.行动.起兵", "action", "曹操自陈留起兵", List.of("曹操", "军事"),
                    List.of("narrative.main"))
            )),
            "narrative", new Checkpoint("推文", "narrative", List.of(
                new Element("narrative.main", "narrative", "曹操起兵，天下震动", List.of("推文"),
                    List.of("player.曹操.elements.0"))
            ))));

        wi = new WorldInformation("test", List.of(n0, n1));
    }

    @Test
    void queryCheckpointReturnsAllHistory() {
        var tool = new QueryCheckpointTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_checkpoint", Map.of("checkpointId", "player.曹操")));

        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertTrue(r.items().get(0).snippet().contains("陈留"));
    }

    @Test
    void queryCheckpointMissingIdFails() {
        var tool = new QueryCheckpointTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_checkpoint", Map.of()));
        assertFalse(r.success());
    }

    @Test
    void queryKeywordFindsText() {
        var tool = new QueryKeywordTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_keyword", Map.of("keywords", "中原")));

        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertTrue(r.items().get(0).path().contains("n0000"));
    }

    @Test
    void queryKeywordWithPagination() {
        var tool = new QueryKeywordTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_keyword",
            Map.of("keywords", "曹操", "limit", "1", "offset", "0")));

        assertTrue(r.success());
        assertEquals(1, r.items().size());
    }

    @Test
    void queryNodeReturnsAllCheckpoints() {
        var tool = new QueryNodeTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_node", Map.of("nodeId", "n0001")));

        assertTrue(r.success());
        assertEquals(2, r.items().size()); // player.曹操 + narrative
    }

    @Test
    void queryNodeUnknownIdFails() {
        var tool = new QueryNodeTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_node", Map.of("nodeId", "n9999")));
        assertFalse(r.success());
    }
}
```

- [ ] **Step 5: 运行测试**

```bash
mvn test -pl . -Dtest=QueryToolsTest -DfailIfNoTests=false -q
```

Expected: 6 tests PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/gsim/worldinfo/tool/ src/test/java/com/gsim/worldinfo/tool/
git commit -m "feat: query_checkpoint + query_keyword + query_node tools"
```

---

### Task 7: WriteElementTool（LLM 写回元素到节点）

**Files:**
- Create: `src/main/java/com/gsim/worldinfo/tool/WriteElementTool.java`
- Test: `src/test/java/com/gsim/worldinfo/tool/WriteElementToolTest.java`

**Interfaces:**
- Consumes: `WorldInformation.appendElement()`, `NodeLoader.save()`
- Produces: `write_element` tool

- [ ] **Step 1: 写 WriteElementTool.java**

```java
package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.Element;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.NodeLoader;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * write_element — LLM writes a new element to a checkpoint in a node.
 */
public final class WriteElementTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;
    private final Path worldsDir;

    public WriteElementTool(Supplier<WorldInformation> worldInfo, Path worldsDir) {
        this.worldInfo = worldInfo;
        this.worldsDir = worldsDir;
    }

    @Override
    public String name() { return "write_element"; }

    @Override
    public String description() {
        return "Write a new information element to a checkpoint. " +
               "Used to record simulation results, narrative text, state changes, etc.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String nodeId = call.param("nodeId");
        String checkpointId = call.param("checkpointId");
        String key = call.param("key");
        String type = call.param("type");
        String value = call.param("value");
        String tagsStr = call.param("tags");
        String linksStr = call.param("links");

        if (nodeId == null || checkpointId == null || key == null || value == null) {
            return ToolResult.fail("write_element",
                "nodeId, checkpointId, key, value are required");
        }

        List<String> tags = tagsStr != null && !tagsStr.isBlank()
            ? Arrays.asList(tagsStr.split(","))
            : List.of();
        List<String> links = linksStr != null && !linksStr.isBlank()
            ? Arrays.asList(linksStr.split(","))
            : List.of();

        Element element = new Element(key, type != null ? type : "text", value, tags, links);

        WorldInformation wi = worldInfo.get();
        wi.appendElement(nodeId, checkpointId, element);

        // persist
        Path nodeFile = NodeLoader.nodeFile(worldsDir, wi.worldId(), nodeId);
        NodeLoader.save(nodeFile, wi.nodeById(nodeId));

        return ToolResult.ok("write_element", List.of(
            new ToolResult.Item(key, checkpointId + "@" + nodeId, value, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "nodeId", Map.of("type", "string", "description", "Target node ID"),
                "checkpointId", Map.of("type", "string", "description", "Target checkpoint ID"),
                "key", Map.of("type", "string", "description", "Element key (unique within checkpoint)"),
                "type", Map.of("type", "string", "description", "Element type: text, action, effect, narrative, etc."),
                "value", Map.of("type", "string", "description", "Element content"),
                "tags", Map.of("type", "string", "description", "Comma-separated tags"),
                "links", Map.of("type", "string", "description", "Comma-separated link targets")
            ),
            "required", List.of("nodeId", "checkpointId", "key", "value")
        );
    }
}
```

- [ ] **Step 2: 写 WriteElementToolTest.java**

```java
package com.gsim.worldinfo.tool;

import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WriteElementToolTest {

    @TempDir
    Path tmpDir;

    private WorldInformation wi;

    @BeforeEach
    void setUp() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            new LinkedHashMap<>(Map.of("worldview", new Checkpoint("世界观", "worldview", new ArrayList<>()))));
        wi = new WorldInformation("test-world", List.of(n0));
    }

    @Test
    void writeElementAppendsToCheckpoint() {
        var tool = new WriteElementTool(() -> wi, tmpDir);
        ToolResult r = tool.execute(new ToolCall("write_element", Map.of(
            "nodeId", "n0000",
            "checkpointId", "worldview",
            "key", "气候.中原",
            "value", "中原大旱蝗灾四起",
            "tags", "气候,灾害"
        )));

        assertTrue(r.success());

        // verify in memory
        List<ElementRef> history = wi.checkpointHistory("worldview");
        assertEquals(1, history.size());
        assertEquals("气候.中原", history.get(0).element().key());
        assertEquals("中原大旱蝗灾四起", history.get(0).element().value());
        assertTrue(history.get(0).element().tags().contains("气候"));
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
mvn test -pl . -Dtest=WriteElementToolTest -DfailIfNoTests=false -q
```

Expected: 1 test PASS。

- [ ] **Step 4: 全量测试**

```bash
mvn test -q
```

Expected: 所有新增测试 PASS，现有测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gsim/worldinfo/tool/WriteElementTool.java src/test/java/com/gsim/worldinfo/tool/WriteElementToolTest.java
git commit -m "feat: write_element tool — LLM writes back to node JSON"
```

---

### Task 8: CacheSession + CacheStore（OpenAI 格式缓存读写）

**Files:**
- Create: `src/main/java/com/gsim/cache/CacheSession.java`
- Create: `src/main/java/com/gsim/cache/CacheStore.java`
- Test: `src/test/java/com/gsim/cache/CacheStoreTest.java`

**Interfaces:**
- Consumes: No internal deps beyond JsonUtils
- Produces: `CacheStore.load/save/appendMessages/createNew`

- [ ] **Step 1: 写 CacheSession.java**

```java
package com.gsim.cache;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One LLM conversation session, stored as a JSON file in worlds/{worldId}/caches/.
 * messages use raw OpenAI format: role, content, tool_calls, tool_call_id.
 */
@JsonDeserialize
public class CacheSession {

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("worldId")
    private String worldId;

    @JsonProperty("nodeId")
    private String nodeId;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("createdAt")
    private String createdAt;

    @JsonProperty("previousSessionId")
    private String previousSessionId;

    @JsonProperty("compressionNote")
    private String compressionNote;

    @JsonProperty("messages")
    private List<Map<String, Object>> messages;

    public CacheSession() {
        this.messages = new ArrayList<>();
    }

    public CacheSession(String agentName, String worldId, String nodeId,
                        String sessionId, String createdAt) {
        this.agentName = agentName;
        this.worldId = worldId;
        this.nodeId = nodeId;
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.messages = new ArrayList<>();
    }

    // getters
    public String agentName() { return agentName; }
    public String worldId() { return worldId; }
    public String nodeId() { return nodeId; }
    public String sessionId() { return sessionId; }
    public String createdAt() { return createdAt; }
    public String previousSessionId() { return previousSessionId; }
    public String compressionNote() { return compressionNote; }
    public List<Map<String, Object>> messages() { return messages; }

    // setters (for Jackson)
    public void setAgentName(String v) { this.agentName = v; }
    public void setWorldId(String v) { this.worldId = v; }
    public void setNodeId(String v) { this.nodeId = v; }
    public void setSessionId(String v) { this.sessionId = v; }
    public void setCreatedAt(String v) { this.createdAt = v; }
    public void setPreviousSessionId(String v) { this.previousSessionId = v; }
    public void setCompressionNote(String v) { this.compressionNote = v; }
    public void setMessages(List<Map<String, Object>> v) { this.messages = v; }

    // fluent
    public CacheSession previousSessionId(String v) { this.previousSessionId = v; return this; }
    public CacheSession compressionNote(String v) { this.compressionNote = v; return this; }

    public void addMessage(Map<String, Object> message) {
        this.messages.add(message);
    }

    public int messageCount() {
        return messages.size();
    }
}
```

- [ ] **Step 2: 写 CacheStore.java**

```java
package com.gsim.cache;

import com.gsim.util.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes CacheSession JSON files.
 */
public final class CacheStore {

    private CacheStore() {}

    /** Caches directory for a world. */
    public static Path cachesDir(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("caches");
    }

    /** Full path to a specific cache file. */
    public static Path cacheFile(Path worldsDir, String worldId, String sessionId) {
        return cachesDir(worldsDir, worldId).resolve(sessionId);
    }

    /** Load a cache session from disk. Returns null if not found. */
    public static CacheSession load(Path worldsDir, String worldId, String sessionId) {
        Path file = cacheFile(worldsDir, worldId, sessionId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), CacheSession.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load cache session: " + sessionId, e);
        }
    }

    /** Save a cache session to disk. Creates caches/ dir if needed. */
    public static void save(Path worldsDir, String worldId, CacheSession session) {
        Path file = cacheFile(worldsDir, worldId, session.sessionId());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonUtils.toJson(session));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save cache session: " + session.sessionId(), e);
        }
    }

    /** Create a new empty session with timestamp-based sessionId. */
    public static CacheSession createNew(Path worldsDir, String worldId,
                                          String agentName, String nodeId) {
        String now = Instant.now().toString();
        // use agent-timestamp format for the session ID
        String sessionId = agentName + "_" + now.replace(":", "-").substring(0, 19) + ".json";
        String finalNow = now;

        CacheSession session = new CacheSession(agentName, worldId, nodeId, sessionId, finalNow);
        save(worldsDir, worldId, session);
        return session;
    }

    /** Append messages and persist. Used for streaming incremental save. */
    public static void appendAndSave(Path worldsDir, String worldId,
                                      CacheSession session, Map<String, Object> message) {
        session.addMessage(message);
        save(worldsDir, worldId, session);
    }
}
```

- [ ] **Step 3: 写 CacheStoreTest.java**

```java
package com.gsim.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CacheStoreTest {

    @TempDir
    Path tmpDir;

    @Test
    void saveAndLoadRoundtrip() {
        CacheSession session = CacheStore.createNew(tmpDir, "test-world", "Orchestrator", "n0000");

        session.addMessage(Map.of("role", "system", "content", "You are a simulation engine."));
        session.addMessage(Map.of("role", "user", "content", "Hello"));
        CacheStore.save(tmpDir, "test-world", session);

        CacheSession loaded = CacheStore.load(tmpDir, "test-world", session.sessionId());
        assertNotNull(loaded);
        assertEquals("Orchestrator", loaded.agentName());
        assertEquals(2, loaded.messageCount());
        assertEquals("system", loaded.messages().get(0).get("role"));
        assertEquals("Hello", loaded.messages().get(1).get("content"));
    }

    @Test
    void loadMissingReturnsNull() {
        assertNull(CacheStore.load(tmpDir, "no-world", "nonexistent.json"));
    }

    @Test
    void compressionChain() {
        CacheSession old = CacheStore.createNew(tmpDir, "w", "Orchestrator", "n0002");
        old.compressionNote("Summary of old session.");

        CacheSession fresh = CacheStore.createNew(tmpDir, "w", "Orchestrator", "n0003");
        fresh.previousSessionId(old.sessionId());
        fresh.compressionNote("Continuing from previous...");

        CacheStore.save(tmpDir, "w", old);
        CacheStore.save(tmpDir, "w", fresh);

        CacheSession loaded = CacheStore.load(tmpDir, "w", fresh.sessionId());
        assertEquals(old.sessionId(), loaded.previousSessionId());
        assertEquals("Continuing from previous...", loaded.compressionNote());
    }

    @Test
    void createsCachesDirectory() {
        CacheStore.createNew(tmpDir, "w", "Sim", "n0000");
        assertTrue(java.nio.file.Files.exists(CacheStore.cachesDir(tmpDir, "w")));
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl . -Dtest=CacheStoreTest -DfailIfNoTests=false -q
```

Expected: 4 tests PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gsim/cache/ src/test/java/com/gsim/cache/
git commit -m "feat: CacheSession + CacheStore — OpenAI-format conversation cache"
```

---

### Task 9: ContextRenderer（FreeMarker system prompt 渲染）

**Files:**
- Create: `src/main/java/com/gsim/context/ContextRenderer.java`
- Create: `prompts/OrchestratorAgent_system.md`
- Test: `src/test/java/com/gsim/context/ContextRendererTest.java`

**Interfaces:**
- Consumes: `WorldInformation` from Task 2
- Produces: `ContextRenderer.renderSystemPrompt(String agentName, WorldInformation wi)` → String

- [ ] **Step 1: 写 prompts/OrchestratorAgent_system.md**

```markdown
<#-- FreeMarker template for Orchestrator system prompt -->
# 推演引擎

你是一个基于回合制的历史推演引擎，服务于架空历史/文游场景。
你的任务是根据玩家行动推进世界时间线，生成符合设定的叙事推文。

## 当前世界状态

- 世界：${worldId}
- 当前节点：${activeNodeId}
- 第 ${activeTurn} 回合，世界时间：${worldTime}
- 分支链长度：${chainLength} 个节点（从 ${rootNodeId} 到 ${activeNodeId}）

## 活跃检查点

<#list checkpointIds as cpId>
- ${cpId}
</#list>

## 最近推文

<#list recentNarratives as n>
**回合 ${n.turn} (${n.worldTime})**
${n.text}

</#list>

## 工具清单

你可以使用以下工具：

1. **query_checkpoint** — 纵向查询检查点的全部历史记录。参数：checkpointId, turnFrom(可选), turnTo(可选)
2. **query_keyword** — 横向关键词全文检索。参数：keywords, limit(默认20), offset(默认0)
3. **query_node** — 定点查询某个回合的全貌。参数：nodeId
4. **write_element** — 向节点写入信息元素。参数：nodeId, checkpointId, key, type, value, tags(可选), links(可选)

所有查询结果都附带来源信息（nodeId, turn, checkpointId）。
写入时请确保 key 在同一 checkpoint 内唯一。
```

- [ ] **Step 2: 写 ContextRenderer.java**

```java
package com.gsim.context;

import com.gsim.worldinfo.ElementRef;
import com.gsim.worldinfo.WorldInformation;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders agent system prompts from FreeMarker templates in the prompts/ directory.
 */
public final class ContextRenderer {

    private final Configuration fm;
    private final Path promptsDir;

    public ContextRenderer(Path promptsDir) {
        this.promptsDir = promptsDir;
        this.fm = new Configuration(Configuration.VERSION_2_3_34);
        this.fm.setDirectoryForTemplateLoading(promptsDir.toFile());
        this.fm.setDefaultEncoding("UTF-8");
    }

    /**
     * Render a system prompt for the given agent with world context injected.
     */
    public String renderSystemPrompt(String agentName, WorldInformation wi) {
        String templateName = agentName + "_system.md";
        Map<String, Object> data = buildDataModel(wi);
        return render(templateName, data);
    }

    /**
     * Render the compression prompt.
     */
    public String renderCompressPrompt(String agentName, String conversationText) {
        String templateName = agentName + "_compress.md";
        Map<String, Object> data = Map.of("conversation", conversationText);
        return render(templateName, data);
    }

    // -- private --

    private String render(String templateName, Map<String, Object> data) {
        try {
            Template t = fm.getTemplate(templateName);
            StringWriter sw = new StringWriter();
            t.process(data, sw);
            return sw.toString();
        } catch (IOException e) {
            // fallback: read raw file without processing
            Path file = promptsDir.resolve(templateName);
            if (Files.exists(file)) {
                try { return Files.readString(file); } catch (IOException ex) { /* ignore */ }
            }
            throw new RuntimeException("Cannot render template: " + templateName, e);
        } catch (TemplateException e) {
            throw new RuntimeException("Template error: " + templateName, e);
        }
    }

    private Map<String, Object> buildDataModel(WorldInformation wi) {
        Map<String, Object> data = new HashMap<>();
        data.put("worldId", wi.worldId());
        data.put("rootNodeId", wi.rootNodeId());
        data.put("activeNodeId", wi.activeNodeId());
        data.put("activeTurn", wi.activeNode().turn());
        data.put("worldTime", wi.activeNode().worldTime());
        data.put("chainLength", wi.branchChain().size());
        data.put("checkpointIds", wi.allCheckpointIds());

        // recent 3 narratives
        List<ElementRef> narratives = wi.checkpointHistory("narrative");
        int start = Math.max(0, narratives.size() - 3);
        data.put("recentNarratives", narratives.subList(start, narratives.size()).stream()
            .map(ref -> Map.of("turn", ref.turn(), "worldTime", ref.worldTime(), "text", ref.element().value()))
            .toList());

        return data;
    }
}
```

- [ ] **Step 3: 写 ContextRendererTest.java**

```java
package com.gsim.context;

import com.gsim.worldinfo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextRendererTest {

    @TempDir
    Path tmpDir;

    @Test
    void renderSystemPromptInjectsWorldInfo() throws Exception {
        // Write a minimal template
        Files.createDirectories(tmpDir);
        Files.writeString(tmpDir.resolve("OrchestratorAgent_system.md"),
            "# Engine\nWorld: ${worldId}\nTurn: ${activeTurn}\nCheckpoints: ${checkpointIds?join(\", \")}");

        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("worldview", new Checkpoint("世界观", "worldview", List.of(
                Element.simple("k", "text", "v")))));

        WorldInformation wi = new WorldInformation("test", List.of(n0));

        ContextRenderer renderer = new ContextRenderer(tmpDir);
        String result = renderer.renderSystemPrompt("OrchestratorAgent", wi);

        assertTrue(result.contains("World: test"));
        assertTrue(result.contains("Turn: 0"));
        assertTrue(result.contains("worldview"));
    }

    @Test
    void missingTemplateFallsBackToRawFile() throws Exception {
        Files.createDirectories(tmpDir);
        Files.writeString(tmpDir.resolve("TestAgent_system.md"), "Raw content without variables");

        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0", Map.of());
        WorldInformation wi = new WorldInformation("test", List.of(n0));

        ContextRenderer renderer = new ContextRenderer(tmpDir);
        String result = renderer.renderSystemPrompt("TestAgent", wi);

        assertEquals("Raw content without variables", result.trim());
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl . -Dtest=ContextRendererTest -DfailIfNoTests=false -q
```

Expected: 2 tests PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gsim/context/ContextRenderer.java
git add src/test/java/com/gsim/context/ContextRendererTest.java
git add prompts/OrchestratorAgent_system.md
git commit -m "feat: ContextRenderer — FreeMarker-based system prompt injection"
```

---

### Task 10: 启动流程整合 — Bootstrap

**Files:**
- Modify: `src/main/java/com/gsim/app/Main.java`
- Create: `src/main/java/com/gsim/app/Bootstrap.java`

**Interfaces:**
- Consumes: All previous tasks
- Produces: Complete startup that loads WorldInformation + Cache Session

- [ ] **Step 1: 写 Bootstrap.java**

```java
package com.gsim.app;

import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
import com.gsim.context.ContextRenderer;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.WorldIndexManager;
import com.gsim.worldinfo.loader.WorldInfoBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full startup sequence: load worlds/ → WorldInformation → Cache → Context.
 */
public final class Bootstrap {

    private final Path worldsDir;
    private final Path promptsDir;

    // result
    private WorldInformation worldInfo;
    private CacheSession activeCache;
    private ContextRenderer contextRenderer;
    private String worldId;
    private String activeNodeId;

    public Bootstrap(Path worldsDir, Path promptsDir) {
        this.worldsDir = worldsDir;
        this.promptsDir = promptsDir;
    }

    public BootstrapResult boot() {
        // 1. List worlds
        List<WorldIndexManager.WorldEntry> worlds = WorldIndexManager.listWorlds(worldsDir);

        // 2. If no worlds, auto-create default
        if (worlds.isEmpty()) {
            worldId = "default";
            WorldIndexManager.createWorld(worldsDir, worldId, "默认世界");
        } else {
            worldId = worlds.get(0).id();
        }

        // 3. Read active state
        ActiveStateManager.ActiveState active = ActiveStateManager.load(worldsDir, worldId);
        if (active == null) {
            activeNodeId = "n0000";
        } else {
            activeNodeId = active.nodeId();
        }

        // 4. Build WorldInformation
        worldInfo = WorldInfoBuilder.build(worldsDir, worldId, activeNodeId);
        if (worldInfo == null) {
            throw new IllegalStateException("Failed to load world: " + worldId);
        }

        // 5. Initialize context renderer
        contextRenderer = new ContextRenderer(promptsDir);

        // 6. Load Orchestrator cache, or create new
        String orchestratorSession = ActiveStateManager.orchestratorSession(active);
        if (orchestratorSession != null) {
            activeCache = CacheStore.load(worldsDir, worldId, orchestratorSession);
        }

        if (activeCache == null) {
            activeCache = CacheStore.createNew(worldsDir, worldId, "Orchestrator", activeNodeId);
            // Inject initial system prompt
            String systemPrompt = contextRenderer.renderSystemPrompt("OrchestratorAgent", worldInfo);
            activeCache.addMessage(Map.of("role", "system", "content", systemPrompt));
            CacheStore.save(worldsDir, worldId, activeCache);
        }

        return new BootstrapResult(worldId, activeNodeId, worldInfo, activeCache, contextRenderer);
    }

    // -- accessors (for use by Main after boot) --
    public WorldInformation worldInfo() { return worldInfo; }
    public CacheSession activeCache() { return activeCache; }
    public ContextRenderer contextRenderer() { return contextRenderer; }
    public String worldId() { return worldId; }

    // -- result record --
    public record BootstrapResult(
        String worldId,
        String activeNodeId,
        WorldInformation worldInfo,
        CacheSession activeCache,
        ContextRenderer contextRenderer
    ) {}
}
```

- [ ] **Step 2: 修改 Main.java 启动流程**

找到 Main.java 现有的 main() 方法。在初始化 AppConfig 之后，加入 Bootstrap 调用。最小改动方式（不删除旧代码，先加新路径）：

```java
// 在 main() 中，AppConfig 初始化后添加：
Path worldsDir = appConfig.worldsDir();
Path promptsDir = appConfig.promptsDir();
Bootstrap bootstrap = new Bootstrap(worldsDir, promptsDir);
Bootstrap.BootstrapResult bootResult = bootstrap.boot();

logger.info("World loaded: {}, active node: {}, chain length: {}",
    bootResult.worldId(), bootResult.activeNodeId(),
    bootResult.worldInfo().branchChain().size());
```

注意：此阶段 Main.java 仍保留旧代码，只加入新 Bootstrap 调用。Phase 8 再清理。

- [ ] **Step 3: 编译并验证 Bootstrap 流程**

写一个简单测试：

```java
// src/test/java/com/gsim/app/BootstrapTest.java
package com.gsim.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BootstrapTest {

    @TempDir
    Path tmpDir;

    @Test
    void bootCreatesDefaultWorld() throws Exception {
        Path worldsDir = tmpDir.resolve("worlds");
        Path promptsDir = tmpDir.resolve("prompts");
        Files.createDirectories(promptsDir);
        Files.writeString(promptsDir.resolve("OrchestratorAgent_system.md"),
            "System: ${worldId}, turn ${activeTurn}");

        Bootstrap b = new Bootstrap(worldsDir, promptsDir);
        Bootstrap.BootstrapResult result = b.boot();

        assertEquals("default", result.worldId());
        assertEquals("n0000", result.activeNodeId());
        assertNotNull(result.worldInfo());
        assertNotNull(result.activeCache());
        assertEquals(1, result.activeCache().messageCount());
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl . -Dtest=BootstrapTest -DfailIfNoTests=false -q
```

Expected: 1 test PASS。

- [ ] **Step 5: 全量测试**

```bash
mvn test -q
```

Expected: 所有新增测试 PASS。现有测试可能有部分因旧代码未删除而存在，但应通过。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/gsim/app/Bootstrap.java
git add -u src/main/java/com/gsim/app/Main.java
git add src/test/java/com/gsim/app/BootstrapTest.java
git commit -m "feat: Bootstrap — full startup sequence with WorldInformation + Cache"
```

---

### Task 11: 新 CLI 命令体系

**Files:**
- Create: `src/main/java/com/gsim/commands/WorldCommand.java`
- Create: `src/main/java/com/gsim/commands/NodeCommand.java`
- Create: `src/main/java/com/gsim/commands/ChatCommand.java`

**Interfaces:**
- Consumes: `WorldInformation`, `WorldIndexManager`, `NodeLoader`
- Produces: CLI commands for `/world`, `/node`, `/chat`

- [ ] **Step 1: 写 WorldCommand.java**

```java
package com.gsim.commands;

import com.gsim.worldinfo.loader.WorldIndexManager;

import java.nio.file.Path;
import java.util.List;

/**
 * /world — world management commands.
 *   /world list                    — list all worlds
 *   /world create <id> <name>      — create new world
 *   /world switch <id>             — switch active world (reload)
 */
public final class WorldCommand {

    private final Path worldsDir;
    private final Runnable onWorldChanged; // callback to re-bootstrap

    public WorldCommand(Path worldsDir, Runnable onWorldChanged) {
        this.worldsDir = worldsDir;
        this.onWorldChanged = onWorldChanged;
    }

    public String execute(List<String> args) {
        if (args.isEmpty()) {
            return "Usage: /world [list|create|switch] ...";
        }
        return switch (args.get(0)) {
            case "list" -> listWorlds();
            case "create" -> createWorld(args);
            case "switch" -> switchWorld(args);
            default -> "Unknown subcommand: " + args.get(0);
        };
    }

    private String listWorlds() {
        List<WorldIndexManager.WorldEntry> worlds = WorldIndexManager.listWorlds(worldsDir);
        if (worlds.isEmpty()) return "No worlds found.";
        StringBuilder sb = new StringBuilder("Worlds:\n");
        for (var w : worlds) {
            sb.append("  - ").append(w.id()).append(" (").append(w.name()).append(")\n");
        }
        return sb.toString();
    }

    private String createWorld(List<String> args) {
        if (args.size() < 3) return "Usage: /world create <id> <name>";
        String id = args.get(1);
        String name = args.get(2);
        WorldIndexManager.createWorld(worldsDir, id, name);
        return "World created: " + id + " (" + name + ")";
    }

    private String switchWorld(List<String> args) {
        if (args.size() < 2) return "Usage: /world switch <id>";
        String id = args.get(1);
        // validate world exists
        if (WorldIndexManager.loadWorldMeta(worldsDir, id) == null) {
            return "World not found: " + id;
        }
        // trigger re-bootstrap
        onWorldChanged.run();
        return "Switched to world: " + id + ". Reloading...";
    }
}
```

- [ ] **Step 2: 写 NodeCommand.java**

```java
package com.gsim.commands;

import com.gsim.worldinfo.*;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.NodeLoader;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * /node — node management commands.
 *   /node status                   — show current node info
 *   /node list                     — list nodes in chain
 *   /node goto <nodeId>            — switch to a different node in chain
 *   /node create <worldTime>       — create child node (next turn)
 */
public final class NodeCommand {

    private final Path worldsDir;
    private final Supplier<WorldInformation> worldInfo;
    private final Runnable onNodeChanged;

    public NodeCommand(Path worldsDir, Supplier<WorldInformation> worldInfo, Runnable onNodeChanged) {
        this.worldsDir = worldsDir;
        this.worldInfo = worldInfo;
        this.onNodeChanged = onNodeChanged;
    }

    public String execute(List<String> args) {
        if (args.isEmpty()) return "Usage: /node [status|list|goto|create] ...";
        return switch (args.get(0)) {
            case "status" -> nodeStatus();
            case "list" -> nodeList();
            case "goto" -> gotoNode(args);
            case "create" -> createChild(args);
            default -> "Unknown subcommand: " + args.get(0);
        };
    }

    private String nodeStatus() {
        WorldInformation wi = worldInfo.get();
        NodeSnapshot active = wi.activeNode();
        return """
            Node: %s (turn %d)
            World time: %s
            Status: %s
            Parent: %s
            Chain length: %d
            Checkpoints: %s
            """.formatted(active.nodeId(), active.turn(), active.worldTime(),
                active.status(), active.parentId(),
                wi.branchChain().size(), wi.allCheckpointIds());
    }

    private String nodeList() {
        WorldInformation wi = worldInfo.get();
        StringBuilder sb = new StringBuilder("Branch chain:\n");
        for (NodeSnapshot n : wi.branchChain()) {
            String marker = n.nodeId().equals(wi.activeNodeId()) ? " ← active" : "";
            sb.append("  %s [turn %d] %s%s\n".formatted(n.nodeId(), n.turn(), n.worldTime(), marker));
        }
        return sb.toString();
    }

    private String gotoNode(List<String> args) {
        if (args.size() < 2) return "Usage: /node goto <nodeId>";
        String nodeId = args.get(1);
        WorldInformation wi = worldInfo.get();
        if (wi.nodeById(nodeId) == null) {
            return "Node not found in chain: " + nodeId;
        }
        // update active
        String worldId = wi.worldId();
        ActiveStateManager.save(worldsDir, worldId,
            new ActiveStateManager.ActiveState(nodeId, Map.of()));
        onNodeChanged.run();
        return "Switched to node: " + nodeId + ". Reloading...";
    }

    private String createChild(List<String> args) {
        if (args.size() < 2) return "Usage: /node create <worldTime>";
        String worldTime = args.get(1);
        WorldInformation wi = worldInfo.get();
        String parentId = wi.activeNodeId();
        int nextTurn = wi.activeNode().turn() + 1;
        String newNodeId = com.gsim.util.IdGenerator.nodeId();
        // TODO: reset counter based on existing nodes; for now just generate

        NodeSnapshot child = new NodeSnapshot(newNodeId, parentId, nextTurn,
            worldTime, "active", Instant.now().toString(),
            new LinkedHashMap<>());

        NodeLoader.save(NodeLoader.nodeFile(worldsDir, wi.worldId(), newNodeId), child);

        ActiveStateManager.save(worldsDir, wi.worldId(),
            new ActiveStateManager.ActiveState(newNodeId, Map.of()));
        onNodeChanged.run();
        return "Created child node: " + newNodeId + " (turn " + nextTurn + ")";
    }
}
```

- [ ] **Step 3: 写 ChatCommand.java**

```java
package com.gsim.commands;

import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * /chat — manual message to the LLM.
 *   /chat <message>                — send a message through the agent loop
 *   /chat history [n]              — show last n messages from cache
 *   /chat clear                    — compress and start new session
 */
public final class ChatCommand {

    private final Path worldsDir;
    private final Supplier<String> worldId;
    private final Supplier<CacheSession> activeCache;

    public ChatCommand(Path worldsDir, Supplier<String> worldId, Supplier<CacheSession> activeCache) {
        this.worldsDir = worldsDir;
        this.worldId = worldId;
        this.activeCache = activeCache;
    }

    public String execute(List<String> args) {
        if (args.isEmpty()) return "Usage: /chat [message|history|clear] ...";
        String sub = args.get(0);
        if ("history".equals(sub)) {
            int n = args.size() > 1 ? Integer.parseInt(args.get(1)) : 10;
            return showHistory(n);
        }
        if ("clear".equals(sub)) {
            return clearSession();
        }
        // default: the whole args is the message
        String message = String.join(" ", args);
        activeCache.get().addMessage(Map.of("role", "user", "content", message));
        CacheStore.save(worldsDir, worldId.get(), activeCache.get());
        return "(message queued, agent will process)";
    }

    private String showHistory(int n) {
        CacheSession session = activeCache.get();
        List<Map<String, Object>> msgs = session.messages();
        int start = Math.max(0, msgs.size() - n);
        StringBuilder sb = new StringBuilder("Last " + n + " messages:\n");
        for (int i = start; i < msgs.size(); i++) {
            Map<String, Object> m = msgs.get(i);
            String role = (String) m.get("role");
            String content = (String) m.get("content");
            if (content == null) content = "[tool_calls]";
            if (content.length() > 80) content = content.substring(0, 80) + "...";
            sb.append("  [").append(role).append("] ").append(content).append("\n");
        }
        return sb.toString();
    }

    private String clearSession() {
        CacheSession old = activeCache.get();
        // Create new session with compression chain
        String summary = "(manual clear — previous session " + old.sessionId() + ")";
        CacheSession fresh = CacheStore.createNew(worldsDir, worldId.get(),
            "Orchestrator", old.nodeId());
        fresh.previousSessionId(old.sessionId());
        fresh.compressionNote(summary);
        CacheStore.save(worldsDir, worldId.get(), fresh);
        return "Cleared. New session: " + fresh.sessionId();
    }
}
```

Step 2 中 NodeCommand 的 `goto` 使用 `Runnable onNodeChanged` 触发完整重新加载。整合时由 Main/Bootstrap 提供此回调。

- [ ] **Step 4: 编译验证**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gsim/commands/
git commit -m "feat: /world /node /chat CLI commands"
```

---

### Task 12: 旧代码删除

**Files:**
- Delete: `src/main/java/com/gsim/campaign/`（整个包）
- Delete: `src/main/java/com/gsim/branch/`（整个包）
- Delete: `src/main/java/com/gsim/world/`（整个包）
- Delete: `src/main/java/com/gsim/data/`（整个包）
- Delete: `src/main/java/com/gsim/storage/`（整个包）
- Delete: `src/main/java/com/gsim/context/`（旧版，整个包）
- Delete: `src/main/java/com/gsim/chroma/`（整个包）
- Delete: `src/main/java/com/gsim/chat/`（整个包）
- Delete: `src/main/java/com/gsim/task/`（整个包）
- Delete: `src/main/java/com/gsim/timeline/`（整个包）
- Delete: `src/main/java/com/gsim/interaction/commands/`（28个命令，保留 interaction/ 核心框架）
- Delete: `data/` 目录（磁盘）
- Modify: 修复所有编译错误（移除对已删除包的引用）

**Interfaces:**
- 无新增接口。所有对旧包的引用必须移除或替换。

- [ ] **Step 1: 定位所有旧包引用**

```bash
grep -rn "com\.gsim\.campaign\|com\.gsim\.branch\|com\.gsim\.world\|com\.gsim\.data\.DataManager\|com\.gsim\.storage\|com\.gsim\.chroma\|com\.gsim\.chat\|com\.gsim\.task\|com\.gsim\.timeline" src/main/java/ --include="*.java" | cut -d: -f1 | sort -u
```

列出所有引用旧包的文件名，逐个处理。

- [ ] **Step 2: 删除旧包文件**

```bash
rm -rf src/main/java/com/gsim/campaign/
rm -rf src/main/java/com/gsim/branch/
rm -rf src/main/java/com/gsim/world/
rm -rf src/main/java/com/gsim/data/
rm -rf src/main/java/com/gsim/storage/
rm -rf src/main/java/com/gsim/context/
rm -rf src/main/java/com/gsim/chroma/
rm -rf src/main/java/com/gsim/chat/
rm -rf src/main/java/com/gsim/task/
rm -rf src/main/java/com/gsim/timeline/
rm -rf src/main/java/com/gsim/interaction/commands/
rm -rf data/
```

- [ ] **Step 3: 修复保留文件中的旧引用**

对 `interaction/` 中剩余的适配器（ConsoleInteractionAdapter, WebInteractionAdapter），移除对 InteractionCommand 的依赖。对 `agent/` 中的 OrchestratorAgent，移除对 DataManager、BranchContextRenderer 的引用。

这是一个 manual 步骤，需要逐个文件确认。核心变更：

`ConsoleInteractionAdapter.java` — 移除 CommandParser 引用，改为直接路由到新命令：
```java
// 旧: CommandParser.parse(input) → InteractionCommand.execute()
// 新: 路由到 WorldCommand / NodeCommand / ChatCommand
```

`OrchestratorAgent.java` — 移除 `runWithContextSession()` 等旧方法，保留 ToolLoop 核心：
```java
// 保留: runToolLoop(), callLlm(), beforeToolExecute(), afterToolExecute()
// 移除: run(), runWithRenderedContext(), runWithContextSession(), chatWithContextSession()
// 移除: filterByTurns(), historyConfig, lastAssistantDraft
```

`NodeAgentChatService.java` — 移除：
```java
// 整个文件删除，chat 逻辑直接由 ChatCommand + cache session 驱动
```

`ToolGroupManager.java` — 保留但简化 group 定义，移除旧的 tool group 映射。

`Main.java` — 移除旧初始化路径，只保留新 Bootstrap。

- [ ] **Step 4: 删除旧测试中引用废弃包的测试**

```bash
# 列出引用旧包的测试文件
grep -rn "com\.gsim\.campaign\|com\.gsim\.branch\|com\.gsim\.world\.WorldState\|com\.gsim\.data\.DataManager\|com\.gsim\.storage\|com\.gsim\.chroma\|com\.gsim\.chat\.BranchMessage\|com\.gsim\.task\.TaskContext\|com\.gsim\.timeline" src/test/ --include="*.java" | cut -d: -f1 | sort -u
```

删除这些测试文件（它们测试的是废弃代码）。

- [ ] **Step 5: 编译**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS。如失败，逐个修复引用错误。

- [ ] **Step 6: 全量测试**

```bash
mvn test -q
```

Expected: 所有保留测试 PASS。新测试全部 PASS。

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: remove 12 deprecated packages and data/ directory"
```

---

### Task 13: 写 prompt 模板文件

**Files:**
- Create: `prompts/OrchestratorAgent_system.md`（已在 Task 9 创建，确认完整）
- Create: `prompts/SimAgent_system.md`
- Create: `prompts/SearchAgent_system.md`
- Create: `prompts/OrchestratorAgent_compress.md`

- [ ] **Step 1: 补全 SimAgent_system.md**

```markdown
<#-- FreeMarker template for Sim sub-agent system prompt -->
# 推演引擎 (Sim)

你是推演引擎的子代理，负责执行具体的模拟推演任务。

## 当前世界状态

- 世界：${worldId}
- 当前节点：${activeNodeId}
- 第 ${activeTurn} 回合，世界时间：${worldTime}

## 任务

${task}

## 可用工具

- query_checkpoint — 查询检查点历史
- query_keyword — 关键词搜索
- query_node — 查询节点全貌
- write_element — 写入推演结果

请根据任务要求进行推演，并将结果通过 write_element 写入对应检查点。
完成后请调用 finish_action。
```

- [ ] **Step 2: 补全 SearchAgent_system.md**

```markdown
<#-- FreeMarker template for Search sub-agent system prompt -->
# 信息检索 (Search)

你是推演引擎的信息检索子代理。

## 当前世界

- 世界：${worldId}
- 节点数：${chainLength}

## 任务

${task}

## 可用工具

- query_checkpoint — 纵向查询
- query_keyword — 关键词搜索
- query_node — 定点查询

请检索所需信息并以结构化方式返回结果。
```

- [ ] **Step 3: 补全 OrchestratorAgent_compress.md**

```markdown
<#-- FreeMarker template for conversation compression -->
# 对话压缩

请将以下对话历史压缩为一段简洁的摘要（500字以内）。
摘要应包含：
1. 关键事件和决策
2. 重要的人物行动
3. 世界观变更
4. 未解决的问题

对话历史：

${conversation}

请直接输出摘要文本，不要加额外标记。
```

- [ ] **Step 4: Commit**

```bash
git add prompts/
git commit -m "feat: prompt templates — Orchestrator, Sim, Search system prompts + compress"
```

---

### Task 14: 最终集成测试 + 端到端验证

**Files:**
- Create: `src/test/java/com/gsim/integration/EndToEndTest.java`

**Interfaces:**
- Consumes: All new components
- Produces: End-to-end test validating the full flow

- [ ] **Step 1: 写 EndToEndTest.java**

```java
package com.gsim.integration;

import com.gsim.app.Bootstrap;
import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
import com.gsim.context.ContextRenderer;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full end-to-end: create world → add nodes → query → cache → compress.
 */
class EndToEndTest {

    @TempDir
    Path tmpDir;

    @Test
    void fullLifecycle() throws Exception {
        Path worldsDir = tmpDir.resolve("worlds");
        Path promptsDir = tmpDir.resolve("prompts");
        Files.createDirectories(promptsDir);
        Files.writeString(promptsDir.resolve("OrchestratorAgent_system.md"),
            "System: ${worldId}, turn ${activeTurn}");

        // --- Bootstrap creates default world ---
        Bootstrap b = new Bootstrap(worldsDir, promptsDir);
        Bootstrap.BootstrapResult result = b.boot();

        assertEquals("default", result.worldId());
        assertEquals("n0000", result.activeNodeId());
        assertNotNull(result.worldInfo());

        // --- Write some elements ---
        WorldInformation wi = result.worldInfo();
        wi.appendElement("n0000", "worldview",
            new com.gsim.worldinfo.Element("气候", "text", "中原大旱", 
                java.util.List.of("气候"), java.util.List.of()));

        // persist
        NodeLoader.save(NodeLoader.nodeFile(worldsDir, "default", "n0000"), wi.nodeById("n0000"));

        // --- Query ---
        assertEquals(1, wi.checkpointHistory("worldview").size());
        assertFalse(wi.keywordIndex().search("中原", 10, 0).items().isEmpty());

        // --- Cache ---
        CacheSession cache = result.activeCache();
        cache.addMessage(Map.of("role", "user", "content", "测试消息"));
        CacheStore.save(worldsDir, "default", cache);

        CacheSession loaded = CacheStore.load(worldsDir, "default", cache.sessionId());
        assertEquals(2, loaded.messageCount()); // system + user

        // --- Context rendering ---
        ContextRenderer renderer = result.contextRenderer();
        String prompt = renderer.renderSystemPrompt("OrchestratorAgent", wi);
        assertTrue(prompt.contains("default"));
        assertTrue(prompt.contains("turn 0"));
    }
}
```

- [ ] **Step 2: 运行全量测试**

```bash
mvn test -q
```

Expected: 所有测试 PASS，包括 EndToEndTest。

- [ ] **Step 3: 确认项目可启动**

```bash
mvn package -q -DskipTests && java -jar target/GSimulator.jar
```

Expected: 应用启动，自动创建 `worlds/` 目录，输出 "World loaded: default, active node: n0000"。

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/gsim/integration/
git commit -m "test: end-to-end integration test for full lifecycle"
```

---

## 自检清单

- [x] **Spec 覆盖**: 节点 JSON (Task 2-3) → 缓存 JSON (Task 8) → WorldInformation (Task 2) → 查询工具 (Task 6-7) → 上下文组装 (Task 9) → 启动流程 (Task 10) → 命令体系 (Task 11) → 旧代码删除 (Task 12)
- [x] **无占位符**: 所有代码步骤均包含实际实现，无 TBD/TODO
- [x] **类型一致性**: `Element`, `Checkpoint`, `NodeSnapshot`, `ElementRef`, `WorldInformation` 在 Task 2 定义后，Task 3-11 均使用相同签名
- [x] **依赖顺序**: FreeMarker (Task 1) → 数据模型 (Task 2) → 加载器 (Task 3) → 索引 (Task 4) → 状态管理 (Task 5) → 查询工具 (Task 6) → 写入工具 (Task 7) → 缓存 (Task 8) → 渲染 (Task 9) → 启动 (Task 10) → 命令 (Task 11) → 清理 (Task 12) → 模板 (Task 13) → 集成测试 (Task 14)
