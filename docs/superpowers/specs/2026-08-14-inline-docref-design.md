# 内容内嵌文档引用（@doc: / @import:）设计

**日期**: 2026-08-14
**状态**: 已批准（用户拍板方案 A，三个澄清决策已定）
**关联**: Phase 2 五模块拆分（feat/tool-mcp-refactor）已完成

## 背景与目标

GSimulator 已有 doc 文档系统（DocStore，`data/docs/{type}/{id}.md`，docId 为标识）与 import 文档目录（ImportDocumentService，任意 .md/.txt 文件，按相对路径引用）。现有引用机制有两类：

- **参数级**：`RefResolver` 解析 `@import:` / `@world:` / `@doc:` / `@cache:`（用于 resolve_ref 工具、text_edit 的 source 参数）——引用是工具的**参数**，不是内容的一部分
- **内嵌展开（仅 @cache:）**：`DocCacheManager.resolve(text)` 把文本中任意位置的 `@cache:id` 替换为缓存全文——`write_element.value` 与 `doc_create.content` 已启用

**目标**：Agent 往 world 信息单元写入内容时，value 中可内嵌 `@doc:"xxx.md"` 或 `@import:"path/file.md"` 引用；写入时（快照语义）自动替换为文档全文后存储。

## 需求（用户原话要点）

1. "我在 docs 里存了一个 xxx.md，我用 MCP 工具给一个地址的信息单元写入了 @doc:"xxx.md"，这个 xxx.md 里面的内容就直接写入 world 对应信息单元了"
2. 引用对象**两者都支持**：doc 系统（docId）与 import 目录（.md 文件）
3. 语法区分：doc 系统**忽略 .md 后缀**（`@doc:"xxx"` 与 `@doc:"xxx.md"` 等价，剥掉后缀查 docId）；import 系统**必须带完整后缀**（`@import:"xxx.md"` 按相对路径查）
4. 前缀沿用现有参数级引用语法族：`@doc:` + `@import:`（不引入新前缀）
5. 展开时机：**写入时展开（快照）**——world 文件里存的是展开后的最终内容，doc 后续变更不回溯
6. 引用不存在：**拒绝写入返回错误**（严格模式，不同于 @cache: 的原样保留）

## 语法规范

内嵌引用形态（引号必须）：

```
@doc:"<docId>[.md]"             → docId = 引号内内容去掉 .md 后缀（不区分大小写），查 DocStore
@import:"<relativePath>"        → 完整相对路径（含后缀），查 import 目录
```

规则：

| 规则 | 行为 |
|------|------|
| 引号 | 必须带引号；无引号形态（如正文中的 `@doc:xxx`）**不识别**，原样保留，不报错 |
| 引号内字符 | 任意字符（除 `"` 本身），支持空格、中文、子目录路径；第一版不支持 `\"` 转义 |
| 多引用 | 一条 value 可含多个引用，按出现顺序逐个原位替换 |
| 递归 | 引用目标内容中若再含 `@doc:`/`@import:` 引用，第一版**不递归展开**，按快照直接写入原文 |
| 后缀 | `@doc:` 剥 `.md`；`@import:` 必须含后缀，无后缀视为无效引用（严格模式报错） |
| 未解析 | 引用存在（引号形态合法）但目标不存在 → 严格模式：整个写入拒绝，返回错误 |
| @cache: 共存 | `@cache:` 保持现有行为（原样保留 + 警告），不并入严格模式；解析顺序与 @cache: 互不干扰 |

## 架构

### 模块与包归属

- **新增**：`com.gsim.core.ref.InlineRefResolver`（core 层，零新依赖边）
  - 输入：`String text`，构造注入 `DocStore` + `ImportDocumentService`
  - 输出：`ResolveResult`（record）：`String text`（展开后文本）+ `List<String> unresolved`（未解析引用原文列表，空 = 全部成功）
  - 职责：识别引号形态 `@doc:"..."` / `@import:"..."`，查存储并替换；复用 `ImportDocumentService` 既有的路径安全校验（normalize + startsWith，防 `../` 逃逸）；**不**复制 `RefResolver` 的参数级解析逻辑（两者职责独立，参数级逻辑不动）
  - 解析失败即收集进 `unresolved`，不做部分替换（要么全成功要么全报告，由调用方决定处理）
- **修改**：`com.gsim.agent.tools.worldinfo.WriteElementTool`
  - 构造器新增 `InlineRefResolver` 参数
  - `execute` 中现有 `cacheManager.resolve(value)` 之后追加内嵌引用解析；`unresolved` 非空 → `ToolResult.fail`（错误消息逐个列出未解析引用），不写入
- **修改**：`com.gsim.agent.bridge.AgentBridge`（组装层）
  - 用 `CoreToolContext` 的 `DocStore` + `ImportDocumentService` 构造 `InlineRefResolver`，注入 `WriteElementTool`

### 数据流

```
write_element(value="@doc:\"三国人物\" ...")
  → cacheManager.resolve(value)            # @cache: 展开（既有行为，不动）
  → inlineRefResolver.resolve(value)       # 新增：@doc:/@import: 原位替换
  → unresolved 非空 ? ToolResult.fail : 正常写入节点 + NodeLoader.save
```

### 错误处理

- 错误码：`[@DOC_REF_FAILED]`，消息列出全部未解析引用原文与原因（docId 不存在 / import 文件不存在 / import 路径逃逸）
- 拒绝写入时原信息单元不被修改（upsert 前校验）
- 日志：WARN 级记录未解析引用（与 @cache: 的日志风格一致）

## 测试策略

**core 层（InlineRefResolverTest）**：
- `@doc:"xxx"` 展开为文档全文；`@doc:"xxx.md"` 与 `@doc:"xxx"` 等价（剥后缀）
- `@import:"path/to/file.md"` 按完整相对路径展开
- 多引用混合文本（正文 + 两个引用交错）原位替换顺序正确
- 引用的文档/文件不存在 → unresolved 非空，文本未被部分替换
- `@import:"../secret.md"` 路径逃逸 → unresolved（安全）
- 无引号 `@doc:xxx` 正文形态 → 原样保留不报错
- `@doc:` 无后缀与 `@import:` 无后缀的差异行为
- 空文本 / null → 原样返回

**agent 层（WriteElementTool 集成测试）**：
- value 含 `@doc:"xxx"` 写入 → 节点文件中存储的是展开后全文（快照验证）
- value 引用不存在文档 → 工具返回错误，节点文件未变化
- `@cache:` 与 `@doc:` 共存于同一条 value 正常展开
- AgentBridge 组装验证（WriteElementTool 拿到正确构造的 resolver）

## 验收标准

1. `write_element` 的 value 含 `@doc:"xxx"` 或 `@doc:"xxx.md"` 写入后，信息单元内容 = 文档全文（快照，不含引用痕迹）
2. `@import:"path/file.md"` 按完整相对路径展开
3. 引用不存在 → 写入拒绝，返回 `[@DOC_REF_FAILED]` 且信息单元未被修改
4. 无引号 `@doc:xxx` 正文原样保留，不误伤
5. 不递归展开；`@cache:` 行为不变
6. `mvn clean verify` 全绿（core 新增单测 + agent 集成测试；依赖方向 grep 零命中——core 无新依赖，agent→core 合法边）
7. 运行冒烟：`--no-cli` 启动，write_element 实测一条 `@doc:` 写入并在 query_element 中读回展开内容

## 范围外（YAGNI）

- 递归展开（doc 引 doc）
- 动态解析（保持引用、读取时展开）
- 其他工具的挂载（attachment_write.data、doc_write.content 等）——解析器已独立，后续按需一行挂载
- `\"` 转义
- 无引号形态识别

## 已确认决策（用户拍板）

| 决策点 | 结论 |
|--------|------|
| 引用对象 | doc 系统 + import 目录双支持 |
| 前缀 | `@doc:`（剥 .md）+ `@import:`（完整后缀），沿用参数级引用语法族 |
| 展开时机 | 写入时展开（快照） |
| 缺失行为 | 拒绝写入返回错误（严格模式） |
| 实现方案 | 方案 A：core 新增 InlineRefResolver + WriteElementTool 挂载 |
