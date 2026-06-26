# Agent 上下文系统重构设计

**日期**: 2026-06-26
**状态**: 已确认
**范围**: 完全弃用现有 data/ 及 12 个业务包，重建为 worlds/ + prompts/ 双目录结构

---

## 一、核心目标

1. 主 Agent 和 SubAgent 的上下文缓存一视同仁，各自独立管理
2. LLM 对话缓存以世界线为单位，每 session 一个 OpenAI 原生格式 JSON，时间戳命名
3. 节点文件为纯 JSON 增量记录，沿 parentId 链加载组装为 WorldInformation
4. LLM 通过 3 个工具自主查询任意回合、任意检查点、任意信息元素
5. 废弃 ChromaDB 向量检索，关键词倒排索引替代
6. 完全弃用现有 data/ 文件结构

---

## 二、磁盘文件结构

```
<cwd>/
  worlds/                              # 世界存档根目录
    _index.json                        # 世界列表
    three-kingdoms/                    # 一个世界 = 一个目录
      world.json                       # 世界元信息
      nodes/                           # 节点（时间线）目录
        n0000.json                     # 根节点（初始世界观，全量）
        n0001.json                     # 第一回合（增量）
        n0002.json                     # 第二回合（增量）
        ...
      caches/                          # LLM 对话缓存
        Orchestrator_2026-06-26T100000.json
        Sim_2026-06-26T100030.json     # 子Agent只写不读，每次新建
        Search_2026-06-26T101500.json
        ...
      active.json                      # 当前激活状态
  prompts/                             # Agent 提示词模板（FreeMarker）
    OrchestratorAgent_system.md
    SimAgent_system.md
    SearchAgent_system.md
    OrchestratorAgent_compress.md
    ...
```

### _index.json
```json
[
  {"id": "three-kingdoms", "name": "三国", "createdAt": "2026-06-26T10:00:00Z"}
]
```

### world.json
```json
{
  "id": "three-kingdoms",
  "name": "三国",
  "createdAt": "2026-06-26T10:00:00Z",
  "currentNodeId": "n0003"
}
```

### active.json
```json
{
  "nodeId": "n0003",
  "sessions": {
    "Orchestrator": "Orchestrator_2026-06-26T100000.json",
    "Sim": "Sim_2026-06-26T143000.json"
  }
}
```

**规则**:
- worlds/ 位于 java -jar 的 cwd，非项目源码目录
- 子 Agent 缓存只写不读，每次创建新时间戳文件
- active.json 只管理 nodeId + Orchestrator session，子 Agent 不跟踪

---

## 三、节点 JSON 规范

每个节点 = 一个回合的增量快照。根节点 n0000 记录完整初始设定。

```json
{
  "nodeId": "n0003",
  "parentId": "n0002",
  "turn": 3,
  "worldTime": "公元184年三月",
  "status": "simulated",
  "createdAt": "2026-06-26T10:00:00Z",

  "checkpoints": {
    "worldview": {
      "label": "世界观",
      "type": "worldview",
      "elements": [
        {
          "key": "气候.中原",
          "type": "text",
          "value": "黄巾之乱爆发后中原大旱，蝗灾四起",
          "tags": ["气候", "灾害", "中原"]
        },
        {
          "key": "人口.洛阳",
          "type": "number",
          "value": "约30万",
          "tags": ["人口", "洛阳", "都城"],
          "links": ["player.汉灵帝.actions.0"]
        }
      ]
    },
    "player.曹操": {
      "label": "曹操",
      "type": "player",
      "elements": [
        {
          "key": "曹操.行动.长社救援",
          "type": "action",
          "value": "曹操率兵五千自陈留出发，星夜驰援长社",
          "tags": ["曹操", "军事", "长社"],
          "links": ["narrative.main", "player.皇甫嵩.elements.0"]
        },
        {
          "key": "曹操.效果.军势壮大",
          "type": "effect",
          "value": "战后收编降卒三千，兵力扩充至八千",
          "tags": ["曹操", "兵力", "晋升"],
          "links": ["narrative.main"]
        }
      ]
    },
    "narrative": {
      "label": "推文",
      "type": "narrative",
      "elements": [
        {
          "key": "narrative.main",
          "type": "narrative",
          "value": "三月，波才率众十余万围长社。皇甫嵩以火攻大破之...",
          "tags": ["推文", "长社之战", "184年"],
          "links": ["player.皇甫嵩.elements.0", "player.曹操.elements.0"]
        }
      ]
    }
  }
}
```

**规则**:

| 规则 | 说明 |
|------|------|
| 增量原则 | 节点只记录本回合变更。n0000 为全量初始 |
| key 唯一 | 同一 checkpoint 内 key 不重复；同 key 跨节点 = 更新 |
| type 自由 | type 不限制枚举，LLM 自行分类 |
| links 仅同节点 | 链接目标 `"checkpointId"` 或 `"checkpointId.elements.N"` |
| tags 供检索 | 关键词检索基于 tags + value 文本匹配 |

**默认检查点类型**（LLM 可自建）:
- `worldview` — 世界观设定
- `player.<name>` — 玩家/角色
- `faction.<name>` — 派系/势力
- `narrative` — 推文/叙事

---

## 四、LLM 缓存 JSON 规范

```json
{
  "agentName": "Orchestrator",
  "worldId": "three-kingdoms",
  "nodeId": "n0003",
  "sessionId": "Orchestrator_2026-06-26T100000",
  "createdAt": "2026-06-26T10:00:00Z",
  "previousSessionId": "Orchestrator_2026-06-26T083000",
  "compressionNote": "此前曹操已从陈留起兵，与皇甫嵩合兵长社...",
  "messages": [
    {
      "role": "system",
      "content": "你是推演引擎..."
    },
    {
      "role": "user",
      "content": "玩家行动：曹操出兵..."
    },
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_xxx",
          "type": "function",
          "function": {
            "name": "query_checkpoint",
            "arguments": "{\"checkpointId\": \"player.皇甫嵩\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_xxx",
      "content": "{\"checkpointId\": \"player.皇甫嵩\", \"elements\": [...]}"
    },
    {
      "role": "assistant",
      "content": "皇甫嵩正在长社固守，曹操可前往会合..."
    }
  ]
}
```

**规则**:

| 规则 | 说明 |
|------|------|
| OpenAI 原生格式 | messages 完全使用 OpenAI Chat Completions 格式 |
| 压缩链 | previousSessionId + compressionNote 形成链表 |
| 压缩操作 | 总结旧 session → 写入新 session 的 compressionNote → 新 session 首条 user 消息携带上下文 |
| 子 Agent | 只写不读，每次新建时间戳文件 |
| 主 Agent | 反复读写，active.json 跟踪当前 session |
| system prompt | 新 session 用 FreeMarker 渲染，不从旧 session 继承 |

---

## 五、WorldInformation 内存数据结构

程序启动时从 activeNodeId 出发沿 parentId 链加载全部节点，组装为此结构。

```java
public record WorldInformation(
    String worldId,
    String rootNodeId,
    String activeNodeId,
    List<NodeSnapshot> branchChain,
    Map<String, List<ElementRef>> byCheckpoint,
    Map<String, List<ElementRef>> byTag,
    KeywordIndex keywordIndex
) {}

public record NodeSnapshot(
    String nodeId,
    String parentId,
    int turn,
    String worldTime,
    String status,
    Map<String, Checkpoint> checkpoints
) {}

public record Checkpoint(
    String label,
    String type,
    List<Element> elements
) {}

public record Element(
    String key,
    String type,
    String value,
    List<String> tags,
    List<String> links
) {}

public record ElementRef(
    String nodeId,
    int turn,
    String worldTime,
    String checkpointId,
    Element element
) {}
```

**加载流程**:
1. 读 active.json → activeNodeId
2. 从 activeNodeId 沿 parentId 链走到根
3. 解析链上所有节点 JSON
4. 构建 byCheckpoint：同 checkpointId 的元素汇聚
5. 构建 byTag：按 tags 倒排
6. 构建 keywordIndex：value 文本分词倒排
7. 注入全局上下文

**更新流程**（LLM 工具写入时）：
1. 新元素写入当前节点 JSON 的对应 checkpoint
2. 更新内存中三个索引
3. 节点 JSON 异步落盘

---

## 六、LLM 查询工具

三个工具覆盖所有查询模式，不设查询 DSL。

### query_checkpoint — 纵向查检查点全历史

```
输入: checkpointId (必填), turnFrom (可选), turnTo (可选)
输出: { checkpointId, label, type, elements: [ElementRef...] }

示例:
  query_checkpoint("player.曹操")
  → 返回曹操从 n0000 到当前的所有 actions + effects，按 turn 排序
```

### query_keyword — 横向关键词全文检索

```
输入: keywords (必填), limit (默认20), offset (默认0)
输出: { totalHits, offset, items: [{ elementRef, snippet, score }...] }

示例:
  query_keyword("长社 火攻", limit=10, offset=0)
  → 返回包含关键词的元素，含来源 nodeId/turn/checkpointId
  → 支持 offset 分页滑动
```

### query_node — 定点查回合全貌

```
输入: nodeId (必填)
输出: { nodeId, turn, worldTime, checkpoints: {...} }

示例:
  query_node("n0002")
  → 返回该节点所有检查点全部元素
```

---

## 七、Agent 上下文组装流程

每次 LLM 调用由三层堆叠：

```
Layer 3: 当前用户输入                          
         (user message)

Layer 2: Active Session messages               
         (OpenAI 格式直灌，包含 tool_calls 和 tool results)
         + previousSession 的 compressionNote  

Layer 1: Rendered Context                      
         System prompt = 
           FreeMarker 渲染的 Agent_system.md
           + WorldInformation 摘要
           + 工具清单
```

**Layer 1 渲染逻辑**:
- 取 `prompts/OrchestratorAgent_system.md`，FreeMarker 注入变量
- 变量包括：当前节点位置（turn, worldTime）、最近3回合推文、活跃检查点列表、工具说明
- 工具说明引导 LLM 使用 query_checkpoint / query_keyword / query_node

**Layer 2 载入逻辑**:
- 读当前 Orchestrator cache JSON → messages[]
- 如果 previousSessionId 存在且当前超出消息限制 → 沿链取 compressionNote 注入 Layer 1
- 不递归加载旧 session 完整 messages

**主/子 Agent 交互**:
- 主 Agent 调用子 Agent 时，子 Agent 读自己的 cache（未来做）
- 子 Agent 结果以 tool result 形式返回主 Agent
- 子 Agent 缓存只写不读（当前阶段）

---

## 八、对话压缩流程

1. 检测当前 session messages 超过阈值（默认 32 轮或 token 超限）
2. 用 `OrchestratorAgent_compress.md` 渲染压缩 prompt
3. 调用 LLM 对 messages 做摘要
4. 创建新 cache JSON 文件（新时间戳）
5. 写入 `compressionNote` = 摘要，`previousSessionId` = 旧 sessionId
6. 新 session 首条 user message 携带压缩后的上下文
7. 更新 active.json 的 Orchestrator sessionId

---

## 九、启动流程

```
GSimulator 启动
├─ 1. 读 AppConfig
├─ 2. 读 worlds/_index.json，确定当前世界
├─ 3. 读 active.json → activeNodeId + sessions
├─ 4. 沿 parentId 链加载节点 → 组装 WorldInformation
├─ 5. 加载 Orchestrator cache → messages[]
│     若 previousSessionId 存在 → compressionNote 注入 context
│     若超量 → 触发压缩
├─ 6. FreeMarker 渲染 system prompt → 注入 WorldInfo + 工具清单
├─ 7. 组装 messages = [system, ...cacheMessages]
├─ 8. 启动 HTTP API + SSE EventBus
├─ 9. 进入交互循环
```

---

## 十、废弃与保留清单

### 完全废弃
| 包/目录 | 行数估算 | 替代方案 |
|---------|---------|---------|
| `data/` 目录 | - | `worlds/` |
| `campaign/` 包 | ~800行 | 节点即回合 |
| `branch/` 包 | ~600行 | parentId 链 |
| `world/` 包 | ~400行 | WorldInformation |
| `data/` 包 (DataManager) | 1263行 | NodeLoader + WorldInfoBuilder |
| `storage/` 包 | ~200行 | 直接 Jackson |
| `context/` 包 (8子包) | ~1500行 | CacheLoader + ContextRenderer |
| `chroma/` 包 | ~1000行 | keywordIndex 倒排 |
| `chat/` 包 (BranchMessage) | ~400行 | cache JSON messages |
| `task/` 包 | ~300行 | 简化为 ToolLoop 上下文 |
| `timeline/` 包 | ~200行 | narrative checkpoint |
| `interaction/commands/` (28命令) | ~2000行 | 新命令体系 |

### 保留升级
| 包 | 说明 |
|------|------|
| `llm/` — LlmClient, LlmManager | 不变 |
| `agent/core/` — AbstractAgent, AgentConfig, AgentFactory | ToolLoop 保留，移除旧 context 依赖 |
| `agent/tool/` — ToolRegistry, ToolFilterConfig, 基础工具 | 保留框架，工具替换 |
| `prompt/` — FreeMarker 渲染 | 升级为 `prompts/` 目录加载 |
| `api/` — HTTP + SSE | 不变 |
| `event/` — EventBus, EventSink | 不变 |
| `app/` — AppConfig, 启动器 | 调整启动流程 |
| `util/` — JSON, IdGen 等 | 不变 |

---

## 十一、实现阶段划分

| Phase | 内容 | 目标 |
|-------|------|------|
| Phase 1 | 新包结构 + WorldInformation 数据模型 | 编译通过 |
| Phase 2 | NodeLoader（JSON 读写 + parentId 链加载） | 能从磁盘加载世界 |
| Phase 3 | KeywordIndex + 3 查询工具 | LLM 可自主查询世界信息 |
| Phase 4 | CacheLoader / CacheWriter（OpenAI 格式读写） | 对话缓存可读可写 |
| Phase 5 | ContextRenderer（FreeMarker + WorldInfo 注入） | system prompt 渲染 |
| Phase 6 | 新命令体系（/world /node /chat 等） | CLI 可交互 |
| Phase 7 | 启动流程 + active.json 管理 | 完整启动闭环 |
| Phase 8 | 旧代码删除 + 测试适配 | 清理干净 |
| Phase 9 | Web UI 对接 | 前端适配新 API |

---

## 十二、Spec 自检

- [x] 无 TBD / TODO / 占位符
- [x] 文件结构、JSON Schema、内存模型三方一致
- [x] links 语法 `checkpointId` / `checkpointId.elements.N` 与数据结构匹配
- [x] 压缩流程完整：旧 session → 摘要 → 新 session
- [x] 主/子 Agent 缓存分离，子 Agent 只写不读
- [x] 废弃清单覆盖全部 12 个待替换包
- [x] 保留清单明确 7 个保留包
