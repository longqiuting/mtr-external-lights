# 屏蔽门吊板（`BlockPSDTop`）绘制原理与可替换点

日期：2026-11-10 · 目标版本：MTR-fabric-1.20.1-**3.6.4.2** + `ziyue.tjmetro` **TianjinMetro-1.20.1-1.1.3**
证据来源：`D:\MC-MTR-JS\temp\mtrjar`（jar 解包）、`D:\MC-MTR-JS\temp\tj`（天津包解包）、CFR 0.152（`temp/cfr.jar`）、`javap -p -c`（JDK 17）。
本文只做调研，不含任何代码改动。姊妹文档：`docs/mtr-psd-rendering.md`（门扇 BER 那一半）。

---

## 0. 一句话结论

**吊板是「静态方块模型 + 每帧 BER 自绘面板」的混合体**：
模型（10 个 multipart 变体）画两侧托架/圆弧下沿/外壳，贴图是 `mtr:textures/block/psd_top.png`（**64×64**）；
BER `mtr.render.RenderPSDTop` 画三样东西 —— ①端头/顶面的外壳补片（同一张 `psd_top.png` 的 UV 切片，**Java 里写死**）、
②**内凹的显示面板**（运行时用 AWT 生成的动态贴图：线路图 / 站名 / 方向箭头，`transparentWhite = true`，纯白被抠成透明）、
③面板下方 0.5px 的**线路色带**（1×N 像素的动态贴图横向拉伸）。

关键含义：**面板本身没有背景贴图**。想上实拍图，不要试图改那张动态贴图，而是
「自己画一张背景四边形，排在面板之前」——本文 §6 给出确定可行的图层顺序（`QueuedRenderLayer.LIGHT` 早于 `EXTERIOR`）。

---

## 1. 绘制入口

| 项 | 值 |
| --- | --- |
| 方块 | `mtr.block.BlockPSDTop`（`EntityBlockMapper`，独立方块，y+2 那一格） |
| 方块实体 | `mtr.block.BlockPSDTop$TileEntityPSDTop extends TileEntityRouteBase extends BlockEntityMapper`（**无 NBT、无 sync 字段**） |
| BER | `mtr.render.RenderPSDTop extends RenderRouteBase<BlockPSDTop$TileEntityPSDTop>` |
| 注册 | `mtr.MTRClient` 构造：`RegistryClient.registerTileEntityRenderer(BlockEntityTypes.PSD_TOP_TILE_ENTITY.get(), RenderPSDTop::new)` |
| 实际 render 签名 | `RenderRouteBase#render(T entity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay)`（=`class_827#method_3569`） |
| 是否每帧现画 | **是**。`render` 每帧跑，但**位图是缓存的**：`ClientData.DATA_CACHE` 按 key 缓存动态贴图，只有站台/线路数据变动才异步重生成 |

### 1.1 `render()` 是 `final`

```java
// mtr/render/RenderRouteBase.java:49
public final void render(T entity, float tickDelta, class_4587 matrices, class_4597 vertexConsumers, int light, int overlay)
```

所以**子类无法改面板的几何/取样逻辑**，只能覆写四个钩子：

```java
protected abstract RenderType getRenderType(...);            // 画什么（ROUTE / ARROW / STATION_NAME / NONE）
protected abstract void renderAdditional(...);               // 面板之后追加（色带就走这里）
protected void renderAdditionalUnmodified(...);              // 面板之前追加（端头补片走这里）
protected float getAdditionalOffset(...);                    // PERSISTENT 时整体上移 7.5px
protected String getStationNameForRendering(long platformId); // 大站名的文本来源
```

这条 `final` 正是天津地铁**整套复制一份 `RenderRouteBase`** 的原因（§4）。

### 1.2 矩阵链（写自定义图层时必须照抄）

```java
// 全局：以方块中心为原点，+Z 转到 facing 方向
T(0.5 + x, y, 0.5 + z) · Ry(-facing.getHorizontalAngle())

// 面板：先抬 1 格，再绕 Z 转 180°（于是 y 从「方块顶」往下量），再左上平移
T(0, 1, 0) · Rz(180°) · T(-0.5, -getAdditionalOffset(state), z=1.95/16)
```

由此得到的**实测坐标（格 / px）**：

| 量 | 值 |
| --- | --- |
| 面板平面 | 距方块中心 **z = 1.95px**（0.121875 格），法线 = `facing.getOpposite()`（代码传 `facing.method_10153()`） |
| 面板高度 | `1 − 7.5/16 − 1.5/16 = **7/16 格 = 7px**` |
| 面板底边 | 方块底面以上 **1.5px**（`bottomPadding`） |
| 面板顶边 | 方块底面以上 **8.5px**（上面 7.5px 是外壳上唇） |
| PERSISTENT 模式 | 整块上移 **7.5px**，面板改到 9px‥16px（只剩上唇那一段有画） |
| 面板宽度 | `(leftBlocks + rightBlocks + 1) − 2 × (0.125/16)` 格，即「连成一排的同种吊板块数」 |
| 横向留边 | `sidePadding = 0.125/16`（0.125px），只在整排的**两端**生效 |
| 色带 | y ∈ `[0.90625, 0.9375]` = 方块底面上 **1.0‥1.5px**，高 0.5px |

宽度/高度的比值就是所有动态贴图的 `aspectRatio`：`aspect = width / height`。
例：门 2 格 + 两侧玻璃各 1 格 = 4 格 → `aspect = 3.984375 / 0.4375 ≈ 9.11`。

---

## 2. 画了哪几层

一句话：**没有「背景板 / 灯箱 / 到站信息」这三层**。吊板只有外壳（模型）+ 一块内凹面板（BER）+ 一条色带。

| # | 层 | 谁画 | 贴图 | 几何（px，以格=16px 计） | UV |
| --- | --- | --- | --- | --- | --- |
| 1 | 两侧托架 + 圆弧下沿 + 底部薄唇 | **JSON 模型**（10 个 multipart 变体） | `mtr:textures/block/psd_top.png`（64×64）<br>变体 `_edge` 用 `psd_top_edge.png` | 模型单位：主体 `x 0..6, y 1..16, z 8..16`；下唇 `x 0..5, y 0..1`；圆弧两段 22.5° | 见 §2.2 分区表 |
| 2 | 端头外壳补片（整排两端的**斜封面** + 顶面 + 1px/15px 横带 + 底部斜切） | **BER** `RenderPSDTop#renderAdditionalUnmodified` | `mtr:textures/block/psd_top.png`（**Java 写死**，无 format、无字段可改） | 8 个四边形：斜封面 `x≈±0.5, z −0.5..0.25, y 1..16`；顶面 `y=0.996875`；横带 `y=0.0625`/`0.9375`；底部 22.5° 斜切 | `0,0‥1,0.9375` / `0,0.9375‥1,0.96875` / `0,0.96875‥1,1` / `0.125..0.1875` 小片 |
| 3 | **显示面板**（线路图 / 站名 / 方向箭头，三选一） | **BER** `RenderRouteBase#render` | **运行时动态贴图** `mtr:dynamic_texture_*`（AWT BufferedImage → DynamicTexture） | 见 §1.2（7px 高 × N 格宽），z=1.95px | 逐块切片：`u1=(leftBlocks−端部留边)/width` … `u2=(width−rightBlocks+…)/width` |
| 4 | 线路色带 | **BER** `RenderPSDTop#renderAdditional` | 动态贴图（1×N 像素，N = 该站台经过的线路数） | `y 1.0‥1.5px`，x 贯穿整排 | 整张贴图 → 整条矩形（纵向渐变被横向拉伸） |
| 5 | 端头小灯 | JSON 模型 `psd_top_left_end/right_end` | `mtr:textures/block/light_off.png`（16×16） | `x 16..17, y 0..1, z 8..10`，绕 Y 转 45° | `0,0‥1,1` |

### 2.1 没有的东西（别再找）

- **没有背景板贴图**：面板的位置露出的是「模型留空的洞」+ 动态贴图的透明像素。`psd_top.png` 只贴在托架/端面上。
- **没有灯箱/发光面板**：`psd_top_light_on_left.json` / `psd_top_light_off_left.json` 存在，但 `blockstates/psd_top.json` **一处都没引用**（`grep psd_top_light` = 0）；`BlockPSDTop$EnumDoorLight` 也没注册成属性（`method_9515` 只注册了 `facing / side / air_left / air_right / propagate_property / persistent`）。**纯死代码**（与 `docs/mtr-psd-rendering.md:236` 的结论一致）。
- **没有到站信息/倒计时**：那是 PIDS 方块（`IPIDS$TileEntityPIDS`），与吊板无关。

### 2.2 `psd_top.png`（64×64）分区表

贴图是「1 格 = 整张图」，即 **1 模型 px = 4 贴图 px**。四个区域：

| 区域（像素） | 用处 |
| --- | --- |
| `u 0..64, v 0..60` | 托架内侧面 / 端面主体（模型 `east [0,0,16,15]`、BER 端面 `0,0‥1,0.9375`） |
| `v 60..62`（`0.9375..0.96875`） | 下唇横带（模型 `east [0,15,16,15.5]`） |
| `v 62..64`（`0.96875..1`） | 圆弧底面 |
| `8..12 × 8..12` | 破坏粒子（模型 `particle`，`uv [2,2,3,3]`） |

### 2.3 相关静态贴图清单（含像素尺寸）

| 文件 | 尺寸 | 用途 |
| --- | --- | --- |
| `mtr:textures/block/psd_top.png` | **64×64** | 吊板托架/端面外壳（模型 + BER 端头补片共用） |
| `mtr:textures/block/psd_top_edge.png` | **64×64** | 同上，`_edge` 变体（相邻没有同类块时的收口） |
| `mtr:textures/block/light_off.png` | 16×16 | 端头小灯（灭） |
| `mtr:textures/block/light_on.png` | 16×16 | 端头小灯（亮，当前未被引用） |
| `mtr:textures/block/sign/arrow.png` | **64×64** | 方向箭头图案（动态贴图里绘制） |
| `mtr:textures/block/sign/circle.png` | **64×64** | 线路圆标（动态贴图里绘制） |
| `mtr:textures/block/sign/logo.png` | 128×128 | 站名入口的 MTR 标（吊板不用） |
| `mtr:font/noto-sans-semibold.ttf` | — | 拉丁/数字字体 |
| `mtr:font/noto-serif-cjk-tc-semibold.ttf` | — | CJK 字体（**注意是繁体 Noto Serif CJK TC**，简体字靠字体回退） |
| （对照，别混）`psd_door_top_left_1.png` | 72×36 | 门扇**上段门叶**，不是吊板 |
| （对照，别混）`psd_door_top_left_2.png` | 288×144 | 门扇上段高清变体 |

---

## 3. 动态内容从哪来

### 3.1 站台定位：`PERSISTENT` 只决定「画哪种」，不提供数据

```java
// BlockPSDTop$EnumPersistent: NONE / ARROW / ROUTE / BLANK
ARROW  -> RenderType.STATION_NAME（大站名，门上方那块）
ROUTE  -> RenderType.ROUTE（线路图，玻璃上方那块）
BLANK  -> NONE（空白，纯外壳）
NONE   -> 按下方方块自动：门 → STATION_NAME；玻璃/端部 → ROUTE；端部收口 → NONE
```

刷子右键在 `NONE` 与上述之间切换，并沿整排 `propagate` 一次（`BlockPSDTop#use`）。
`STYLE` 只是「画什么」的开关，**数据全靠位置查**：

```java
// BlockPSDTop$TileEntityRouteBase#getPlatformId
cachedPlatformId = RailwayData.getClosePlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE, this.getPos());
// 结果按 DataCache#needsRefresh(cachedRefreshTime) 缓存，不是每帧查
```

也就是说：**吊板不存任何线路/站名 NBT**，它只是「离我最近的那个站台」的一台显示器。

### 3.2 显示模式（`SavedRailBase.psdDisplayMode`，0/1/2）

```java
// RenderPSDTop#getRenderType + isDisplayMode2
mode 0：只画 ROUTE / ARROW
mode 1：允许 STATION_NAME（getStationNameForRendering 返回站名）
mode 2：按 x/z 的 (coord mod 8 < 4) 异或，**逐块交替** ARROW 与 ROUTE —— 一条吊板带上一块箭头一块线路图
```

`mode 2` 的交替判据是 `Math.floorMod(pos.getX(), 8) < 4 == Math.floorMod(pos.getZ(), 8) < 4`，
所以相邻块必然不同、隔 4 格重复一次。

### 3.3 三种面板内容的生成（`mtr.client.RouteMapGenerator`）

| 内容 | 生成方法 | 尺寸 | 细节 |
| --- | --- | --- | --- |
| 线路图 | `generateRouteMap(platformId, vertical=false, flip, aspect, transparentWhite)` | 高 = `rawHeight × scale`，宽 = 高 × aspect | 站点阶梯：线 + 站点圆点 + 站名（在线上/下交替）+ 换乘线路色块与换乘站名。**当前站**：黑底白字；**已过站**：灰 `0xFFAAAAAA`；未到站：黑字。白底 → `clearColor(-1)` 抠透明 |
| 站名 | `generateStationName(stationName, aspect)` | 高 = `2 × scale`，宽 = 高 × aspect | 单行居中，`fontSizeBig×2` / `fontSizeSmall×2`，四周留 `scale/16` 内边距 |
| 方向箭头 | `generateDirectionArrow(platformId, hasLeft, hasRight, CENTER, showToString=true, paddingScale=0.25, aspect, bg=-1, fg=-16777216, transparent)` | 高 = `scale` | 左侧箭头 + 「往 XX」+ 左侧线路圆标（`circle.png`，多线路则纵向分色）+ 圆内站台号（`platform.name`） |
| 色带 | `generateColorStrip(platformId)` | **1 × N 像素** | 每像素一条线路色，整条贴图横向拉伸成 0.5px 高的线；N>1 时是渐变 |

`scale` 来自 `Config.dynamicTextureResolution()`（**默认 2**）：

```java
scale      = 2^(resolution + 5)     // 默认 2 → 128
lineSize   = scale / 8              // 16
lineSpacing= lineSize * 3 / 2       // 24
fontSizeBig= lineSize * 2           // 32
fontSizeSmall = fontSizeBig / 2     // 16
```

即 4 格宽的吊板带：站名贴图 ≈ **2331×256**，线路图 ≈ `width × (≈3×128)`。分辨率调到 3 就整体 ×2。

### 3.4 文本、颜色、滚动

- **字体**：`ClientCache` 里 `Font.createFont(mtr:font/noto-sans-semibold.ttf)` + `noto-serif-cjk-tc-semibold.ttf`；
  逐字符 `canDisplay` 选择，都不行就遍历系统字体兜底（`ClientCache#getTextPixels`）。
  文本先渲染成**白色像素缓冲**，再当遮罩上色 —— 所以换字体/换色都不需要新图。
- **颜色**：`0xFF000000 | route.color` 直接来自线路数据；站名文本固定 `-16777216`（黑）。
  面板四边形的顶点色是 `RenderRouteBase.getShadingColor(facing, -1)`：朝东西向时 ×0.75 压暗。
- **滚动**：**吊板不滚动**。全宽贴图一次画完（`RouteMapGenerator.scrollTextLightRail` 只给轻轨列车用）。
  站名超长时是**缩字号**，不是滚动。

---

## 4. 天津地铁（`ziyue.tjmetro`）的差异

### 4.1 类名与关键方法

| 天津类 | 继承/关系 | 关键方法 |
| --- | --- | --- |
| `ziyue.tjmetro.block.BlockPSDTopTianjin` | **extends `mtr.block.BlockPSDTop`** | 新增属性 `STYLE : EnumPSDType`（`default` / `station_name` / `next_station`）；`use` 里用**扳手**（不是刷子）逐块 `cycleBlockState(STYLE)`；`getActualState(ServerWorld,…)` 重写 |
| `BlockPSDTopTianjin$TileEntityPSDTopTianjin` | extends `BlockPSDTop$TileEntityRouteBase` | 只多一个 `final boolean jinjing` |
| `ziyue.tjmetro.render.RenderPSDTopTianjin` | extends **`ziyue.tjmetro.render.RenderRouteBase`**（自己那份） | **整个 `render()` 重写**（因为 MTR 的是 final）；`getRenderType` / `renderAdditional` / `renderAdditionalUnmodified` / `getAdditionalOffset` 全部重写 |
| `ziyue.tjmetro.client.ClientCache` + `RouteMapGenerator` | 自建（不 extends） | `getStationName(platformId, isAPG, …)`、`getNextStation(…, arrowDirection, …)`、`getRouteMapPSDBMT` / `getRouteMapJinjing` / `getRouteMapTRT` |
| `BlockPSDTopTianjinBMT` + `RenderPSDTopTianjinBMT` | 同一套，BMT 风格 | — |

### 4.2 具体改了四处

1. **去掉了 `STATION_NAME` 这个 RenderType**。MTR 的 `getRenderType` 在「门上方」返回
   `STATION_NAME`（整块大站名），天津一律返回 `ARROW`：

   ```java
   if (blockBelow instanceof BlockPSDAPGDoorBase) return RenderType.ARROW;   // MTR: STATION_NAME
   ```

   大站名改由 `STYLE == STATION_NAME` 时走 `ClientCache.getStationName(...)`（自己画，白底黑字）。

2. **新增「下一站」面板**：`STYLE == NEXT_STATION` 时调 `getNextStation(...)` / `getNextStationJinjing(...)`，
   画法是「箭头 + 往 XX」在左、「下一站 ●○○」在右（`RouteMapGenerator#generatePSDNextStation`，
   右侧那块用 `gui.tjmetro.next_station` 标签 + 后续所有站名合并）。

3. **面板绘制条件砍掉了 `STATION_NAME`**：

   ```java
   if ((renderType == ARROW || renderType == ROUTE) && SIDE_EXTENDED != SINGLE) { … }
   ```

4. **贴图换命名空间**：`assets/tjmetro/blockstates/psd_top_tianjin{,_bmt,_jinjing}.json` →
   `models/block/psd_tianjin_top_*` → `tjmetro:block/psd_tianjin_top.png`。
   但 **`renderAdditionalUnmodified` 里那张端头补片仍写死 `mtr:textures/block/psd_top.png`**
   （`RenderPSDTopTianjin.java:115`）—— 他们只换掉了模型那部分，BER 补片还在用 MTR 的原图。
   我们做自己的样式时不要复制这个瑕疵。

| 天津静态贴图 | 尺寸 |
| --- | --- |
| `tjmetro:textures/block/psd_tianjin_top.png` | 64×64 |
| `tjmetro:textures/block/psd_tianjin_top_edge.png` | 64×64 |
| `tjmetro:textures/block/psd_tianjin_bmt_top.png` / `_top_edge.png` | 64×64 |
| `tjmetro:textures/block/psd_tianjin_red_top.png` | 16×16 |
| `tjmetro:font/dengxian.ttf` | 天津用**等线**替代 Noto Sans（CJK 仍回退 MTR 的 Noto Serif TC） |

### 4.3 结论：天津走的是「能继承的继承、不能继承的整份抄」

`Block` / `BE` 继承（列车开门逻辑靠 `instanceof BlockPSDAPGDoorBase`，抄了就永不开门），
**渲染器整份复制**（因为 `RenderRouteBase#render` 是 final，且面板内容集不同）。
我们若要厦门样式，先问一句：**只是换外观（贴图 + 字体 + 颜色），还是要换版式（多/少元素）？**
只换外观 → 继承 `RenderPSDTop` 即可，一行 mixin 都不用；换版式 → 才需要天津那种复制。

---

## 5. 可替换点

### 5.1 静态贴图（可直接用实拍/重绘替换，零代码）

| 可替换物 | 像素尺寸 | 换法 | 影响面 |
| --- | --- | --- | --- |
| `mtr:textures/block/psd_top.png` | 64×64 | 资源包同名覆盖 | **⚠ 全局**：所有 MTR 吊板 + 天津吊板的端头补片都会变（天津 BER 也读它） |
| `mtr:textures/block/psd_top_edge.png` | 64×64 | 资源包同名覆盖 | 同上，仅整排两端 |
| `mtr:textures/block/light_off.png` | 16×16 | 资源包同名覆盖 | 端头小灯 |
| `mtr:textures/block/sign/arrow.png` | 64×64 | 资源包同名覆盖 | **只影响动态贴图里的箭头图案**（箭头本身是程序贴上去的） |
| `mtr:textures/block/sign/circle.png` | 64×64 | 资源包同名覆盖 | 线路圆标底图（底色由程序按线路色填） |
| `mtr:font/*.ttf` | — | 资源包同名覆盖 | 全局字体 |

> 想**只改自己的吊板**，就不能覆盖 `mtr:`。本项目已有 `mtrlte:` 通道（`xiamen/XiamenTextures`），
> 但它目前只重写了 `RenderPSDAPGDoor` 里的 `String.format` 调用；`RenderPSDTop` 的贴图 id 是
> **`new Identifier("mtr:textures/block/psd_top.png")` 直接常量**（没有 format），**不会被现有 mixin 命中**。
> 自己写 `RenderXiamenPSDTop` 时，`renderAdditionalUnmodified` 不要调 `super`，直接用自己的 `mtrlte:` 贴图即可 ——
> 它是 `protected` 且非 final，这是最干净的口子。

### 5.2 程序画的部分（必须保留逻辑，只能改颜色/字体/尺寸）

| 元素 | 代码位置 | 能改的 | 不能改的（除非复制/ASM） |
| --- | --- | --- | --- |
| 面板矩形 | `RenderRouteBase#render`（**final**） | 宽高由**方块数 + 构造函数四个 padding** 决定：`super(dispatcher, z=1.95, top=7.5, bottom=1.5, side=0.125, transparentWhite=true, ARROW_DIRECTION)` | 面板的 7px 高度、1.95px 进深、逐块 UV 切片 |
| 面板内容 | `RouteMapGenerator.generate*` | 背景色（`-1` 白 / `transparentWhite`）、文字色、字号、`paddingScale`、`aspect` | 站点阶梯的排布算法 |
| 色带 | `RenderPSDTop#renderAdditional` | 颜色（来自线路数据）、y 区间（0.90625..0.9375）、是否画 | 1×N 拉伸方式 |
| 端头补片 | `RenderPSDTop#renderAdditionalUnmodified` | 整段可覆写（几何 + 贴图） | 原版 8 个四边形的坐标 |
| 字体 | `ClientCache#font / fontCjk` | 换 ttf（天津换成等线就是这么做的） | 逐字符回退链 |
| 面板触发条件 | `getRenderType` | 门上方画站名还是箭头、`mode 2` 交替 | — |
| 站名文本 | `getStationNameForRendering` | 返回什么字符串（可加站名后缀、可返回 null 退化成箭头） | — |

### 5.3 一句话清单：厦门样式要动的东西

```
静态（可实拍/重绘）：psd_top.png 64×64、psd_top_edge.png 64×64、light_off.png 16×16
                     （+ 想改箭头/圆标：sign/arrow.png、sign/circle.png，各 64×64）
程序（保留逻辑）：   线路图阶梯、站名排版、方向箭头、色带 —— 只改字体/颜色/padding
要新增（可自由画）： 面板**背景**四边形（原版没有这一层），见 §6
```

---

## 6. 实拍图 → 贴图：可行性方案

### 6.1 拍什么（按优先序）

| # | 目标 | 拍法 | 落点 |
| --- | --- | --- | --- |
| 1 | **整排吊板带正面** | 正对、镜头与吊板同高、尽量远 + 中长焦（等效 50–85mm），画面横向铺满整排 | 面板背景 / 整体配色参考 |
| 2 | **吊板与门叶的交接带**（上方 20–30cm） | 同 1，稍微下压 5° | 上唇、门叶与吊板的关系 |
| 3 | **端头侧封面**（整排最边上一块） | 从线路方向 45° 侧拍，能看到端面 + 圆弧下沿 | `psd_top_edge.png` 的端面/圆弧 |
| 4 | **下沿与底面** | 仰拍 30–45°，拍圆弧过渡与底部薄唇 | 底唇 1px 带、圆弧 UV 区 |
| 5 | **面板特写**（若面板有丝印/贴膜/老化差异） | 正对，1m 内 | 面板底纹 |
| 6 | **吊架/连接件** | 侧上方 | 可选（模型改动量大，本版不做） |

**不要**指望一张图拍全：模型把 16px 的高度切成「上唇 7.5px + 面板 7px + 下唇 1.5px」三段，
端面又是另一套 UV，所以至少要「正面 + 端面 + 底面」三张。

### 6.2 分辨率

以「1 模型 px = 16–32 贴图 px」为目标（原版是 4 px，实拍风格建议至少 16）：

| 部位 | 模型尺寸 | 建议成品 |
| --- | --- | --- |
| `psd_top.png` 整张（托架+端面共用） | 16×16 模型单位 | **512×512**（= 32px/模型px）；内存紧就 256×256 |
| 面板背景（4 格宽 = 64×7 模型 px） | 64×7 | **2048×224**（宽 32px/模型px）；整排 4 格够用，6 格做 3072 |
| 端面 | 8×15 模型单位 | 256×480 |

手机 4000×3000 的原始照片「够」，但**必须先把透视矫正成正视图**再降采样，否则 1px 的底唇会糊。

### 6.3 关键坑：图层顺序不能靠调用顺序

MTR 的延迟绘制队列是 **`Map<Identifier, Set<consumer>>`**，按**贴图**分组：

```java
// RenderTrains#scheduleRender
Map<Identifier, Set<BiConsumer>> map = RENDERS.get(priority ? 1 : 0).get(layer.ordinal());
map.computeIfAbsent(id, …).add(callback);
```

刷新时 `forEach` 遍历这个 map（HashMap 顺序）—— 所以「先调 scheduleRender 的先画」**不成立**。
但有两条确定性规则可用：

```java
// 1) priority：false(0) 整批先画，true(1) 整批后画   ← RenderTrains.render 的 i 循环
// 2) 层：按枚举声明序 LIGHT(0) → LIGHT_TRANSLUCENT(1) → INTERIOR(2) → EXTERIOR(3)
```

面板走 `EXTERIOR` + `priority=false`。所以**背景四边形只要排在 `LIGHT`/`LIGHT_TRANSLUCENT`/`INTERIOR` 任一层，
就必然在面板之前画出**，与贴图分组的顺序无关。推荐：

```java
// 背景：LIGHT 层（顺带得到「灯箱不打光」的效果）
RenderTrains.scheduleRender(bgTexture, false, QueuedRenderLayer.LIGHT, (m, vc) -> {
    storedMatrixTransformations.transform(m);
    IDrawing.drawTexture(m, vc,
        x1, 1.5f/16, Z_BG,  x2, 8.5f/16, Z_BG,   // Z_BG = 1.95f/16 + 0.001f（沿 facing 推到面板后面）
        0f, 0f, 1f, 1f, facing.getOpposite(), 0xFFFFFFFF, light);
});
```

其中 `x1/x2` 要按 `leftBlocks/rightBlocks` 算（同 §1.2 的宽度公式），`storedMatrixTransformations` 用
`renderAdditionalUnmodified` 拿到的那份（它的坐标系就是「方块中心为原点、y=0 为底面」——
MTR 自己画端头补片用的也是这套，见那些 `-0.125f … 0.5f` 的坐标）。
**z 的符号必须实机看一次**：面板法线是 `facing.getOpposite()`，所以背景要往 `+facing` 方向退 0.001 格；
推反了会被面板的深度测试切掉（表现为背景整片消失，不是紫黑格）。

### 6.4 为什么「背景层」这条路能走通

- 面板动态贴图是 `transparentWhite = true` → 生成完 `clearColor(-1)` 把**纯白抠成 alpha=0**
  （`RouteMapGenerator#clearColor` 只动生成的位图，不碰你的实拍图）。
- 所以面板上无内容的区域本来就是透明的，背景会**自然透出来**：实拍图当底色，站名/线路/圆点/色带照旧由程序画在上面。
- 反过来，如果直接拿实拍图当**面板贴图**（改 `generateRouteMap` 或换掉那张动态贴图），
  就必须自己重画站点阶梯 —— 那是 §5.2 里「不能改」的那一块，性价比极低。

### 6.5 落地顺序建议（本版发布前不做，留给下一版）

1. 先把 `psd_top.png` / `psd_top_edge.png` 按实拍重绘成 512×512（纯静态，零风险，先拿到 90% 的观感提升）。
2. 再写 `RenderXiamenPSDTop extends RenderPSDTop`，只覆写 `renderAdditionalUnmodified`
   （自己的 `mtrlte:` 外壳贴图 + §6.3 的背景四边形），注册点就在 `XiamenClient` 第 2 步
   （现在那里是 `new RenderPSDTop(dispatcher)`）。
3. 想改版式（去掉箭头/换站名写法）再考虑复制 `RenderRouteBase` —— 那一步的维护成本等于天津那套。

---

## 7. 待实机确认（本文未验证项）

| 项 | 怎么验 |
| --- | --- |
| 背景四边形的 z 偏移方向（`+facing` 还是 `−facing`） | 放一块吊板，把背景涂成纯红，看是否整片消失 |
| `LIGHT` 层的实际混合模式（是否吃 alpha） | 用半透明 PNG，看是否透出背面方格 |
| 1.5px/8.5px 这两个边界在视觉上的落点 | 画一条 0.5px 的红线贴在 y=1.5/16 与 8.5/16，对比实机截图 |
| `mode 2` 交替在斜向门带上的表现 | 摆一段 L 形吊板带，看交替是否错位 |

以上四条都只影响「贴图怎么裁」，不影响 §1–§5 的任何结论（那些全部来自字节码常量与资源文件）。
