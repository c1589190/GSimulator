---
name: gsim-integration-test
description: |
  GSimulator 全面质量检查 Skill — 覆盖 mvn verify 棘轮门禁、MCP 连通性、
  World/Node/Map/Doc CRUD、SubAgent 权限、错误处理边界测试。
  当用户提到 "测试"、"验证"、"检查项目"、"质量检查"、"集成测试"、"跑测试"、
  "全面检查"、"回归测试"、"验收测试"、"提交前检查"、"合并前验证" 时必须使用。
---

# GSimulator 集成验收测试

## 质量门禁架构（棘轮式）

```
                    ┌──────────────┬──────────────┬──────────────┐
                    │   gsim-lib   │   gsimap     │   gsim-app   │
                    │   (旧模块)    │   (新模块)    │   (新模块)    │
╔═══════════════════╪══════════════╪══════════════╪══════════════╗
║ Compiler -Werror  │    阻断      │    阻断      │    阻断      ║
║ Spotless          │    阻断      │    阻断      │    阻断      ║
║ Tests             │    阻断      │    阻断      │    阻断      ║
║ SpotBugs (Medium) │  报告不阻断   │    阻断      │    阻断      ║
║ Checkstyle        │  报告不阻断   │  报告不阻断   │  报告不阻断   ║
╚═══════════════════╧══════════════╧══════════════╧══════════════╝

策略：历史债务不阻塞当前开发，但任何模块不得继续新增同类债务。
gsim-lib 的 398 个 SpotBugs 存量问题已基线化（config/spotbugs/gsim-lib-baseline.xml），
CI 通过指纹对比阻止新增，同时允许存量逐步修复。
```

## Phase 1: 代码质量门 (mvn verify)

```bash
mvn verify --batch-mode
```

### 门禁规则

| 门禁 | gsim-lib | gsimap | gsim-app | 失败处理 |
|------|----------|--------|----------|---------|
| `-Xlint:all,-processing,-options -Werror` | 阻断 | 阻断 | 阻断 | 修复源码警告，不得滥用 @SuppressWarnings |
| Spotless (palantirJavaFormat) | 阻断 | 阻断 | 阻断 | `mvn spotless:apply` 自动修复 |
| Tests (~578) | 阻断 | 阻断 | 阻断 | 定位失败用例修复 |
| SpotBugs (Max/Medium) | 报告 | **阻断** | **阻断** | gsimap/gsim-app 必须 0 bugs |
| Checkstyle | 报告 | 报告 | 报告 | 仅报告，逐步清零 |

### SpotBugs 分类修复指南

当 gsimap/gsim-app 被阻断时，按类别处理：

```
🔴 High priority — 必须立刻修:
   NP_*          空指针风险
   DC_PARTIALLY_CONSTRUCTED  部分构造泄漏
   IS2_INCONSISTENT_SYNC  不一致同步
   OBL_UNSATISFIED_OBLIGATION  资源泄漏
   EC_UNRELATED_TYPES  equals 类型错误
   BC_IMPOSSIBLE_CAST  不可能的类型转换
   RC_REF_COMPARISON  引用比较 bug
   DLS_DEAD_LOCAL_STORE  死代码赋值

🟡 Medium priority — 本次修:
   DMI_RANDOM_USED_ONLY_ONCE  随机数对象只用一次
   MS_PKGPROTECT  可变静态字段应包级私有
   UC_USELESS_OBJECT  无用对象

🟢 Low priority — 分批修:
   EI_EXPOSE_REP  可变内部状态暴露
   URF_UNREAD_FIELD  未读取字段
   SE_NO_SERIALVERSIONID  序列化 ID
```

### gsim-lib 棘轮基线

存量 398 条已基线化于 `config/spotbugs/gsim-lib-baseline.xml`。
CI 运行 `config/spotbugs/check-baseline.sh gsim-lib` 对比指纹：
- 新增 issue → 阻断
- 修复 issue → 允许（质量只升不降）

更新基线（修复存量后）：
```bash
mvn spotbugs:spotbugs -pl gsim-lib -Dspotbugs.effort=Max -Dspotbugs.threshold=Medium
cp gsim-lib/target/spotbugsXml.xml config/spotbugs/gsim-lib-baseline.xml
```

### Checkstyle 去噪说明

已移除与 Spotless 重叠的格式规则（空白、括号、import 排序等），
保留语义级规则：
- 命名约定、AvoidStarImport
- Javadoc（public API）
- 危险代码：EmptyStatement、EqualsHashCode、HiddenField、MissingSwitchDefault
- 代码规模：MethodLength(200)、ParameterNumber(10)
- FIXME/HACK 标记追踪

## Phase 2: MCP 连通性

**直接执行**（不启动子 Agent）：

1. `gsim_world_list` — 确认连通，itemCount > 0，`_context` 存在
2. `gsim_node_status` — worldId="default"，确认 `_context.{worldId, address, nodeId}`
3. `gsim_doc_list` — 不传 worldId（Doc 不需要），确认正常
4. `gsim_list_llm_providers` — 不传 worldId，确认有 provider
5. `gsim_import_document_list` — 不传 worldId，确认正常

## Phase 3-8: 功能测试

全部通过 `Agent` 工具并行派发，`run_in_background: true`，`subagent_type: general-purpose`。

### Phase 3: World & Node CRUD

测试 world_create → world_switch → node_create → node_switch → node_list(tree) → node_goto_parent(根节点应报错) → world_switch(default)。

### Phase 4: GSimap 地图

测试 generate → create_region → list_regions → get_hex → query_radius → get_neighbors → add/remove hex → render_text → get_distance → query_by_address → merge_regions → rename_region → 缺 worldId 校验。

### Phase 4b: GSimap Edge Pathway（边连通系统）

测试 edge_set → edge_get → edge_list → edge_remove → 同一段边多标签共存 → 标签清空后边自动删除 → 缺 worldId 校验 → 无效 pathwayId 校验。

### Phase 5: WorldInfo 元素

测试 create_checkpoint → write_element(×3) → query_element → query_checkpoint → query_keyword → query_by_tag → query_address → query_node → attachment_write/read → resolve_ref → text_edit → 缺 worldId 校验。

### Phase 6: Doc CRUD

Doc 工具不需要 worldId。测试 create(×3) → list → search → read → write(append+replace) → crop → index → delete(×3) → 确认清理。

### Phase 7: SubAgent & 权限

测试 list_llm_providers → agent_config_list → create_sub_agent_config → dispatch_sub_agent → list_sub_agent_caches → activate_tool_groups → update/delete agent config → 验证工具组激活。

### Phase 8: 错误处理 & 边界

测试缺 worldId 校验(×5) → 无效参数(×5) → 边界值(×5) → 不存在 world → 不需要 worldId 的工具验证(×4) → _context 字段检查(×3)。

每个子 Agent 独立汇报 PASS/FAIL 列表。

## Phase 9: 汇总报告

```markdown
## GSimulator 集成验收测试报告

### 质量门: mvn verify
| 模块 | Compiler | Tests | Spotless | SpotBugs | Checkstyle |
|------|----------|-------|----------|----------|------------|
| gsim-lib | ✅ | ✅ 562 | ✅ | ⚠️ 398 baseline | ⚠️ N violations |
| gsimap | ✅ | ✅ 16 | ✅ | ✅ 0 bugs | ⚠️ N violations |
| gsim-app | ✅ | ✅ 0 | ✅ | ✅ 0 bugs | ⚠️ N violations |

### 功能测试
| Phase | 子系统 | PASS | FAIL |
|-------|--------|------|------|
| 2 | MCP 连通性 | | |
| 3 | World & Node | | |
| 4 | GSimap 地图 | | |
| 4b | Edge Pathway | | |
| 5 | WorldInfo | | |
| 6 | Doc CRUD | | |
| 7 | SubAgent 权限 | | |
| 8 | 错误处理 | | |

### 健康度总评
🟢 正常 | 🟡 非关键问题 | 🔴 阻断性问题
```

## 测试数据命名

- World: `mcp_test_{{YYYYMMDD}}`
- Doc: `mcp_test_{{a/b/c}}`
- Checkpoint: `test_chars`
- Region: `测试国A` / `测试国B`
- Agent: `mcp_tester`

## 关键约束

1. **先跑代码门，再跑功能测试** — mvn verify 必须先通过
2. **功能测试全并行** — Phase 3-8 独立子 Agent
3. **Doc/Import/Agent配置/Search 不需要 worldId**
4. **GSimap/WorldInfo/Node/SubAgent缓存 需要 worldId**
5. **成功响应必须含 `_context: {worldId, address, nodeId}`**
6. **测试结束后清理** — 删除测试 doc/region/world
7. **不要假设当前 active world** — 每个 Agent 先调 world_list 确认
8. **不要跳过错误测试** — 缺 worldId、无效参数、不存在 ID 也必须覆盖

## Bug 知识沉淀

每发现并修复一个真实 bug，必须调用子流程 `capture-bug.md`
将触发场景、修复过程、检验方法写入 `bugs/` 文件夹。

命名规范: `bugs/{{YYYY-MM-DD}}-{{category}}-{{slug}}.md`
分类: `spotbugs` | `logic` | `null` | `concurrency` | `api` | `build`

已沉淀的 bug:
- `bugs/2026-07-22-logic-canvas-cache-key.md` — Canvas 缓存键 worldId 匹配失败
- `bugs/_template.md` — 新 bug 模板

沉淀时读取 `capture-bug.md` 了解完整工作流。
