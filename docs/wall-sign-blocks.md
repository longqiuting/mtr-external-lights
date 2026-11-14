# 贴墙标识方块（mtrlte:wall_sign_*）

钉在墙面上的站名牌：**MTR 站名出入口的语义**（`BlockStationNameEntrance`：自动连成一条、显示站名、
刷子切高矮），但**模型和贴图全是本模组自己的** —— 一块深藏青灯板 + 一块品牌 logo，站名由我们自己的
`WallSignRenderer` 画在灯板左边那条里。**只做了 1.20.1。**

和[站外标志](station-sign-blocks.md)（`mtrlte:station_sign_*`，立地 / 挑墙大灯箱）**不是一回事**：
那个是进站口招牌，这个是墙上贴的站名牌。

## 三个方块 = 一套行为 × 三张品牌 logo

| id（`mtrlte:` 前缀省略） | 品牌 | 模型 | 品牌 logo |
|---|---|---|---|
| `wall_sign_xiamen` | 厦门地铁 | `wall_sign_xiamen_{short,tall}` | `block/xiamen_logo` |
| `wall_sign_jiaomei` | 角美地铁 | `wall_sign_jiaomei_{short,tall}` | `block/jiaomei_logo` |
| `wall_sign_lte` | LTE | `wall_sign_lte_{short,tall}` | `block/lte_logo` |

三块的形状、站名、连接、刷子行为**完全一样**（`WallSignBlock` 实例化三次，共用一个方块实体类型
`mtrlte:wall_sign_xiamen`），差别只有那张品牌 logo。

## 模型：两层，全部自有贴图

```
models/block/station_name_entrance_tall.json     ← 基础模型：灯板 16x16 + logo quad（贴图默认 xiamen）
models/block/station_name_entrance_short.json    ← 基础模型：灯板 16x8（y 4..12）+ logo quad
models/block/wall_sign_<id>_{tall,short}.json    ← 子模型：parent 上面两个，只覆盖 textures.logo
```

| textures 键 | 值 | 用途 |
|---|---|---|
| `board` | `mtrlte:block/station_name_background` | 牌面底图（16×16 深藏青 + 顶亮 / 底暗 + 1 px 内框） |
| `particle` | 同 `board` | 挖掘粒子 |
| `logo` | `mtrlte:block/{xiamen,jiaomei,lte}_logo` | 品牌 logo（挖空 PNG，走 cutout） |

**一个 `mtr:` 前缀都没有** —— 这条由 `node tools/verify-wall-signs.js` 逐项断言。

几何（模型空间，1 px = 1/16 格）：

| 元素 | 高板 | 矮板 | 说明 |
|---|---|---|---|
| 灯板 | `[0,0,0.05]→[16,16,0.05]` | `[0,4,0.05]→[16,12,0.05]` | 零厚度；`north` 面 `cullface=north`（贴墙那侧），`south` 面是牌面 |
| 品牌 logo | `[10.5,5.5,0.1]→[15.5,10.5,0.1]` | 同左 | 5×5 px，竖直居中；只有 `south` 面，`shade=false` |

三层的 z 顺序（越靠近看的人 z 越大）：**灯板 0.05 → logo 0.1 → 站名 0.15**。站名平面是 MTR 的
`zOffset`（0.00625）+ 固定 0.003125 推出来的，所以 logo 必须落在 0.05..0.15 之间才「在牌子前面、
又被字压不住」。README 里那句「logo 要叠在牌面上」就是这个意思。

## 站名：自己的渲染器，不用 MTR 的 `RenderStationNameTiled`

上一版直接挂 MTR 的 `new RenderStationNameTiled(dispatcher, true)`，`showLogo=true` 那条路走
`ClientData.DATA_CACHE.getStationNameEntrance(...)` → `RouteMapGenerator.generateStationNameEntrance(...)`，
它生成贴图时会**往左边画死 MTR 自己的 logo**（`textures/block/sign/logo.png`，红底白 M）。
那张图是运行时拼的、资源包换不掉 —— 所以三种牌子都挂着 MTR 的 logo。换成 `showLogo=false` 又会把
站名铺满整条板、压到我们的品牌 logo 上。

现在是 `WallSignRenderer extends RenderStationNameBase`，只覆写 `drawStationName`：

- 贴图：`ClientData.DATA_CACHE.getStationName(name, aspectRatio)` —— 白字、透明底、**不含 logo**；
- 落笔：`RenderTrains.scheduleRender(..., QueuedRenderLayer.INTERIOR, ...)`（和 MTR 的站名同一条提交
  路径；`INTERIOR` = `getEntityCutout`）。矩阵必须由队列喂进来 —— `StoredMatrixTransformations` 是绝对
  世界坐标，自己 `new MatrixStack()` 会丢掉相机相对偏移；
- 版面：站名只画在 `x -0.46875..0.125`（模型 0.5..10.0 px），右边 10.5..15.5 px 归 logo；
  高板字带高 12 px、矮板 6 px，都竖直居中；
- 拼字：`getStationName` 的贴图铺满整条牌子（aspect = `总格数 × 字带宽 / 字带高`），每格用
  `u1=(lengthLeft-1)/total .. u2=lengthLeft/total` 切自己那一段 —— `length()` 是照抄 MTR `getLength`
  的数格子法（沿 `facing.rotateYClockwise()/Counterclockwise()` 数**同一个方块实例**，所以不同品牌的
  牌子不会串在一起）。
- `STYLE >= 2`（MTR 的「无灯板」形态）直接不画：blockstate 里本来就没挂模型。

## 三个属性（全是 MTR 父类的）

| 属性 | 取值 | 做什么 |
|---|---|---|
| `facing` | 4 个水平方向 | 墙在哪一侧（`pos.offset(facing)` 那格必须是实心方块）。模型正面朝 `facing.getOpposite()`。 |
| `propagate_property`（Java 里的 `STYLE`） | `0..5` | 偶数 = 矮板、奇数 = 高板；`2/3` 黑字无灯板、`4/5` 白字无灯板。**只有 0/1 挂模型**（与 MTR 一致）。 |
| — | — | 没有 `link` 位掩码：MTR 不存连接状态，渲染时现数（`getLength`）。 |

## 刷子 / 形状 / 掉落

全在父类 `BlockStationNameEntrance` 里，我们一行都不重写：

- 刷子：`onUse` 走 MTR 的画笔，`STYLE.cycle()` + 沿墙向左右各传一格（一排一起换形态）；
- 框选：薄板（`y` 4..12 / 0..16，`z` 0..1），碰撞箱 `VoxelShapes.empty()`（薄牌面不挡人）；
- 支撑：背后那格必须实心，否则掉物品变空气；
- 方块设置逐字照 MTR：`requiresTool().strength(2.0F).nonOpaque()`，**没有 luminance**
  （那块板是普通贴图，不是自发光方块）。

## 页签顺序

「厦门风格物件」（`mtrlte:core`）由 `LogoObjects.pinCoreTabOrder()` 统一重排，最前排固定是：

```
厦门 logo 块 → 角美 logo 块 → 厦门贴墙标识 → 角美贴墙标识 → LTE 贴墙标识 → 四件站外标志（挂墙/杆上 × 厦门/角美）
```

即三面牌子**紧跟在两个 logo 块后面**（用户：「贴墙标识要放到两个 logo 块的后面」）。
`wall_sign_lte` 同时也在「LTE 工具」（`mtrlte:tools`）页签的第 2 位（LTE logo 之后、仪表板之前），
同一个方块两页都能拿到。

## 生成 / 校验

- `node tools/gen-wall-sign-assets.js v1.20.1`：生成两个基础模型、六个子模型、三个 blockstate、
  三个 item 模型、三张战利品表；`station_name_background.png` 只在缺失时生成。**重跑不会改动
  仓库里已有的文件**（除 line-ending 由 git 自己管）。
- `node tools/verify-wall-signs.js v1.20.1`：不启动游戏的静态校验，重点是这几条不变量 ——
  - 模型（含父模型覆盖后）里不许出现 `mtr:`；每个 `textures` 值都必须 `mtrlte:` 开头且 png 存在；
  - 渲染器不许出现 `getStationNameEntrance(`，必须用 `getStationName(`；
  - logo quad 的 z 必须在灯板前面、站名平面后面，x 必须在站名带右边界之外、竖直范围和字带同带；
  - blockstate 8 条变体指向本 id 自己的模型、灯板 y 与 MTR `getOutlineShape` 一致；
  - item 模型 / 战利品表 / lang / `mineable/pickaxe`。
