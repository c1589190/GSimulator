# GPT 审查报告排查结果 — HTTP API 稳定性修复

**排查日期**: 2026-06-21
**分支**: phase-http-api-completion

---

## P0 问题排查

### P0-1. CORS 统一实现 — ✅ 确认存在

**结论**: 完全缺失 CORS 支持。

**证据**:
- 全代码零命中 `grep -r 'Access-Control|cors|CORS|CrossOrigin'` 无结果
- `BaseApiHandler.sendJson()` (line 22) 只设置 `Content-Type: application/json; charset=utf-8`
- `SseWriter.sendHeaders()` (lines 32-35) 只设置 `Content-Type: text/event-stream`, `Cache-Control`, `Connection`, `X-Accel-Buffering`
- **没有任何 handler 处理 OPTIONS 请求** — 所有 handler 对非 GET/POST 返回 405
- 无中间件/filter 机制可批量添加 CORS

**影响**: 浏览器跨域请求全部被 Same-Origin Policy 拦截，GUI 无法使用。

### P0-2. ApiResponse.fail(String, Map) 丢失 data — ✅ 确认存在

**结论**: `fail(String, Map)` 存储了 data，但 `toJson()` 序列化时丢弃。

**证据** (`ApiResponse.java`):
- 第 86-89 行: `fail(String, Map)` 构造函数确实存储 data 字段
- 第 53-55 行: `toJson()` 对 `success==false` 无条件 `map.put("data", null)`
- 注释说 "保留 data 用于向后兼容" 但实际上是假的
- 唯一调用者 `CommandApiHandler.java` 第 90 行传递的 data（含 command, sessionId, displayText 等）在 HTTP 响应中全部消失

**影响**: 失败响应丢失诊断信息，GUI 无法获取错误详情。

### P0-3. /api/tasks SSE 竞态 — ✅ 确认存在

**结论**: `POST /api/tasks` 在返回 taskId 之前已经启动虚拟线程执行，存在竞态。

**证据**:
- `TaskManager.createCommandTask()` (line 59-70): 第 67 行 `Thread.startVirtualThread()` 在 return 前就触发
- 任务从 PENDING → RUNNING 仅需微秒级
- `TasksApiHandler.handleTaskEvents()` (line 137-185): 如果 task 已终止，只发 `task_status` + `done`，中间事件全丢
- **对比** `StreamCommandHandler` (lines 64-78): 使用 `reserveTask()` + subscribe + `executePendingTask()` 避免竞态

**影响**: GUI 先 POST /api/tasks 再 GET events 可能收不到中间事件。

### P0-4. 重复 done 事件 — ✅ 确认存在

**结论**: done 事件确实重复，来源有二。

**证据**:
- **来源 1**: `TaskManager.executeTask()` finally 块 (line 250-252): `eventBus.publish(GSimEvent.of(sessionId, taskId, "done", Map.of()))`
- **来源 2**:
  - `TasksApiHandler.handleTaskEvents()` (line 169-175): `sse.writeEvent("done", doneData)` — 绕过 EventBus 直接写
  - `StreamCommandHandler` (lines 85-92): 同样模式
- 两者格式不同（一个有 type，一个有 status），客户端收到了两条 done

**影响**: SSE 客户端收到两条 done 事件，语义不明确。

### P0-5. cancel 竞态覆盖 — ✅ 确认存在

**结论**: cancel 后 executeTask 可能把 CANCELLED 覆盖为 DONE/FAILED。

**证据** (`TaskManager.java`):
- `cancelTask()` (line 135): 写 `CANCELLED` 到 Map → 发 `command_error` + `done`
- `executeTask()` 主流程 (lines 199-236): `manager.handle()` 完成后无条件写 DONE/FAILED，只检查 `isCancelled` 在异常处理
- Normal path (lines 219-236): 不管是 DONE 还是 FAILED 分支，都没有 `isCancelled()` 检查
- 如果 `cancelTask()` 在 `manager.handle()` 执行期间调用，CANCELLED 状态会被后续的 `tasks.put(taskId, done/failed)` 覆盖

**影响**: cancel 操作不可靠，任务可能被覆盖为 DONE/FAILED。

---

## P1 问题排查

### P1-6. CampaignsApiHandler 语义错误 — ✅ 确认存在

**6a) createCampaign 不按请求 name/id 创建 — ✅ 确认**
- `handleCreateCampaign()` (line 141-153): `req.name()` 当成 campaignId 尝试 load，找到就返回已存在的，找不到就调 `getOrCreateDefault()`
- `getOrCreateDefault()` 返回随机 ID + 硬编码 name="default-campaign"
- 用户请求的 name 完全被丢弃

**6b) listTurns 只返回 current turn — ✅ 确认**
- `handleListTurns()` (line 180-187): 传入的 `campaignId` 完全忽略
- 只调用 `getCurrentTurn()` 返回内存中唯一一个 turn
- Campaign 记录中存有 `turnIds` 列表但未使用

**6c) DELETE actions 不作用于指定 campaignId/turnId — ✅ 确认**
- `handleClearActions()` (line 241-243): 接受 `campaignId`, `turnId` 参数但完全不用
- 直接调用 `clearActions()` 清空内存中的 actions，不管属于哪个 campaign/turn

### P1-7. sessionId 多来源支持 — ✅ 确认缺失

**结论**: sessionId 仅从 JSON body 的 `sessionId` 字段获取，不支持 header 或 query。

**证据**:
- 所有 handler 通过 `CommandRequest.sessionId()` 获取，来自 body JSON 反序列化
- 大量 handler 硬编码 `"default"` (BranchesApiHandler, ConfigApiHandler, DataApiHandler, PlayersApiHandler, SearchDbApiHandler 等)
- 无 `X-GSim-Session-Id` header 读取
- 无 query param sessionId 读取

### P1-8. logs/outputs taskId 路径穿越 — ⚠️ 低风险确认

**结论**: 无显式防护但实际难以利用。

**证据**:
- `LogsOutputsApiHandler` (lines 72-73): `logDir.resolve(taskId + ".json")` 直接使用
- Java `Path.resolve()` API 对 `..` 不会向上穿越（会作为文件名字面量），但对绝对路径会返回参数本身
- `pathSegments()` 用 `/` 分割后只取 `segs[0]`，多段路径被截断
- 无 taskId 格式校验 / 白名单 / `startsWith` 规范化

**实际风险**: 低（Java NIO Path.resolve 行为 + split 截断的双重保护下难以利用），但防御性编程建议加校验。

### P1-9. Import 未接 KnowledgeStore 却返回成功 — ✅ 确认

**结论**: Import local 在 pipeline 未连接 KnowledgeStore 的情况下返回 "Import completed"。

**证据** (`ImportApiHandler.java`):
- 第 70-72 行: 注释明确写 "Import pipeline not yet connected to SQLite KnowledgeStore. Passing null chromaClient"
- 第 73 行: `ImportManager importManager = new ImportManager(config, null)` — null chromaClient
- 第 87 行: `BaseApiHandler.sendOk(exchange, "Import completed", data)` — 仍然返回 success

---

## 补救测试需求排查

| 测试需求 | 当前覆盖 | 状态 |
|----------|---------|------|
| OPTIONS /api/tasks 返回 204 + CORS | 无 | ❌ 缺失 |
| autoStart=false 返回 PENDING | 无 | ❌ 缺失 |
| 订阅 events 后 start 收到完整事件 | `TasksApiHandlerTest.shouldReturnSseStreamForTask` 部分覆盖，但用 reserveTask | ⚠️ 部分 |
| done 不重复 | `SseTaskEventsTest.eventSequenceShouldBeComplete` 只测 SseEventSink，不测 handler | ❌ 缺失 |
| cancel 后任务保持 CANCELLED | `TasksApiHandlerTest.shouldCancelTask` 只测 PENDING cancel，不测 RUNNING 期间 cancel | ⚠️ 部分 |
| fail(String, Map) 保留 data | 无 | ❌ 缺失 |
| campaign create/listTurns/clearActions 语义 | `ApiEndpointsTest` 测了 list 但不验语义 | ❌ 缺失 |
| logs/outputs 拒绝 ../ | 无 | ❌ 缺失 |

---

## 总结

| # | 问题 | 状态 | 严重度 |
|---|------|------|--------|
| P0-1 | CORS 缺失 | 确认 | 高 |
| P0-2 | fail data 丢失 | 确认 | 中 |
| P0-3 | SSE 竞态 | 确认 | 高 |
| P0-4 | done 重复 | 确认 | 中 |
| P0-5 | cancel 覆盖 | 确认 | 高 |
| P1-6a | createCampaign 忽略 name | 确认 | 中 |
| P1-6b | listTurns 只返回 current | 确认 | 中 |
| P1-6c | DELETE actions 忽略 ID | 确认 | 中 |
| P1-7 | sessionId 缺 header/query | 确认 | 低 |
| P1-8 | logs/outputs 路径穿越 | 极低风险 | 低 |
| P1-9 | Import 成功后端未完成 | 确认 | 中 |
