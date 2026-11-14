# 贴墙标识方块（mtrlte:wall_sign_*）

钉在墙面上的站名牌：**MTR 4.0.5 的「站名出入口」塔式模型**（`station_name_entrance_tall`），
上墙后自动和相邻的同款牌子连成一条、显示车站名、刷子切换高矮两种形态。**只做了 1.20.1。**

和[站外标志](station-sign-blocks.md)（`mtrlte:station_sign_*`，16 格高的立地 / 挑墙大灯箱）**不是一回事**：
那个是进站口招牌，这个是墙上贴的站名牌。

## 三个方块 = 一套行为 × 三张品牌 logo

| id（`mtrlte:` 前缀省略） | 品牌 | 模型 | 品牌 logo（画在牌子左下角） |
|---|---|---|---|
| `wall_sign_xiamen` | 厦门地铁 | `block/station_name_entrance_tall` / `_short` | `block/xiamen_logo` |
| `wall_sign_jiaomei` | 角美地铁 | 同上 | `block/jiaomei_logo` |
| `wall_sign_lte` | LTE | 同上 | `block/lte_logo` |

三块的形状、站名、连接、刷子行为**完全一样**（`WallSignBlock` 实例化三次，共用一个方块实体类型
`mtrlte:wall_sign_xiamen`），差别只有那张品牌 logo。

## 模型从哪来：MTR 4.0.5 原样拷贝

源文件：`assets/mtr/models/block/station_name_entrance_tall.json`（4.0.5）。

```json
{ "textures": { "particle": "mtr:block/dark_blue" },
  "elements": [ { "from": [0,0,0.05], "to": [16,16,0.05],
                  "faces": { "north": { "texture": "#particle", "cullface": "north" },
                             "south": { "texture": "#particle" } } } ] }
```

只有一个元素、**零厚度**、只有 north（贴墙、`cullface`）/ south（朝外）两个面。
也就是说**方块模型只是一块「灯」**：牌子的颜色和站名都是 BER（`WallSignRenderer`）画的 ——
MTR 自己也是这个分工（它的 `RenderStationNameTall` 挂在它自己的方块实体类型上，我们用不了）。

拷贝进本模组时只改命名空间：

| 本模组 | 内容 |
|---|---|
| `models/block/station_name_entrance_tall.json` | 原样，贴图 → `mtrlte:block/dark_blue` |
| `models/block/station_name_entrance_short.json` | 原样（`y 4..12`），贴图 → `mtrlte:block/dark_blue` |
| `textures/block/dark_blue.png` | 从 MTR 4.0.5 拷贝（方块被挖开时的粒子贴图也是它） |
| `textures/block/white.png` | 从 MTR 4.0.5 拷贝；渲染器用它当「纯白色块」，颜色由顶点色乘出来 |

## 三个属性

| 属性 | 取值 | 做什么 |
|---|---|---|
| `facing` | 4 个水平方向 | **墙在哪一侧**（`pos.offset(facing)` 那格必须是实心方块）。牌面朝 `facing.getOpposite()`，与站外标志挂墙款、原版告示牌同一套。 |
| `style` | `0` 矮 / `1` 高 | MTR 里叫 `propagate_property`（`IntProperty.of("propagate_property", 0, 1)`，javap 与 3.6.4.2 / 4.0.5 都对过），语义相同：0 → short 模型，1 → tall 模型。 |
| `link` | `0..63` 位掩码 | 和相邻同款牌子连上了没有（见下）。 |

## 自动连接：`link` 位掩码

位 = `1 << direction.getId()`（Minecraft 的 `Direction` 序列 `DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5`）：

```
DOWN=1  UP=2  NORTH=4  SOUTH=8  WEST=16  EAST=32
```

一格最多 6 个邻居 → 值域 `0..63`。判「连上」三个条件全满足（`WallSignBlock#connectsTo`）：
同一个方块、**同一面墙**（`facing` 相等）、**同一形态**（`style` 相等）。

- 位的维护：`getStateForNeighborUpdate` 里**整张掩码重算**（6 次 `getBlockState`，比按位打补丁更难写错），
  放置时在 `getPlacementState` 里先算好（自己不会触发自己的邻居更新）。
- 为什么是位掩码而不是 6 个 `BooleanProperty`：这 6 位**不参与选模型变体**
  （零厚度的板，两格并排在视觉上本来就无缝，MTR 的塔式模型也没有拼接件），
  唯一的消费者是渲染器 —— 它要拿这 6 位算出「这条牌子横跨几格、竖跨几格、由哪一格负责画字」。
  一个 `IntProperty` 让 `blockstates` 保持 2×4 个变体，而不是 2×4×64。
- 用途：`WallSignBlock#runOf(...)` 返回 `Run(width, height, anchor)`；只有**左下角那一格**
  （左边没连、下面没连）落笔，牌面按 `width × height` 铺满整条 —— 一条三格的牌子上站名只出现一次。

## 站名

方块实体（`WallSignBlockEntity`）是**纯标记**，不存站名、不存 NBT、不参与网络同步。
渲染器每帧按位置现取：

```java
RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)   // MTR 的按位置判定
```

和 MTR 的 `RenderStationNameBase` 同一条链路。取不到车站（站外 / 还没建站）就画 MTR 的
`gui.mtr.untitled`（「未命名」）。牌子底色 = 车站色（`Station#color`，为 0 时用深蓝 `#1B2A6B`），
字色按底色亮度自动取黑 / 白。

## 刷子切换

`onUse` 走 MTR 的 `IBlock.checkHoldingBrush(world, player, runnable)`（它自带「只在服务端跑」的守卫）。

- **取值循环：`style` 0 → 1 → 0**（矮 → 高 → 矮）；
- 改完**沿墙向左右两侧各传一遍**（`facing.rotateYClockwise()` / `rotateYCounterclockwise()`，
  `propagateStyle`），一排牌子一起换形态；上下不传；
- `style` 变了以后，`style` 不等的两格不再算「连上」，所以落状态时同时把 `link` 重算一遍。

## 形状

- 框选形状：薄板（`z 0..1`），`y` 按形态取 `4..12`（矮）/ `0..16`（高）；
- 碰撞形状：`VoxelShapes.empty()` —— MTR 的站名出入口也是空的（javap 核对过），薄牌面不该挡人；
- 亮度 15、`nonOpaque()`、`requiresTool().strength(2.0F)`、金属音、`mineable/pickaxe` 标签；
- 墙没了 → 掉物品 + 变空气。

## 渲染

`WallSignRenderer`（`WallSignClient` 注册，`fabric.mod.json` 的 `client` 入口）：

```
translate(0.5, 0, 0.5) → rotateY(-blockRotation(facing)) → translate(-0.5, 0, -0.5)   // 模型空间，+Z 朝外
translate(0, 0, PLATE_Z) → scale(1/16)                                              // 1 单位 = 1 px，+Y 朝上
```

- 牌面：一块纯色四边形（`white.png` × 顶点色 = 车站色），铺满 `width × height`；
- 品牌 logo：`width >= 2` 时画在左下角（10×10 px，`RenderLayer.getEntityTranslucentEmissive`）；
- 站名：`TextRenderer.draw` + `matrix`，再 `scale(s, -s, 1)` 把 Y 翻成 GUI 的「向下为正」
  （`RenderLayer.getText` 关掉了背面剔除，负 Y 缩放不会让字消失）。

## 页签顺序

- 厦门 / 角美：进「厦门风格物件」（`mtrlte:core`），前排顺序由 `LogoObjects.pinCoreTabOrder()`
  重排成「两个 logo → 这两个贴墙标识 → 四件站外标志」；
- LTE：进「LTE 工具」（`mtrlte:tools`），由 `WallSignObjects.pinToolsTabOrder()` **钉在最后**
  （LTE logo → 五件工具物品 → 贴墙标识），不依赖「谁后注册谁在后」。

## 生成 / 校验

- `tools/gen-wall-sign-assets.js`：从 MTR 4.0.5 那份 json 生成两个模型（改命名空间）、
  三个 blockstate（multipart 2 形态 × 4 朝向）、三个 item 模型、三张战利品表。
- `node tools/verify-wall-signs.js v1.20.1`：不启动游戏的静态校验 —— 模型 / blockstate /
  `WallSignBlock` 里的形状常量 / 贴图引用（必须都是 `mtrlte:` 且文件存在、不得出现 `mtr:` 前缀）/
  item 模型 / 战利品表 / 语言文件 / 标签，以及 Java 里 `style` / `link` 的属性定义。
