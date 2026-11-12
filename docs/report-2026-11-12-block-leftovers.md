# 方块四个遗留问题（1.20.1）

范围：只改 `v1.20.1`。编译 `gradle -p v1.20.1 compileJava` → **EXIT 0 / 0 错误**（4 个既有警告），未提交 git，未启动游戏。

---

## 1. 站台贴图变灰 —— 箭头层的透明像素被画成了不透明黑板（不是模型被换掉）

### 现象与原先的猜测

实机截图：屏蔽门所在的那一条站台整块变深灰、上面压着白/绿箭头，旁边的大理石正常。

原来的猜测是「`BlockPlatformDoorTypeMixin` 让 `door_type` 把整块模型换成 MTR 的 `platform_psd`」。
**这条是错的**，有两条硬证据：

1. `blockstates/marble_platform.json` 里从头到尾没有引用任何 `mtr:` 模型。新增的
   `tools/check-platform-models.js` 把 `facing × door_type × side` 的**全部 60 个状态**逐一求值，
   60/60 都只落到 `mtrlte:block/marble_platform(+arrow_n)`，见下面的表。
2. 模型 id 只能由 blockstate 决定；MTR 3.6.4.2 里没有任何「换模型」的 mixin
   （`mtr/mixin/` 只有 7 个类，与方块模型无关），`mtr.block.BlockPlatform` 也不重写任何渲染方法。

### 真正的原因

站台 = 大理石本体 + 一块 `y=16.1` 的整格箭头薄片（`marble_platform_arrow_1..4`）。
箭头贴图 `mtrlte:block/platform_arrow`（256×128，照抄 MTR 的 `mtr:block/platform_arrow`）里
**只有箭头本身不透明**：

| 像素 | 数量 | alpha |
|---|---|---|
| 透明 | 26663 | 0 |
| 深灰底 `#333333` | 5111 | 255 |
| 纯白（箭头） | 162 | 255 |

而**方块模型默认走 `RenderLayer.getSolid()`，那一层不做 alpha 测试**：alpha=0 的像素连同它的颜色（黑）
一起写进缓冲区。于是「屏蔽门放上去 → 父类把 `side` 算成 1..4 → 叠上箭头模型 → 整格 16×16 的顶板把它 80% 的
透明部分画成一块深灰板盖住大理石」。这也解释了为什么**只有门那一排变灰**（只有 `side=1..4` 才叠箭头层）。

### 修法

新增 `decor/DecorClient.java`（`ClientModInitializer`，已加进 `fabric.mod.json` 的 client 入口点），
把 `marble_platform` / `marble_floor` 挂到 `RenderLayer.getCutout()` ——
**MTR 自己给 `Blocks.PLATFORM` 挂的就是 cutout**（`mtr.MTRClient` 里
`RegistryClient.registerBlockRenderType(RenderLayer.getCutout(), Blocks.PLATFORM.get())`）。

实测依据：`floor_hangzhou`（1744×1744）被模型用到的 `uv 0..8`（左上 872×872）区域 alpha=0 像素 **0 个**，
所以挂 cutout 不会让大理石本体变透；箭头层之外全部消失，箭头本身仍是纯色。
箭头贴图自带的那条 `#333333` 底色会保留（MTR 原版站台看到的是同一张图，效果一致）。

### `(facing × side × door_type)` → 模型（`node tools/check-platform-models.js`）

`door_type` 在 multipart 的 `when` 里没出现 = 通配，**三种门型结果完全一样**，所以表按 `facing × side` 给，
三种门型各出一份，共 12 + 48 = 60 个状态。

| facing | y | side=0 | side=1 | side=2 | side=3 | side=4 |
|---|---|---|---|---|---|---|
| north | 0 | 本体 | 本体 + `arrow_1` | 本体 + `arrow_2` | 本体 + `arrow_3` | 本体 + `arrow_4` |
| east | 90 | 本体 | 本体 + `arrow_1` | 本体 + `arrow_2` | 本体 + `arrow_3` | 本体 + `arrow_4` |
| south | 180 | 本体 | 本体 + `arrow_1` | 本体 + `arrow_2` | 本体 + `arrow_3` | 本体 + `arrow_4` |
| west | 270 | 本体 | 本体 + `arrow_1` | 本体 + `arrow_2` | 本体 + `arrow_3` | 本体 + `arrow_4` |

- 「本体」= `mtrlte:block/marble_platform`（大理石贴图 + 警戒黄带），箭头 = `mtrlte:block/marble_platform_arrow_1..4`
  （MTR 的 `platform_arrow` 那张图，8×16 的半张 UV，四向各自一档）。
- 脚本断言：每个状态至少命中一个模型（不会 MissingModel）、命中的**全部**是 `mtrlte:block/*`、
  模型文件与它引用的贴图都存在。输出：`✓ 60/60 状态全部解析到本模组的模型，没有一条落到 mtr: 的灰色站台模型上`。
- 复现：`cd v1.20.1 && node tools/check-platform-models.js`（打印全部 60 行）。

---

## 2. 扶手镜像内转角换模型

`railing_stair_corner_mirror_on_wall.json` 还是**上一批（改动前）的导出**：几何在 `y=20..21`，
而 2026-11-12 用户换了新内转角（`railing_stair_corner_on_wall.json` 降到 `y=19..20`），
于是两个内转角一高一低，接缝处差 1/16 格。

改法：把镜像那一份整体降到同一批次的高度 `y=19..20`（`from/to` 的 y 各减 1），
两个内转角现在互为真镜像：

```
corner        [{from:[1,19,13], to:[3,20,16]}, {from:[0,19,13], to:[1,20,15]}]
corner_mirror [{from:[1,19,0],  to:[3,20,3]},  {from:[0,19,1],  to:[1,20,3]}]
```

`RailingWallBlocks` 里镜像那段的包围盒 `box(0,19,0,3,21,3)` 本来就裹得住 19..20，不用改；
`tools/check-railing-shapes.js` → `✓ 201 个模型元素全部落在包围盒内`。

---

## 3. 两种栏杆的碰撞高度

`RailingWallBlock` 里原来**框选盒从 y=0 起、碰撞盒只留在几何那一层**：

| | 上一版框选 | 上一版碰撞 |
|---|---|---|
| 墙上扶手（平直段） | 0..20 | 18..20 |
| 墙上扶手（斜段） | 0..31 | 14..31 |
| 玻璃扶手 | 0..17.5 | 0..17.5 |

于是墙上扶手脚下的 `y=0..14/18` **完全没有碰撞**：人能直接走进栏杆里，只有胸口那一小片被一片悬空的薄片挡住
——实机就是「碰撞高度不对」（要么穿过去、要么被看不见的薄片卡住）。玻璃扶手反而是对的，两种栏杆手感不一致。

改法：框选与碰撞**共用同一份** `stretchToFloor` 过的形状（`shapes[]`），即两种栏杆统一成
**「地面 y=0 → 几何顶部」**：

| | 现在（框选 = 碰撞） |
|---|---|
| 墙上扶手 平直 / 端部 / 内转角 | 0..20（端部 0..20.5） |
| 墙上扶手 斜段 / 起点 | 0..31 |
| 玻璃扶手 平直 / 端部 | 0..17.5 |
| 玻璃扶手 斜段 | 0..29.5 |

这与参考模组一致：MSD `BlockRailingStair#getOutlineShape2` 的手写盒子一律是 `y=0..24`，
`javap -c` 里就是 `13.0d, dconst_0, dconst_0, 16.0d, 24.0d, 3.0d`（x1,y1,z1,x2,y2,z2）连着
`IBlock.getVoxelShapeByDirection(...)`，即「从方块地面一直挡到扶手顶」。栏杆就是「从地面挡住的一段」。

---

## 4. 安检机 working 灯常亮微光

素材里三颗灯是三个独立元素：`green`（元素 5）、`red`（6）、`working`（7）。
上一版把 green/red 的「灭」做成了指向贴图上那块 `#353637` 暗色（UV 8.3..8.7 / 0.55..0.95），
但 **working 一直指着贴图上的纯白 `#ffffff`** —— 也就是永远「亮着」，可它又跟着普通方块吃方向光，
看着就是一团微光。

改法（`temp/fix-scanner-lamps.js`，三份模型一起改）：

1. 待机模型 `liquid_scanner.json` 里元素 7 的 6 个面 → 同一块灭灯暗色，
   于是 working 灯**只在检测的三秒里亮**（`_green` / `_red` 两份模型仍然指着白色）；
2. 三盏灯的三个元素都加 `"shade": false`（指示器是自发光的，不该再乘一次方向光）——
   亮起来的时候是纯色，不再「只是比周围亮一点点」。

`liquid_scanner`（待机）= 三灯全灭；`_green` = 绿 + working 亮；`_red` = 红 + working 亮。
灯元素的位置 / 尺寸 / 45° 旋转一个没动。

---

## 改动文件

- 新增 `v1.20.1/src/main/java/dev/js/mtrheadlight/decor/DecorClient.java`
- 改 `v1.20.1/src/main/resources/fabric.mod.json`（client 入口点加一行）
- 改 `v1.20.1/src/main/java/dev/js/mtrheadlight/railing/RailingWallBlock.java`（形状合一份、注释同步）
- 改 `v1.20.1/src/main/java/dev/js/mtrheadlight/railing/RailingWallBlocks.java`、`RailingGlassBlocks.java`（注释同步）
- 改 `v1.20.1/src/main/java/dev/js/mtrheadlight/security/LiquidScannerBlock.java`（注释同步）
- 改 `assets/mtrlte/models/block/railing_stair_corner_mirror_on_wall.json`（y 20..21 → 19..20）
- 改 `assets/mtrlte/models/block/liquid_scanner{,_green,_red}.json`（working 灯灭 + 三灯 `shade:false`）
- 新增 `v1.20.1/tools/check-platform-models.js`
