# 统一 docs 工具组 — 实施计划

## 目标

将 `skill_mgmt` 工具组泛化为统一的 `docs` 工具组，使 Agent 能够以一致的方式管理所有类型的文本资产（角色设定、Skill、世界态势、模板等），并新增 `doc_crop` 工具支持行级裁剪 + 关键词遮蔽 + 选择性替换，为后续多角色推演提供上下文视图生成能力。

## 当前状态 vs 目标状态

| 维度 | 当前 | 目标 |
|------|------|------|
| 工具组 | `skill_mgmt`（6 个工具） | `docs`（8 个工具） |
| 数据模型 | `SkillMeta` | `Document`（SkillMeta 的超集） |
| 存储路径 | `data/skills/{name}/SKILL.md` | `data/docs/{type}/{id}.md` |
| 工具命名 | `skill_list`, `skill_read`, ... | `doc_list`, `doc_read`, `doc_crop`, ... |
| 搜索 | 仅 skill_search | doc_search 支持全文档类型搜索 |
| 裁剪 | 无 | `doc_crop`（行裁剪 + 关键词遮蔽 + 选择性替换） |

## 分步任务

### Step 1: Document 数据模型

**文件**: `src/main/java/com/gsim/doc/Document.java`

```java
public record Document(
    String id,           // 唯一标识，如 "char_caocao"
    DocType type,        // CHARACTER | SKILL | WORLD_STATE | TEMPLATE | CONTEXT | RULE
    String title,        // 人类可读标题
    String content,      // Markdown 正文
    List<String> tags,   // 标签
    int version,         // 版本号
    String updatedAt     // ISO 时间戳
) {}
```

`DocType` 枚举：`CHARACTER`, `SKILL`, `WORLD_STATE`, `TEMPLATE`, `CONTEXT`, `RULE`, `OTHER`

与旧 `SkillMeta` 的关系：SkillMeta 的字段（id, name, description, tags, version）全部映射到 Document，`name` → `title`，`description` → 去掉（正文前 200 字作为摘要），新增 `type` 和 `content`。

### Step 2: DocStore（统一存储层）

**文件**: `src/main/java/com/gsim/doc/DocStore.java`

```
存储路径：data/docs/{type}/{id}.md

文件格式：
---
type: character
title: 曹操 · 角色设定
tags: [角色, 魏, 君主]
version: 3
updated: 2026-07-01T12:00:00
---
曹操，字孟德，沛国谯县人...

# 性格
...

# 目标
...
```

- YAML frontmatter 存元数据，Markdown body 存正文
- `DocStore` 提供 CRUD：list(filter), read(id), create(meta, content), write(id, content), delete(id)
- 内部维护内存缓存 + 文件系统持久化
- 启动时扫描 `data/docs/` 重建索引

### Step 3: 统一 doc_* 工具实现

**新文件目录**: `src/main/java/com/gsim/doc/tool/`

| 工具 | 文件 | 核心逻辑 |
|------|------|---------|
| `doc_list` | `DocListTool.java` | 按 type/tag 过滤列出文档摘要 |
| `doc_read` | `DocReadTool.java` | 按 offset/limit 分段读取（支持行号） |
| `doc_create` | `DocCreateTool.java` | 从内容 + 元数据创建文档 |
| `doc_write` | `DocWriteTool.java` | 替换全文 / 追加 / 按行范围覆盖 |
| `doc_search` | `DocSearchTool.java` | 全文关键词 + 语义搜索（复用现有 SkillIndex） |
| `doc_index` | `DocIndexTool.java` | 重建语义索引 |
| `doc_crop` | `DocCropTool.java` | **新增** — 行裁剪 + 关键词遮蔽 + 选择性替换 |
| `doc_template` | `DocTemplateTool.java` | 从 `resources/gsim/templates/` 实例化模板 |

### Step 4: doc_crop 工具详细设计

```
doc_crop(
  source:      "turn_5_world_state",   ← 源文档 ID
  lines:       "1-6, 11-14, 20",      ← 保留行范围（逗号分隔，- 表示区间）
  mask_words:  ["王允", "七星刀",      ← 遮蔽为 *** 的关键词列表
                "兖州豪强"],
  mask_lines:  ["8-9"],               ← 整行替换为 ***
  rename: {                           ← 选择性替换（保持叙事连贯）
    "曹操的密使": "一位神秘来客",
    "许都": "某地"
  }
)
→ 返回裁剪后的纯文本（不修改原文档）
```

**实现要点：**
- `lines` 解析：支持 `"1-6, 11-14, 20"` 格式
- `mask_words`：简单字符串替换，大小写不敏感
- `mask_lines`：整行替换为 `[此行内容不可见]` 或单纯 `***`
- `rename`：先于 mask_words 执行，避免把要保留的替换词也遮蔽掉
- 执行顺序：保留指定行 → rename 替换 → mask_words 遮蔽 → mask_lines 整行遮蔽
- 输出格式：纯文本，行号重新编号（1, 2, 3...）

### Step 5: 迁移现有 Skill 系统

**策略：替换而非兼容**

1. 删除 `skill/tool/` 下 6 个旧工具
2. 删除 `skill/SkillMeta.java`（被 Document 替代）
3. 保留 `skill/SkillIndex.java`（语义搜索能力被 doc_search 复用）
4. 新 `doc_*` 工具注册到 ToolRegistry
5. 更新 `ToolGroup`：删除 `SKILL_MGMT`，新增 `DOCS` 组
6. 更新 `ToolCategoryRegistry`：替换 skill_* 映射为 doc_* 映射

### Step 6: 调整工具组

**旧的 5 组：**
```
world_info | node_mgmt | import_doc | search | skill_mgmt
```

**新的 5 组：**
```
world_info | node_mgmt | import_doc | search | docs
```

`docs` 组成员：`doc_list`, `doc_read`, `doc_create`, `doc_write`, `doc_search`, `doc_index`, `doc_crop`, `doc_template`

默认工具集也更新：`skill_list` → `doc_list`，`skill_read` → `doc_read`，等。

### Step 7: 更新 Orchestrator 系统提示词

- 将 prompt 中所有 `skill_*` 工具引用替换为 `doc_*`
- 新增 `doc_crop` 使用说明和示例
- 更新工具组目录表

### Step 8: 数据迁移

- 启动时检测 `data/skills/` 目录
- 如有旧 Skill 文件，自动迁移到 `data/docs/skill/` 格式
- 迁移完成后不删除旧目录（用户手动确认后删除）

### Step 9: 测试

| 测试范围 | 测试文件 | 内容 |
|---------|---------|------|
| DocStore CRUD | `DocStoreTest.java` | 创建/读取/列表/按 tag 过滤 |
| doc_crop 逻辑 | `DocCropToolTest.java` | 行裁剪、遮蔽、替换的各种组合 |
| 工具注册 | `DocsToolGroupTest.java` | 8 个工具正确注册到 ToolRegistry |
| 迁移 | `SkillMigrationTest.java` | 旧 Skill 文件 → 新 Document 格式 |
| 集成 | 现有 `ToolGroupManagerTest` 更新 | 验证新工具组路由 |

## 执行顺序

```
Step 1: Document 模型          (基础依赖)
  │
Step 2: DocStore               (基础依赖)
  │
Step 3: doc_list, doc_read      (最先实现，后续工具依赖)
  │
Step 4: doc_create, doc_write   (管理能力)
  │
Step 5: doc_search, doc_index   (搜索能力)
  │
Step 6: doc_template            (模板能力)
  │
Step 7: doc_crop                (核心新增能力)
  │
Step 8: 删除旧 skill 工具
  │
Step 9: 更新 ToolGroup + ToolCategoryRegistry
  │
Step 10: 更新 Orchestrator prompt
  │
Step 11: 数据迁移逻辑
  │
Step 12: 测试 + 文档
```

## 涉及文件清单

### 新增
- `src/main/java/com/gsim/doc/Document.java`
- `src/main/java/com/gsim/doc/DocType.java`
- `src/main/java/com/gsim/doc/DocStore.java`
- `src/main/java/com/gsim/doc/tool/DocListTool.java`
- `src/main/java/com/gsim/doc/tool/DocReadTool.java`
- `src/main/java/com/gsim/doc/tool/DocCreateTool.java`
- `src/main/java/com/gsim/doc/tool/DocWriteTool.java`
- `src/main/java/com/gsim/doc/tool/DocSearchTool.java`
- `src/main/java/com/gsim/doc/tool/DocIndexTool.java`
- `src/main/java/com/gsim/doc/tool/DocCropTool.java`
- `src/main/java/com/gsim/doc/tool/DocTemplateTool.java`

### 修改
- `src/main/java/com/gsim/agent/ToolGroup.java` — 替换 SKILL_MGMT → DOCS
- `src/main/java/com/gsim/agent/ToolCategoryRegistry.java` — 更新工具分类
- `src/main/java/com/gsim/app/GSimulatorApplication.java` — 注册新工具
- `src/main/resources/gsim/agents/orchestrator/config.json` — 更新 prompt
- `src/main/resources/gsim/prompts/orchestrator-system.md` — 更新 prompt

### 删除
- `src/main/java/com/gsim/skill/SkillMeta.java`
- `src/main/java/com/gsim/skill/tool/SkillListTool.java`
- `src/main/java/com/gsim/skill/tool/SkillReadTool.java`
- `src/main/java/com/gsim/skill/tool/SkillCreateTool.java`
- `src/main/java/com/gsim/skill/tool/SkillWriteTool.java`
- `src/main/java/com/gsim/skill/tool/SkillSearchTool.java`
- `src/main/java/com/gsim/skill/tool/SkillIndexTool.java`

### 保留（复用）
- `src/main/java/com/gsim/skill/SkillIndex.java` — 语义搜索索引引擎，被 DocSearchTool 复用
