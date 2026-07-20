# T04: 地图程序化生成与查询

## 前置条件

- T02 通过（test_integration World 存在）
- T03 通过（n0000 → n0001 → n0002 链条存在）
- 当前活跃节点为 n0001（T03 Step 8 回退到此节点）

## 测试步骤

### Step 1: 程序化生成地图

在 n0001 节点生成一个中等大小的程序化地图。

- **工具**: `gsimap_generate`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `radius`: `60`
  - `landRatio`: `0.5`
  - `ridges`: `3`
  - `fragments`: `4`
  - `coastRoughness`: `0.5`
  - `seed`: `42`
- **预期结果**: 返回成功，生成的地图含 hex 数据

```json
{"tool": "gsimap_generate", "args": {"worldId": "test_integration", "nodeId": "n0001", "radius": 60, "landRatio": 0.5, "ridges": 3, "fragments": 4, "coastRoughness": 0.5, "seed": 42}}
```

### Step 2: 查询单个 hex

- **工具**: `gsimap_get_hex`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `q`: `0`
  - `r`: `0`
- **预期结果**: 返回坐标 (0,0) 的 hex，含 terrain type、color、symbol。中心点通常为陆地

```json
{"tool": "gsimap_get_hex", "args": {"worldId": "test_integration", "nodeId": "n0001", "q": 0, "r": 0}}
```

### Step 3: 查询邻居 hex

- **工具**: `gsimap_get_neighbors`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `q`: `0`
  - `r`: `0`
- **预期结果**: 返回 6 个邻居 hex（或满足 hex 网格拓扑的有效数量），每个含坐标和 terrain

```json
{"tool": "gsimap_get_neighbors", "args": {"worldId": "test_integration", "nodeId": "n0001", "q": 0, "r": 0}}
```

### Step 4: 半径查询

- **工具**: `gsimap_query_radius`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `q`: `0`
  - `r`: `0`
  - `radius`: `3`
- **预期结果**: 返回中心周围 3 步内的所有 hex（约 37 个 hex），每个含坐标和 terrain

```json
{"tool": "gsimap_query_radius", "args": {"worldId": "test_integration", "nodeId": "n0001", "q": 0, "r": 0, "radius": 3}}
```

### Step 5: 列出所有 Region

生成后应至少有一个默认区域（如 ocean 或 unclaimed）。

- **工具**: `gsimap_list_regions`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
- **预期结果**: 返回 region 列表，每个含 name、center、hexCount、terrain 构成

```json
{"tool": "gsimap_list_regions", "args": {"worldId": "test_integration", "nodeId": "n0001"}}
```

### Step 6: 查询城市列表

初始生成的地图通常尚无城市。

- **工具**: `gsimap_get_cities`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
- **预期结果**: 返回城市列表（可能为空，尚未初始化 Nation）

```json
{"tool": "gsimap_get_cities", "args": {"worldId": "test_integration", "nodeId": "n0001"}}
```

### Step 7: 计算两点距离

- **工具**: `gsimap_get_distance`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `fromQ`: `0`, `fromR`: `0`
  - `toQ`: `5`, `toR`: `0`
- **预期结果**: 返回 hex 距离 = 5

```json
{"tool": "gsimap_get_distance", "args": {"worldId": "test_integration", "nodeId": "n0001", "fromQ": 0, "fromR": 0, "toQ": 5, "toR": 0}}
```

### Step 8: 节点 n0002 继承父节点地图

切换到 n0002，验证父链地图继承。

- **工具**: `node_switch`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0002`
- **预期结果**: 切换成功

```json
{"tool": "node_switch", "args": {"worldId": "test_integration", "nodeId": "n0002"}}
```

- **验证**: 查询 n0002 的 (0,0) hex，应返回与 n0001 相同的地形（因为 n0002 无自己的地图修改，从父链继承）

```json
{"tool": "gsimap_get_hex", "args": {"worldId": "test_integration", "nodeId": "n0002", "q": 0, "r": 0}}
```

### Step 9: 地图历史查询

- **工具**: `gsimap_get_history`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0002`
- **预期结果**: 返回 n0001（含地图）和 n0002（继承）的历史条目

```json
{"tool": "gsimap_get_history", "args": {"worldId": "test_integration", "nodeId": "n0002"}}
```

### Step 10: 地图 Diff 查询

n0002 本身无地图修改，diff 应为空或仅含继承标记。

- **工具**: `gsimap_get_diff`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0002`
- **预期结果**: 返回 n0002 的 diff（空或 minimal）

```json
{"tool": "gsimap_get_diff", "args": {"worldId": "test_integration", "nodeId": "n0002"}}
```

## 预期通过标准

- [ ] Step 1: 地图生成成功，无异常
- [ ] Step 2: (0,0) hex 可查询，含 terrain type
- [ ] Step 3: 邻居查询返回有效 hex（含坐标偏移 ±1）
- [ ] Step 4: 半径查询返回约 37 个 hex
- [ ] Step 5: region 列表可查询（至少含默认区域）
- [ ] Step 6: 城市列表正常返回（可为空）
- [ ] Step 7: 距离计算 = 5
- [ ] Step 8: n0002 继承 n0001 的地图
- [ ] Step 9: 历史查询含 n0001 和 n0002
- [ ] Step 10: diff 查询正常

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| gsimap_generate 超时 | radius 太大 | 减小到 40 以下 |
| (0,0) 查询返回 water | 随机种子导致中心为海 | 换 seed 重试，或选一个已知陆地坐标 |
| 邻居不足 6 个 | 地图边缘 | 选地图内部坐标重试 |
| n0002 hex 与 n0001 不同 | MapResolver 父链回溯失败 | 检查 MapResolver.walkParentChain 逻辑 |
| get_history 为空 | MapStore 未正确保存 | 检查 attachment 文件是否存在 |

## 扩展测试（可选）

- **E4.1**: 不同 seed 生成的地图显著不同（随机性验证）
- **E4.2**: landRatio=0.8 地图陆地占比显著高于 landRatio=0.2
- **E4.3**: 极端 radius=10 和 radius=150 的边界测试
- **E4.4**: 在 n0002 修改一个 hex 后，验证 MapResolver diff 应用
- **E4.5**: gsimap_get_neighbors 对地图边缘 hex 的边界行为
