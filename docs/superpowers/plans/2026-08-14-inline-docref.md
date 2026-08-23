# 内嵌文档引用 + 大文本暂存 + core.properties 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现三件套——`@doc:`/`@import:` 内容内嵌引用（写入时快照展开）、`write_element` 超阈值大文本暂存 docs 并引导 `@doc:` 二次提交、core.properties 全局配置系统（内置默认 + 启动落盘）。

**Architecture:** 解析器 `InlineRefResolver` 放 core（依赖 DocStore + ImportDocumentService，零新依赖边）；`CoreConfig` 放 core（classpath 默认 + 外部覆盖）；`write_element` 工具在 @cache: 展开后、引用展开前做阈值检查，超阈值暂存为 `docs/tmp/wstg_*.md`（DocType.TMP）不写入；app 启动落盘 core.properties 模板并组装 resolver 注入工具链。@cache: 写入端 5 处停用（消费端保留）。

**Tech Stack:** Java 21 + Maven 多模块（gsim-core / gsim-agent / gsim-app），JUnit 5，离线文件操作测试。

**Spec:** `docs/superpowers/specs/2026-08-14-inline-docref-design.md`（本计划从 spec 论证，执行者需同时读两者）

## Global Constraints

- 依赖方向禁令（终态）：core 不得依赖 agent/agentlib/map；map 不得依赖 agent/agentlib（判定：源码 import 零命中 + pom 无依赖边）
- 编译严格模式：`-Xlint:all -Werror`（未使用 import 即失败）；spotless palantirJavaFormat（verify 阶段检查）
- 语法（照抄 spec）：`@doc:"<docId>[.md]"` 剥 .md（不区分大小写）；`@import:"<relativePath>"` 必须完整后缀；引号必带；无引号形态不识别原样保留；引号内任意字符（除 `"`）；第一版不递归展开
- 严格模式：任一 `@doc:`/`@import:` 引用未解析 → `[@DOC_REF_FAILED]` 整体拒绝写入，信息单元不被修改
- 阈值口径：@cache: 展开后、@doc: 展开前；默认 500 字符（`core.doc.staging.threshold`）；恰为阈值不暂存、超阈值暂存
- 超阈值行为：只暂存不写入；暂存 `docs/tmp/{wstg_docId}.md`（DocType.TMP，title = 信息单元地址 `nodeId:checkpointId:key`）
- core.properties：jar 内置默认 + 启动落盘模板（已存在不覆盖）；外部文件覆盖 classpath 默认
- @cache: 写入端全停（doc_read / doc_crop / doc_template / resolve_ref / text_edit 五处 put）；`DocCacheManager`、`docs/.cache/`、`resolve()`/`resolveDocId()` 消费端保留
- docId 正则：`^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*$`（嵌套路径，每层字母数字连字符下划线）
- 测试离线：本计划全部测试为纯文件/内存操作，不依赖外部服务
- SDD 执行约定：implementer 只改代码与测试，**不运行 mvn/调试命令**；测试命令由控制器执行（写测试 → 控制器跑失败 → implementer 实现 → 控制器跑通过）

---

### Task 1: InlineRefResolver（core 层内嵌引用解析器）

**Files:**
- Create: `gsim-core/src/main/java/com/gsim/core/ref/InlineRefResolver.java`
- Create: `gsim-core/src/test/java/com/gsim/core/ref/InlineRefResolverTest.java`

**Interfaces:**
- Consumes: `com.gsim.core.doc.DocStore`（`get(String id)` 返回 `Document` 或 null）、`com.gsim.core.importing.ImportDocumentService`（`readDocument(String documentId, int offset, int limit, boolean full)`，文件不存在抛 `ImportDocumentException`，返回 `ImportDocumentReadResult`（第 10 字段 `content()`））
- Produces: `com.gsim.core.ref.InlineRefResolver`（Task 5/6 使用）：
  ```java
  public final class InlineRefResolver {
      public record ResolveResult(String text, List<String> unresolved) {}
      public InlineRefResolver(DocStore docStore, ImportDocumentService importService)
      public ResolveResult resolve(String text)
  }
  ```

- [ ] **Step 1: 写失败测试** `InlineRefResolverTest.java`

测试数据准备：`@TempDir` 下建 `docsDir/`（构造 `DocStore` 并 `init()`，预置文档）与 `importDir/`（构造 `ImportDocumentService`，预置文件）。测试用例（全部断言在 `resolve()` 上）：

```java
// 1. @doc:"xxx" 展开为文档全文
// 2. @doc:"xxx.md" 与 @doc:"xxx" 等价（剥 .md，含 ".MD" 大写）
// 3. @doc:"第一层/第二层/文件名" 嵌套路径寻址（DocStore 预置嵌套 docId）
// 4. @import:"path/to/file.md" 按完整相对路径展开（importDir 预置子目录文件）
// 5. 多引用混合文本（"前文 @doc:\"a\" 中段 @import:\"b.md\" 后文"）原位替换，顺序正确
// 6. 引用的文档不存在 → unresolved 含引用原文，text 不被部分替换（原样返回）
// 7. @import:"../secret.md" 路径逃逸 → unresolved（ImportDocumentException 归一为未解析）
// 8. 无引号 @doc:xxx 正文形态 → 原样保留，unresolved 为空（不误伤）
// 9. @import:"noext" 无后缀 → unresolved；@doc:"a.txt" → docId=a.txt 查 store（null）→ unresolved
// 10. 空文本 / null → 原样返回，unresolved 空
```

- [ ] **Step 2: 实现** `InlineRefResolver.java`

解析逻辑（核心算法）：

```java
public ResolveResult resolve(String text) {
    if (text == null || text.isBlank()) return new ResolveResult(text, List.of());
    List<String> unresolved = new ArrayList<>();
    StringBuilder out = new StringBuilder();
    int pos = 0;
    while (pos < text.length()) {
        int at = text.indexOf('@', pos);
        if (at < 0) { out.append(text, pos, text.length()); break; }
        out.append(text, pos, at);
        String prefix = null;
        if (text.startsWith("@doc:", at)) prefix = "@doc:";
        else if (text.startsWith("@import:", at)) prefix = "@import:";
        if (prefix == null) { out.append(text, at, at + 1); pos = at + 1; continue; }
        // 引号必须紧随前缀（允许零个空格）：@doc:"xxx"
        int q1 = at + prefix.length();
        while (q1 < text.length() && text.charAt(q1) == ' ') q1++;
        if (q1 >= text.length() || text.charAt(q1) != '"') {
            // 无引号形态：不识别，按普通文本推进（不报错）
            out.append(text, at, q1); pos = q1; continue;
        }
        int q2 = text.indexOf('"', q1 + 1);
        if (q2 < 0) { out.append(text, at); pos = at + 1; continue; } // 引号未闭合 → 原样
        String inner = text.substring(q1 + 1, q2);
        String resolved = tryResolve(prefix, inner);   // null = 未解析
        if (resolved == null) {
            unresolved.add(text.substring(at, q2 + 1)); // 引用原文（含引号）
            out.append(text, at, q2 + 1);               // 原样保留（不部分替换）
        } else {
            out.append(resolved);
        }
        pos = q2 + 1;
    }
    return new ResolveResult(out.toString(), unresolved);
}

private String tryResolve(String prefix, String inner) {
    try {
        if ("@doc:".equals(prefix)) {
            String docId = inner.toLowerCase().endsWith(".md")
                    ? inner.substring(0, inner.length() - 3) : inner;
            Document doc = docStore.get(docId);
            return doc == null ? null : doc.content();
        } else { // @import:
            ImportDocumentReadResult r =
                    importService.readDocument(inner, 0, Integer.MAX_VALUE, false);
            return r.content();
        }
    } catch (Exception e) {  // ImportDocumentException（不存在/不支持类型）等 → 未解析
        return null;
    }
}
```

注意：`@doc:` 剥 `.md` 用 `endsWith(".md")` 不区分大小写；docId 剥后缀后不得为空（`"@doc:\".md\""` → 空 docId → 未解析）；`resolved` 为 null 与空字符串的区分——空文档返回空串（合法展开），未解析返回 null。

- [ ] **Step 3: Commit**（控制器验证测试通过后）
  `git add gsim-core/src/main/java/com/gsim/core/ref/InlineRefResolver.java gsim-core/src/test/java/com/gsim/core/ref/InlineRefResolverTest.java`
  `git commit -m "feat: InlineRefResolver 内嵌 @doc:/@import: 引用解析（core）"`

**控制器验证**：`mvn -pl gsim-core test -Dtest=InlineRefResolverTest`（预期全绿）；`mvn -pl gsim-core clean test`（回归无破）

---

### Task 2: DocType.TMP + 嵌套 docId 工具正则

**Files:**
- Modify: `gsim-core/src/main/java/com/gsim/core/doc/DocType.java`（加 TMP 枚举）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocCreateTool.java:79`（正则）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocReadTool.java:73`（正则）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocIndexTool.java:64`（正则）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocTemplateTool.java:83`（正则）
- Create: `gsim-core/src/test/java/com/gsim/core/doc/DocStoreNestedPathTest.java`
- Create: `gsim-agent/src/test/java/com/gsim/agent/tools/doc/DocCreateToolTest.java`

**Interfaces:**
- Consumes: `DocStore.create(String id, DocType type, String title, String content, List<String> tags)`（writeToFile 已有 `Files.createDirectories(file.getParent())`——子目录写路径天然支持，无需改 DocStore 本体）
- Produces: `DocType.TMP("tmp", "暂存")`（Task 5 暂存用）；嵌套 docId 工具校验（Task 5 的 @doc 嵌套引用依赖 resolver 走 store.get，本任务保证嵌套 docId 可经工具创建/读取）

- [ ] **Step 1: DocType 加 TMP**：`TMP("tmp", "暂存")` 枚举项（放在 OTHER 之前）。`DocCreateTool` 的 type 参数描述**不加** TMP（暂存专用，用户不可见）

- [ ] **Step 2: 4 处正则放宽**：`"^[a-zA-Z0-9_-]+$"` → `"^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*$"`（DocCreateTool/DocReadTool/DocIndexTool/DocTemplateTool 各一处）。错误消息保持原有文案

- [ ] **Step 3: 写失败测试**

`DocStoreNestedPathTest.java`（core，`com.gsim.core.doc` 包）：
```java
// 1. create("a/b/c", DocType.OTHER, ...) → docsDir/other/a/b/c.md 落盘，get("a/b/c") 读回内容
// 2. 新 DocStore 实例 init() 后（模拟重启）get("a/b/c") 仍可读（递归扫描 + 相对路径 docId）
```

`DocCreateToolTest.java`（agent，`com.gsim.agent.tools.doc` 包，构造 `new DocCreateTool(docStore, cacheManager)`——cacheManager 传 null 即可）：
```java
// 1. doc_create("a/b/c", title=..., content=...) → success，store.get("a/b/c") 非空
// 2. doc_create("a//b") → fail（空段）
// 3. doc_create("a/../b") → fail（穿越）
// 4. doc_create("a/b.") → fail（非法字符）
// 5. doc_create("abc") 顶层仍通过
```
（实现细节参考 DocCreateTool.execute 现有结构；确认 DocCreateTool 构造器签名后编写）

- [ ] **Step 4: Commit**（控制器验证后）
  `git add gsim-core/src/main/java/com/gsim/core/doc/DocType.java gsim-core/src/test/java/com/gsim/core/doc/DocStoreNestedPathTest.java gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocCreateTool.java gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocReadTool.java gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocIndexTool.java gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocTemplateTool.java gsim-agent/src/test/java/com/gsim/agent/tools/doc/DocCreateToolTest.java`
  `git commit -m "feat: DocType.TMP + doc 工具嵌套 docId 正则（a/b/c）"`

**控制器验证**：`mvn -pl gsim-core,gsim-agent -am test`（相关测试 + 回归）

---

### Task 3: CoreConfig + core.properties（core 层配置系统）

**Files:**
- Create: `gsim-core/src/main/java/com/gsim/core/config/CoreConfig.java`
- Create: `gsim-core/src/main/resources/core.properties`
- Create: `gsim-core/src/test/java/com/gsim/core/config/CoreConfigTest.java`

**Interfaces:**
- Consumes: 无（零依赖；classpath 资源加载用 `CoreConfig.class.getResourceAsStream("/core.properties")`）
- Produces: `com.gsim.core.config.CoreConfig`（Task 5/6 使用）：
  ```java
  public final class CoreConfig {
      public static final String STAGING_THRESHOLD = "core.doc.staging.threshold";
      public static CoreConfig load()                 // 仅 classpath 默认
      public static CoreConfig load(Path externalFile) // classpath 默认 + 外部覆盖
      public String get(String key)                   // 无则 null
      public int getInt(String key, int defaultValue)  // 非法数值回退默认
  }
  ```

- [ ] **Step 1: 写失败测试** `CoreConfigTest.java`

```java
// 1. CoreConfig.load() → getInt(STAGING_THRESHOLD, -1) == 500（classpath 默认）
// 2. 外部文件（@TempDir 写 core.properties 含 core.doc.staging.threshold=100）load(external) → 100（覆盖）
// 3. 外部文件缺失路径 load(不存在的 path) → 默认 500（外部文件不存在时静默跳过）
// 4. 外部文件部分 key（只含不相关 key）→ 阈值仍为默认 500
// 5. 外部文件阈值非法（"abc"）→ 回退默认 500
// 6. get("不存在的key") → null
```

- [ ] **Step 2: 写失败资源** `core.properties`

```properties
# GSimulator core 配置（内置默认；应用启动时复制到工作目录，外部文件覆盖本默认值）
core.doc.staging.threshold=500
```

- [ ] **Step 3: 实现** `CoreConfig.java`

```java
public final class CoreConfig {
    public static final String STAGING_THRESHOLD = "core.doc.staging.threshold";
    private static final String RESOURCE = "/core.properties";
    private final Map<String, String> values;

    private CoreConfig(Map<String, String> values) { this.values = Map.copyOf(values); }

    public static CoreConfig load() {
        Map<String, String> m = new LinkedHashMap<>();
        try (var in = CoreConfig.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                p.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
            }
        } catch (IOException e) {
            // classpath 资源不可读时以空默认继续（不应发生）
        }
        return new CoreConfig(m);
    }

    public static CoreConfig load(Path externalFile) {
        CoreConfig base = load();
        if (externalFile == null || !Files.isRegularFile(externalFile)) return base;
        Map<String, String> m = new LinkedHashMap<>(base.values);
        try {
            Properties p = new Properties();
            p.load(Files.newBufferedReader(externalFile));
            p.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
        } catch (IOException e) {
            // 外部文件不可读时用 classpath 默认
        }
        return new CoreConfig(m);
    }

    public String get(String key) { return values.get(key); }

    public int getInt(String key, int defaultValue) {
        String v = values.get(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
```

- [ ] **Step 4: Commit**（控制器验证后）
  `git add gsim-core/src/main/java/com/gsim/core/config/CoreConfig.java gsim-core/src/main/resources/core.properties gsim-core/src/test/java/com/gsim/core/config/CoreConfigTest.java`
  `git commit -m "feat: CoreConfig + core.properties 全局配置（内置默认+外部覆盖）"`

**控制器验证**：`mvn -pl gsim-core test -Dtest=CoreConfigTest`（全绿）+ 回归

---

### Task 4: @cache: 写入端停用（5 处）

**Files:**
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocReadTool.java`（删 put + `[@cache:...]` 输出段）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocCropTool.java`（删 put 及返回值中的 cacheId 引用）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocTemplateTool.java`（删 put 及返回值中的 cacheId 引用）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/ref/ResolveRefTool.java`（删 put 及返回值中的 cacheId 引用）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/text/TextEditTool.java`（删 put 及返回值中的 cacheId 引用）

**Interfaces:**
- Consumes: 无（纯删除；`cacheManager` 字段/构造器**保留**——消费端 `resolve()`/`resolveDocId()` 仍被使用）
- Produces: 无新接口；doc_read 返回值不再含 `[@cache:...]` 提示

- [ ] **Step 1: 逐文件删除 put 调用与相关输出逻辑**

以 DocReadTool 为例（其余四处同模式）：
- 删除 execute 中 `cacheId = cacheManager.put("read", ...)` 及其 if 块（第 102-108 行区域）
- 删除输出组装中对 cacheId 的分支（`if (cacheId != null) output.append("[@cache:"...)` 及结尾"使用 @cache:" 提示）
- `cacheManager.resolveDocId(docId)`（@cache: 消费端，DocReadTool 第 67 行）**保留**
- 其余文件：删除 `put(...)` 调用；如返回值/消息引用了 cacheId 变量则一并删除该变量与引用；`resolveDocId`/`resolve` 消费端保留
- 每处删除后清理不再使用的 import（-Werror 会拦截未使用 import）

- [ ] **Step 2: 检查本任务范围**：`grep -rn "\.put(" gsim-agent/src/main/java/ | grep cacheManager` 应只剩消费端（resolveDocId/resolve 调用）或零命中；`cacheManager` 字段与构造器参数**不得删除**（WriteElementTool 等消费端仍用）

- [ ] **Step 3: Commit**（控制器验证后）
  `git add gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocReadTool.java gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocCropTool.java gsim-agent/src/main/java/com/gsim/agent/tools/doc/DocTemplateTool.java gsim-agent/src/main/java/com/gsim/agent/tools/ref/ResolveRefTool.java gsim-agent/src/main/java/com/gsim/agent/tools/text/TextEditTool.java`
  `git commit -m "refactor: @cache: 写入端停用（5 处 put 移除，消费端保留）"`

**控制器验证**：`mvn -pl gsim-agent -am test`（回归全绿；测试中无 @cache 断言，预期无测试改动）

---

### Task 5: WriteElementTool 扩展（阈值暂存 + 引用展开）

**Files:**
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/worldinfo/WriteElementTool.java`
- Modify: `gsim-agent/src/test/java/com/gsim/agent/tools/worldinfo/WriteElementToolTest.java`（4 处构造点更新 + 新用例）

**Interfaces:**
- Consumes: `InlineRefResolver`（Task 1：`ResolveResult.resolve(String)`）、`CoreConfig`（Task 3：`STAGING_THRESHOLD`、`getInt`）、`DocStore.create(String, DocType, String, String, List<String>)`、`DocType.TMP`（Task 2）
- Produces: 新构造器签名（Task 6 使用）：
  ```java
  public WriteElementTool(Supplier<WorldInformation> worldInfo, Path worldsDir,
          DocCacheManager cacheManager, DocStore docStore,
          InlineRefResolver inlineRefResolver, CoreConfig coreConfig)
  ```

- [ ] **Step 1: 改构造器与字段**：新增 `docStore`、`inlineRefResolver`、`coreConfig` 三个字段与构造参数（`cacheManager` 保持可为 null——现有测试传 null；新增参数均非 null）

- [ ] **Step 2: 写失败测试**（更新 WriteElementToolTest 全部现有构造点 + 新增用例）

现有 4 处构造更新为：
```java
DocStore docStore = new DocStore(docsDir); // @TempDir 子目录
docStore.init();
var resolver = new InlineRefResolver(docStore, new ImportDocumentService(importDir));
var tool = new WriteElementTool(() -> wi, tmpDir, null, docStore, resolver, CoreConfig.load());
```
（`setUp` 中统一准备 docsDir/importDir；`ImportDocumentService` 构造不需要 importDir 存在）

新增用例：
```java
// 1. 超阈值暂存：value = 501 字符 → r.success()，返回提示含 "wstg_write_" 与 "@doc:\""；
//    wi.checkpointHistory("worldview") 仍为空（未写入 world）；docsDir/tmp/ 下存在 wstg_*.md，
//    DocStore 中 get 该 docId 内容 = value
// 2. 阈值内直接写入：value = 500 字符 → 正常写入 world（history 有 1 条）
// 3. 边界：value 恰 500 不暂存；501 暂存
// 4. @doc: 引用展开：docStore 预置文档 "设定集"，value = "@doc:\"设定集\"" →
//    写入的 element.value == 文档全文（快照）
// 5. 引用不存在：value = "@doc:\"不存在的\"" → r.success() == false，消息含 "[@DOC_REF_FAILED]"，
//    history 为空（未写入）
// 6. 暂存→二次提交链路：先写 501 字符 value（暂存）→ 从返回提示提取 docId →
//    value = "@doc:\"<docId>\"" 二次调用 → history 有 1 条且值 = 原 501 字符全文
// 7. @doc: 与 @import: 共存：value = "前文 @doc:\"设定集\" 中段 @import:\"附件.txt\" 后文" →
//    展开后三部分拼接正确（importDir 预置 附件.txt）
// 8. 无引号形态：value 含 "@doc:xxx"（无引号）→ 原样写入不报错
```

- [ ] **Step 3: 实现 execute 新流程**（在现有 @cache: resolve 之后插入）

```java
// 现有：value = cacheManager.resolve(value);   （保留）
// 新增：阈值检查（@cache: 展开后、@doc: 展开前）
int threshold = coreConfig.getInt(CoreConfig.STAGING_THRESHOLD, 500);
if (value.length() > threshold) {
    return stageToDoc(nodeId, checkpointId, key, value);
}
// 新增：内嵌引用解析
InlineRefResolver.ResolveResult rr = inlineRefResolver.resolve(value);
if (!rr.unresolved().isEmpty()) {
    return ToolResult.fail(name(), "[@DOC_REF_FAILED] 以下引用无法解析: "
            + String.join("; ", rr.unresolved()));
}
value = rr.text();
// 后续正常写入（不变）
```

暂存方法：
```java
private ToolResult stageToDoc(String nodeId, String checkpointId, String key, String value) {
    try {
        String docId = "wstg_write_" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + randomHex(8);
        String title = nodeId + ":" + checkpointId + ":" + key; // 信息单元地址
        Document doc = docStore.create(docId, DocType.TMP, title, value, List.of());
        if (doc == null) { // docId 冲突（极低概率）→ 重试一次：重新生成时间戳+随机再 create
            String retryId = "wstg_write_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    + "_" + randomHex(8);
            doc = docStore.create(retryId, DocType.TMP, title, value, List.of());
            if (doc == null) return ToolResult.fail(name(), "[STAGING_FAILED] 暂存文档创建失败（docId 冲突）");
            docId = retryId;
        }
        log.warn("[WriteElement] value {} chars > threshold, staged to doc {}", value.length(), docId);
        return ToolResult.ok(name(), List.of(new ToolResult.Item(
                "内容已暂存为文档（" + value.length() + " 字符，超过阈值）",
                docId,
                "请在后续调用中使用 write_element(value=\"@doc:\\\"" + docId + "\\\"\") 提交到信息单元 " + title,
                1.0)));
    } catch (IOException e) {
        return ToolResult.fail(name(), "[STAGING_FAILED] 暂存失败: " + e.getMessage());
    }
}
```
（randomHex 私有方法：与 DocCacheManager 同款 `ThreadLocalRandom` 8 位 hex；`java.time` import 用 FQN 或显式 import，注意 -Werror）

- [ ] **Step 4: 清理**：删除不再使用的 import；确认 `cacheManager` null 分支保持（`if (cacheManager != null)`）

- [ ] **Step 5: Commit**（控制器验证后）
  `git add gsim-agent/src/main/java/com/gsim/agent/tools/worldinfo/WriteElementTool.java gsim-agent/src/test/java/com/gsim/agent/tools/worldinfo/WriteElementToolTest.java`
  `git commit -m "feat: write_element 大文本暂存（>500 字符入 docs/tmp）+ @doc/@import 引用展开"`

**控制器验证**：`mvn -pl gsim-agent -am test`（WriteElementToolTest 全绿 + 回归；编译期 gsim-app 会因构造器变化失败——Task 6 处理，控制器验证时用 `mvn -pl gsim-agent -am test` 限定模块）

---

### Task 6: 组装接线（WorldInfoToolContext + AgentBridge + app 落盘）

**Files:**
- Modify: `gsim-agent/src/main/java/com/gsim/agent/bridge/WorldInfoToolContext.java`（加 3 字段）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/bridge/AgentBridge.java`（WriteElementTool 构造传参）
- Modify: `gsim-app/src/main/java/com/gsim/app/GSimulatorApplication.java`（落盘 + CoreConfig + resolver 组装 + WorldInfoToolContext 构造点）
- Create: `gsim-app/src/test/java/com/gsim/app/CorePropertiesTemplateTest.java`

**Interfaces:**
- Consumes: `CoreConfig.load(Path)`（Task 3）、`InlineRefResolver(DocStore, ImportDocumentService)`（Task 1）、WriteElementTool 新构造器（Task 5）
- Produces: `WorldInfoToolContext` 新签名（5 字段 → 8 字段）：
  ```java
  public record WorldInfoToolContext(
          Path worldsDir,
          Supplier<WorldInformation> worldInfoSupplier,
          Supplier<String> activeWorldId,
          DocCacheManager docCacheManager,
          Runnable onNodeChanged,
          DocStore docStore,
          InlineRefResolver inlineRefResolver,
          CoreConfig coreConfig) {}
  ```

- [ ] **Step 1: WorldInfoToolContext 加 3 字段**（如上签名，包内 import 补齐：`com.gsim.core.doc.DocStore`、`com.gsim.core.ref.InlineRefResolver`、`com.gsim.core.config.CoreConfig`）

- [ ] **Step 2: AgentBridge 构造更新**：`new WriteElementTool(wiSupplier, ctx.worldsDir(), ctx.docCacheManager(), ctx.docStore(), ctx.inlineRefResolver(), ctx.coreConfig())`

- [ ] **Step 3: GSimulatorApplication 组装**

在启动流程中（docsDir 确定之后、工具注册之前，参考现有第 117-154 行 DocCacheManager/DocStore 初始化区域）：

```java
// 0. baseDir 确定：AppConfig 中 worldsDir = baseDir/worlds（resolvePath(result.get("worlds.dir"), baseDir, "worlds")），
//    故 baseDir = worldsDir.getParent()（worldsDir 为既有字段，第 83 行 `this.worldsDir = config.worldsDir();`）
Path baseDir = worldsDir.getParent();
// 1. core.properties 落盘（模板）
ensureCorePropertiesTemplate(baseDir);
// 2. CoreConfig（外部文件覆盖）
this.coreConfig = CoreConfig.load(baseDir.resolve("core.properties"));
// 3. InlineRefResolver（用现有 docStore / importDocService 实例）
this.inlineRefResolver = new InlineRefResolver(docStore, importDocService);
// 4. WorldInfoToolContext 构造点传参（现有 230 行附近调用处追加 3 个实参：docStore, inlineRefResolver, coreConfig）
```

落盘方法（static，便于测试）：
```java
static void ensureCorePropertiesTemplate(Path baseDir) throws IOException {
    Path target = baseDir.resolve("core.properties");
    if (Files.exists(target)) return; // 已存在不覆盖
    try (var in = CoreConfig.class.getResourceAsStream("/core.properties")) {
        if (in == null) return; // classpath 缺失则静默跳过（不应发生）
        Files.createDirectories(baseDir);
        Files.copy(in, target);
    }
}
```
（新字段 `coreConfig`、`inlineRefResolver` 按现有字段声明风格添加；docStore/importDocService 变量名以 GSimulatorApplication 现有代码为准——已有 docStore 局部变量（第 154 行 `ctx.getDocStore(docsDir)` 区域）与 importDocService（CoreToolContext 构造实参））

- [ ] **Step 4: 写失败测试** `CorePropertiesTemplateTest.java`（`com.gsim.app` 包，可访问 package-private static 方法）

```java
// 1. 空临时目录 ensureCorePropertiesTemplate(tmp) → tmp/core.properties 生成，内容含 "core.doc.staging.threshold=500"
// 2. 预置已存在 core.properties（内容 "custom=1"）→ ensureCorePropertiesTemplate 后内容不变（不覆盖）
// 3. 落盘后的文件可被 CoreConfig.load(...) 读取（阈值 500）
```

- [ ] **Step 5: Commit**（控制器验证后）
  `git add gsim-agent/src/main/java/com/gsim/agent/bridge/WorldInfoToolContext.java gsim-agent/src/main/java/com/gsim/agent/bridge/AgentBridge.java gsim-app/src/main/java/com/gsim/app/GSimulatorApplication.java gsim-app/src/test/java/com/gsim/app/CorePropertiesTemplateTest.java`
  `git commit -m "feat: 组装接线 — core.properties 落盘 + CoreConfig + InlineRefResolver 注入工具链"`

**控制器验证**：`mvn -pl gsim-app -am test`（app 全模块编译 + 测试全绿）

---

### Task 7: 文档对齐

**Files:**
- Modify: `CLAUDE.md`（doc 系统/配置/工具章节）

- [ ] **Step 1: 更新 CLAUDE.md**

- 配置系统节：新增 `core.properties`（core 层全局配置，jar 内置默认 + 启动落盘工作目录，已存在不覆盖；第一版配置项 `core.doc.staging.threshold=500`）
- 工具系统/WorldInfo 节：`write_element` 行为补注——value 超过 `core.doc.staging.threshold` 时暂存 `docs/tmp/wstg_*.md`（DocType.TMP）并返回 `@doc:` 地址引导二次提交，不直接写入
- Doc 系统描述（Package 说明 core 节）：新增 `InlineRefResolver`（`com.gsim.core.ref` 内嵌 `@doc:`/`@import:` 引用解析）；`@cache:` 写入端已停用说明；DocType 列表加 TMP
- 测试数统计如变化则同步更新（以控制器最终验证实测为准，此步留待控制器收尾时填数）

- [ ] **Step 2: Commit**（控制器验证后）
  `git add -f CLAUDE.md`
  `git commit -m "docs: CLAUDE.md 对齐 — @doc 内嵌引用/大文本暂存/core.properties/@cache 停用"`

**控制器验证**：文档事实性抽查（与代码一致）

---

### 最终验收（控制器执行）

1. `mvn clean verify` BUILD SUCCESS（五模块全绿）
2. 依赖方向 grep 零命中（core→agent/agentlib/map、map→agent/agentlib）
3. 运行冒烟（jar --no-cli，临时工作目录）：
   - 工作目录生成 `core.properties`（含 `core.doc.staging.threshold=500`）
   - doc_create 建嵌套 docId 文档 → doc_read 读回
   - write_element 超阈值 value（>500 字符）→ 返回暂存提示含 `wstg_write_` 与 `@doc:` 地址；worlds 节点文件无该元素
   - 用返回的 docId 二次 `write_element(value="@doc:\"wstg_xxx\"")` → 展开全文写入；query_element 读回内容 = 原大文本
   - 引用不存在（`@doc:"不存在"`）→ `[@DOC_REF_FAILED]` 拒绝
4. 清理临时运行数据，工作区干净
