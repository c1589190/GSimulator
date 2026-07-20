# T05: 地图修改操作

## 前置条件

- T04 通过（n0001 有程序化生成的地图，landRatio=0.5）

## 测试步骤

### Step 1: 确保在 n0001 节点

- **工具**: `node_switch`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`
- **预期结果**: 切换成功

```json
{"tool": "node_switch", "args": {"worldId": "test_integration", "nodeId": "n0001"}}
```

### Step 2: 寻找合适的种子 hex

找一个已知的陆地 hex 作为 Nation 起始点。使用 query_radius 在中心附近寻找。

- **工具**: `gsimap_query_radius`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`, `q`: 10, `r`: 0, `radius`: 3
- **预期结果**: 返回 hex 列表，从中选一个 terrain 为 lowland/hills/plains 的坐标（记为 SeedQ, SeedR）

```json
{"tool": "gsimap_query_radius", "args": {"worldId": "test_integration", "nodeId": "n0001", "q": 10, "r": 0, "radius": 3}}
```

### Step 3: 初始化 Nation

在选定的种子 hex 上 flood-fill 创建 Nation。

- **工具**: `gsimap_init_nation`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `name`: `test_nation`
  - `seedQ`: `10`（从 Step 2 选陆地坐标）
  - `seedR`: `0`
  - `maxHexes`: `500`
  - `capital`: `测试城`
  - `ruler`: `测试王`
  - `religion`: `测试教`
  - `faction`: `测试王国是一个尚武的北方势力。`
  - `narrative`: `纪元元年春，测试王国在北方边境建立要塞。`
  - `worldview`: `测试王国信奉力量至上。`
- **预期结果**: 返回成功，province "test_nation" 创建，含 hex 列表和 capital 城市

```json
{"tool": "gsimap_init_nation", "args": {"worldId": "test_integration", "nodeId": "n0001", "name": "test_nation", "seedQ": 10, "seedR": 0, "maxHexes": 500, "capital": "测试城", "ruler": "测试王", "religion": "测试教", "faction": "测试王国是一个尚武的北方势力。", "narrative": "纪元元年春，测试王国在北方边境建立要塞。", "worldview": "测试王国信奉力量至上。"}}
```

### Step 4: 验证 Nation 省

- **工具**: `gsimap_get_province`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`, `name`: `test_nation`
- **预期结果**: 返回 test_nation 的完整信息，含 hex 列表和城市"测试城"

```json
{"tool": "gsimap_get_province", "args": {"worldId": "test_integration", "nodeId": "n0001", "name": "test_nation"}}
```

### Step 5: 城市列表验证

- **工具**: `gsimap_get_cities`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`
- **预期结果**: 列表中包含"测试城"，含坐标

```json
{"tool": "gsimap_get_cities", "args": {"worldId": "test_integration", "nodeId": "n0001"}}
```

### Step 6: 创建 Region

- **工具**: `gsimap_create_region`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `name`: `test_region`
  - `description`: `测试区域——北部森林`
  - `color`: `#228B22`
  - `hexes`: ``（空，稍后手动添加）
- **预期结果**: test_region 创建成功

```json
{"tool": "gsimap_create_region", "args": {"worldId": "test_integration", "nodeId": "n0001", "name": "test_region", "description": "测试区域——北部森林", "color": "#228B22"}}
```

### Step 7: 添加 hex 到 Region

从 test_nation 省中选一个 hex 坐标添加到 test_region。

- **工具**: `gsimap_add_hex_to_region`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `name`: `test_region`
  - `q`: `11`
  - `r`: `0`
- **预期结果**: hex (11,0) 加入 test_region

```json
{"tool": "gsimap_add_hex_to_region", "args": {"worldId": "test_integration", "nodeId": "n0001", "name": "test_region", "q": 11, "r": 0}}
```

### Step 8: 移除 hex

- **工具**: `gsimap_remove_hex_from_region`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `name`: `test_region`
  - `q`: `11`
  - `r`: `0`
- **预期结果**: hex (11,0) 从 test_region 移除

```json
{"tool": "gsimap_remove_hex_from_region", "args": {"worldId": "test_integration", "nodeId": "n0001", "name": "test_region", "q": 11, "r": 0}}
```

### Step 9: 更新 Region 属性

- **工具**: `gsimap_update_region`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `name`: `test_region`
  - `description`: `更新后的描述——南部草原`
  - `color`: `#FFD700`
  - `tag`: `Test`
- **预期结果**: test_region 的 description、color、tag 均已更新

```json
{"tool": "gsimap_update_region", "args": {"worldId": "test_integration", "nodeId": "n0001", "name": "test_region", "description": "更新后的描述——南部草原", "color": "#FFD700", "tag": "Test"}}
```

### Step 10: Rename Region

- **工具**: `gsimap_rename_region`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `oldName`: `test_region`
  - `newName`: `test_region_renamed`
- **预期结果**: 重命名成功，provinces/checkpoint/cities 中的引用同步更新

```json
{"tool": "gsimap_rename_region", "args": {"worldId": "test_integration", "nodeId": "n0001", "oldName": "test_region", "newName": "test_region_renamed"}}
```

### Step 11: 修改地形类型定义

- **工具**: `gsimap_update_terrain_type`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `key`: `forest`
  - `name`: `密林`
  - `color`: `#006400`
  - `moveCost`: `3`
  - `food`: `2`
  - `gold`: `1`
  - `stone`: `3`
  - `description`: `茂密的原始森林，移动困难但资源丰富`
- **预期结果**: terrain "forest" 的定义已更新

```json
{"tool": "gsimap_update_terrain_type", "args": {"worldId": "test_integration", "nodeId": "n0001", "key": "forest", "name": "密林", "color": "#006400", "moveCost": 3, "food": 2, "gold": 1, "stone": 3, "description": "茂密的原始森林，移动困难但资源丰富"}}
```

### Step 12: 河流寻路

从某个陆地 hex 出发寻找通往水体的最低成本路径。

- **工具**: `gsimap_find_river_path`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0001`
  - `q`: `10`
  - `r`: `0`
- **预期结果**: 返回一条河流路径（hex 坐标数组），可能指向最近的水体或地图边缘

```json
{"tool": "gsimap_find_river_path", "args": {"worldId": "test_integration", "nodeId": "n0001", "q": 10, "r": 0}}
```

## 预期通过标准

- [ ] Step 1: 切换到 n0001 成功
- [ ] Step 2: 找到陆地 hex
- [ ] Step 3: Nation 初始化成功，含 capital、faction 信息
- [ ] Step 4: Province 查询返回完整数据
- [ ] Step 5: 城市列表含"测试城"
- [ ] Step 6: Region 创建成功
- [ ] Step 7: hex 添加成功
- [ ] Step 8: hex 移除成功
- [ ] Step 9: Region 属性更新成功
- [ ] Step 10: Region 重命名成功
- [ ] Step 11: terrain type 定义更新成功
- [ ] Step 12: 河流寻路返回有效路径

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| init_nation flood-fill 收集 hex=0 | 种子坐标是水 | 换一个确认的陆地坐标 |
| get_province 返回空 | Nation 未正确保存 | 检查 MapService.saveMap() 是否正确调用 |
| rename_region 后旧名仍存在 | checkpoint 引用未更新 | 检查 renameRegion 中所有引用更新逻辑 |
| rename_region 后城市丢失 | cities map key 未同步 | 确认 MapData rename 逻辑覆盖 cities |
| find_river_path 返回空 | 无相邻水体 | 尝试不同起始坐标或降低期望（允许返回空） |

## 扩展测试（可选）

- **E5.1**: 连续创建多个 Nation，验证它们不会重叠（不同 seed hex 不同区域）
- **E5.2**: 地形修改后查询 hex 验证新定义生效
- **E5.3**: Region 增删 hex 后 list_regions 验证 hexCount 变化
- **E5.4**: 在 n0002 创建一个新 Nation，验证 diff 应用后 n0002 底图 = n0001 底图 + n0002 diff
