# GSimulator 数据模型与持久化方案

> 本文档描述 GSimulator 中所有核心数据模型的结构、存储方式与关键设计决策。详见 `ARCHITECTURE.md`（系统总览）、`RUNTIME-FLOWS.md`（数据流转）、`TOOL-CONTRACTS.md`（工具读写契约）。AI Agent 开发约束见项目根目录 `CLAUDE.md`。

---

## 1. WorldInfo — 三层数据模型

```
data/worlds/{worldId}/
  └── nXXXX.json           ← 节点文件（n0000, n0001, ...）
        ├── parentId: String | null    — n0000.parentId = null
        ├── turn: int                  — 回合编号
        ├── worldTime: String          — 游戏内时间
        ├── status: "active"|"completed"|"abandoned"
        ├── checkpointLabels: String[] — 该节点拥有的检查点 ID 列表
        └── data: Map<String, Checkpoint>
              └── Checkpoint
                    ├── id/type: String / "character"|"faction"|"worldview"|"misc"
                    └── elements: List<Element>
                          ├── key: String
                          ├── type: "text"|"json"
                          ├── value: String
                          ├── tags: List<String>
                          ├── links: List<String>
                          ├── createdAt: ISO timestamp
                          └── updatedAt: ISO timestamp
```

- 每个节点独立存储为一个 `nXXXX.json` 文件。
- `WorldInformation` 启动时构建全量 `KeywordIndex`，支持全文检索和 `player.*` 通配符检查点查询。
- 节点分支通过 `node_create` / `node_switch` / `node_goto_parent` 管理，形成链式结构。

---

## 2. DocStore — 文档模型

```
data/docs/{docId}.json
  └── Document
        ├── docId: String   — 唯一标识
        ├── type: String    — "character"|"skill"|"world_state"|"template"|"context"|"rule"|"other"
        ├── title: String
        ├── content: String
        ├── tags: List<String>
        ├── createdAt: ISO timestamp
        └── updatedAt: ISO timestamp
```

- 纯文件存储，每文档独立 JSON 文件。
- 文本搜索依赖关键词索引（本地实现）。

---

## 3. GSimap MapData — 六角格地图数据模型（核心）

> MapData 是整个地图系统的根 record，包含所有子组件。

```
MapData
├── gridSize: int (1-1000)
├── hexOrientation: boolean          — true=pointy-top, false=flat-top
├── hexes: Map<String, HexCell>      — key = "q_r" 轴向坐标
│     HexCell
│     ├── color: "#808080"
│     ├── terrain: "unknown"
│     ├── symbol / symbolColor: String?    — 文字标注
│     ├── description: String?
│     ├── riverMask: int (6-bit)           — @Deprecated, 迁移至 edgeTags
│     └── edgeTags: Map<Integer, List<String>> — 每方向 0-5 的通路标签
├── terrainTypes: Map<String, TerrainType> — 8 个内置类型
│     TerrainType { name, color, food, gold, stone, moveCost, description }
│     内置: water(水), plains(平原), forest(森林), mountain(山地),
│           desert(沙漠), swamp(沼泽), tundra(冻土), hills(丘陵)
├── terrainBlocks: List<TerrainBlock>
│     TerrainBlock { terrain, boundary(List<Pt>), seedKey, hexKeys }
├── provinces: Map<String, Province>
│     Province { hexes, color, tag, description, annexedBy }
│     annexedBy 非空 → 已被合并，不参与渲染
├── cities: Map<String, City>
│     City { q, r, name, description }
├── pathwayGroups: Map<String, PathwayGroup> — 边类型注册表
│     PathwayGroup {
│       id, name, color, description, visible: Boolean,
│       properties: Map<String, PropertyDef>
│     }
│     PropertyDef { type("int"|"float"|"bool"|"string"), defaultValue, description }
│     内置: river { width(int, default=2) }, road { width(int, default=1) }
├── edges: Map<String, Map<String, Map<String, Object>>> — 边连通数据（稀疏存储）
│     外层 key = edgeKey = "minQ_minR|maxQ_maxR"   ← 顺序无关的确定性 key
│     中层 key = pathwayId (e.g. "river", "road")
│     内层 map = property → value (e.g. "width" → 3)
│     仅存储有通路标记的边，空边不出现在 JSON 中
├── compressedRegions: List<CompressedRegion> — 渲染优化用凸包缓存
│     CompressedRegion { id, terrain, color, boundaries, isWater, hexKeys }
│     纯渲染缓存，可被 recompress() 重建
└── rivers/roads: @Deprecated               — 由 edges + pathwayGroups 替代
```

### 边 Key 设计

```
edgeKey(q1, r1, q2, r2) =
    let k1 = minLex(q1_r1, q2_r2)
    let k2 = maxLex(q1_r1, q2_r2)
    k1 + "|" + k2
```

等价于 `"minQ_minR|maxQ_maxR"`，保证 `edgeKey(a,b) == edgeKey(b,a)`。前端 JS 镜像函数 `buildEdgeKey(q1,r1,q2,r2)` 位于 `pathway.js`。

### 前后端双向同步

```
后端加载时:  syncEdgesToHexTags()   MapData.edges  ←→ HexCell.edgeTags[d]
          events.js
前端保存时:  syncHexTagsToEdges()   HexCell.edgeTags[d]  ←→ MapData.edges
          pathway.js
```

- **前端渲染视角**: 按 HexCell.edgeTags[direction] 逐格绘制边线
- **后端存储视角**: 按 edge pair 聚合为稀疏 Map，消除冗余存储

### 持久化

MapData 以 `nXXXX_map.json` 形式附加在世界节点旁。`MapResolver` 从根节点起沿 diff 链逐层合并，重构完整地图状态。

---

## 4. Cache — 对话缓存模型

```
data/worlds/{worldId}/caches/{sessionId}.json
  └── CacheSession
        ├── sessionId: String
        ├── agentType: "orchestrator"|"sim-*"|"search-*"
        ├── worldId / nodeId: String?
        ├── messages: List<Message>
        └── createdAt: ISO timestamp
```

- 每个 SubAgent 调用自动保存完整对话历史。
- 支持通过 `cacheId` 续接上下文。
- 支持摘要压缩（compact）以节省 token。

---

## 5. AgentConfig — Agent 配置模型

```
data/agents/{agentId}.json
  └── AgentConfig
        ├── agentId: "orchestrator"|"sim"|"search"|custom
        ├── llmProvider: String              — 对应 llms.json 中的 provider ID
        ├── staticSystemPrompt: String       — 完整系统提示词（嵌入 JSON 本身）
        ├── toolFilter: ToolFilterConfig
        │     { mode: "all"|"read_only"|"custom", allow: String[], deny: String[] }
        ├── maxToolRounds: int               — orchestrator=64, sim/search=16
        ├── temperature: double (default 0.3)
        ├── maxTokens: int (default 2048)
        └── maxPermission: Permission        — SubAgent 权限上限
```

- 配置嵌入 JSON，prompt 内容直接存放在 `staticSystemPrompt` 字段中。
- classpath fallback 路径: `resources/gsim/agents/{agentId}/config.json`.

---

## 6. 配置文件模型

| 文件 | 加载目标 | 说明 |
|------|---------|------|
| `data/gsim.properties` | `AppConfig` | 应用基础配置（API host/port、LLM 默认值、agent 限制） |
| `data/llms.json` | `LlmProvider[]` | LLM Provider 数组: `{ id, name, baseUrl, apiKey, model, temperature, maxTokens, contextWindow }` |

两个文件均在首次启动时自动生成模板。

---

## 7. 跨文档引用

| 文档 | 内容 |
|------|------|
| `ARCHITECTURE.md` | 系统架构总览、模块依赖关系 |
| `RUNTIME-FLOWS.md` | 数据在 Agent → Tool → WorldInfo 之间的流转路径 |
| `TOOL-CONTRACTS.md` | 各工具（query_element / write_element / node_create 等）的入参/出参契约 |
| — | AI Agent 开发约束、包结构、运行命令、提交前检查 |

---

## 持久化总览

```
data/
├── gsim.properties         — AppConfig
├── llms.json               — LlmProvider[]
├── agents/                 — AgentConfig (*.json)
│   └── {agentId}.json
├── worlds/                 — 世界观数据
│   └── {worldId}/
│       ├── n0000.json       — 节点数据
│       ├── n0000_map.json   — 地图数据（可选附件）
│       ├── n0001.json
│       ├── n0001_map.json
│       └── caches/          — 对话缓存
│           └── {sessionId}.json
└── docs/                   — 文档存储
    └── {docId}.json
```

所有模型均使用 Java `record` 定义，Jackson 序列化/反序列化，无数据库依赖。
