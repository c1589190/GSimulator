# doc 工具输出缓存链接机制 — 实施计划

## 目标

doc 工具（doc_read, doc_crop, doc_template）输出大段文本时，自动缓存到本地文件，返回 `@cache:{id}` 短引用。LLM 在后续工具调用中直接用引用替代复制全文，节省 Token 并避免抄写错误。缓存文件存储本地，方便调试。

## 设计

### 引用格式

```
@cache:{cache_id}
```

### 缓存存储

```
data/docs/.cache/
├── crop_20260701_120000_a1b2c3d4.txt
├── read_20260701_120005_e5f6g7h8.txt
└── tmpl_20260701_120010_i9j0k1l2.txt
```

### ID 格式

```
{tool}_{timestamp}_{random8}.txt
例: crop_20260701_120000_a1b2c3d4.txt
```

### 流转示意

```
┌─ doc_crop(lines="1-6, 11-14", mask_words="董卓,七星刀") ─┐
│                                                             │
│  1. 执行裁剪，生成完整文本 (800 chars)                       │
│  2. 写入 data/docs/.cache/crop_20260701_120000_a1b2c3d4.txt │
│  3. 返回: "[@cache:crop_20260701_120000_a1b2c3d4]           │
│             # 预览 (前200字)..."                            │
└─────────────────────────────────────────────────────────────┘
                    │
                    ▼ LLM 看到缓存引用
                    │
┌─ dispatch_sub_agent(prompt="@cache:crop_20260701_...") ─┐
│                                                           │
│  1. 检测 prompt 以 @cache: 开头                           │
│  2. 读取缓存文件 → 完整文本                                │
│  3. 用完整文本替换 @cache: 引用                            │
│  4. 正常派发 SubAgent                                     │
└───────────────────────────────────────────────────────────┘
```

## 分步任务

### Step 1: DocCacheManager

**文件**: `src/main/java/com/gsim/doc/DocCacheManager.java`

```
- cacheDir: Path (data/docs/.cache/)
- put(toolName, text): String → 写入文件，返回 cacheId
- get(cacheId): String → 读取文件内容
- resolve(text): String → 检测 @cache: 引用并替换为实际内容
- list(): List<CacheInfo> → 列出所有缓存
- cleanupOlderThan(days): int → 清理过期缓存
```

`resolve(String text)` 的行为：
- 如果 text 以 `@cache:` 开头 → 提取 ID → 读取文件 → 返回文件内容
- 如果 text 中包含 `@cache:xxx`（内嵌引用）→ 替换为文件内容
- 如果 text 不含 `@cache:` → 原样返回

### Step 2: 修改产出缓存的工具

**doc_crop / doc_read / doc_template** 的 execute() 方法：

当前返回完整文本在 snippet 中。修改为：
1. 执行操作，得到完整文本
2. 调用 `cacheManager.put(toolName, fullText)` → 得到 cacheId
3. 返回时 snippet 改为：
   ```
   [@cache:{cacheId}]
   {fullText 的前 200 字符预览}...
   ```
4. item.title 末尾追加 ` (已缓存)` 标记

### Step 3: 修改消费缓存的工具参数

**dispatch_sub_agent.prompt** 参数解析：

在 `DispatchSubAgentTool.execute()` 中，拿到 prompt 参数后：
```java
String prompt = call.param("prompt", "").trim();
prompt = cacheManager.resolve(prompt);  // 解析 @cache: 引用
```

**doc_write.content** 参数解析：
```java
String content = call.param("content", "").trim();
content = cacheManager.resolve(content);
```

**doc_create.content** 参数解析：
同上。

### Step 4: ToolRegistry 注入 DocCacheManager

在 `GSimulatorApplication` 中创建 `DocCacheManager` 实例，注入到需要它的工具中：
- DocCropTool
- DocReadTool
- DocTemplateTool
- DispatchSubAgentTool
- DocWriteTool
- DocCreateTool

### Step 5: 更新 Orchestrator 提示词

在 system prompt 中新增 `@cache:` 使用说明，放在工具调用规则章节：

```
### 缓存链接 (@cache:)

某些工具（doc_read, doc_crop, doc_template）输出大量文本时，
会自动缓存为文件并返回 "@cache:{id}" 引用地址。

你应当优先使用 @cache: 引用而非复制全文：
- ✅ dispatch_sub_agent(prompt="@cache:crop_a1b2c3d4")
- ❌ dispatch_sub_agent(prompt="[复制粘贴800字全文...]")

接受文本参数的其他工具（如 doc_write、doc_create）同样支持 @cache: 引用。
```

### Step 6: 测试

| 测试 | 内容 |
|------|------|
| `DocCacheManagerTest` | put/get/resolve/cleanup |
| `DocCropCacheTest` | doc_crop 产出 @cache: 引用 |
| `CacheResolveTest` | dispatch_sub_agent 解析 @cache: 引用 |
| 集成测试 | 完整流程：crop → @cache → dispatch |

## 涉及文件

### 新增
- `src/main/java/com/gsim/doc/DocCacheManager.java`

### 修改
- `src/main/java/com/gsim/doc/tool/DocCropTool.java` — 注入 cacheManager，输出缓存引用
- `src/main/java/com/gsim/doc/tool/DocReadTool.java` — 同上
- `src/main/java/com/gsim/doc/tool/DocTemplateTool.java` — 同上
- `src/main/java/com/gsim/doc/tool/DocWriteTool.java` — content 参数解析 @cache:
- `src/main/java/com/gsim/doc/tool/DocCreateTool.java` — content 参数解析 @cache:
- `src/main/java/com/gsim/agent/tool/DispatchSubAgentTool.java` — prompt 参数解析 @cache:
- `src/main/java/com/gsim/app/GSimulatorApplication.java` — 创建 DocCacheManager，注入各工具
- `src/main/resources/gsim/agents/orchestrator/config.json` — 新增 @cache: 使用说明
