# 线路条带（厦门实拍版式）与门灯颜色 — 2026-11-10

产物：`v1.20.1/build/libs/lte-1.0.25.jar`（1,193,391 B，`md5 a9e24c890658d917e02708bdd9df531c`，
`BUILD SUCCESSFUL`）→ 桌面 `lte-1.0.25.jar` + 实例
`PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.25.jar`（三份 md5 一致，旧的 1.0.24 已删）。

离线版式预览（不开游戏）：`D:\MC-MTR-JS\temp\strip-preview\01..05-*.png`
（`XiamenStripPainter` 刻意不 import 任何 MC/MTR 类型，可以 `javac` 单独编译 + 直接吐 PNG）。

---

## 1. 为什么是“拦位图”而不是“另写渲染器”

```
RenderRouteBase#render                                  ← final，改不了
  ClientData.DATA_CACHE.getRouteMap(platformId, vertical, flip, aspectRatio, transparentWhite)
    ClientCache#getResource(key, supplier, defaultColour) ← 缓存键 / 纹理注册 / 图片尺寸
      RouteMapGenerator.generateRouteMap(...)             ← 位图内容：RouteMapArtMixin 在这里换掉
  IDrawing.drawTexture(...)                              ← 面板几何 / UV / 着色
RenderPSDTop#renderAdditional → getColorStrip(platformId) ← 下沿 1px 色带
```

面板几何、UV、色带、方向箭头、纹理缓存全部还是 MTR 的代码；我们只换“画什么”。
画不出来（纵向线路图 / 环线 / 数据没到 / 配置关掉 / 任何异常）→ 返回 null → 不取消注入 →
MTR 画它自己那张，不会出现紫黑格。

## 2. 四个钩子与 `transparentWhite`

| 钩子（`RenderRouteBase` 的 API） | 谁实现 | 我们是否动 |
|---|---|---|
| `final render(T, float, MatrixStack, VertexConsumerProvider, int, int)` | 基类，**final** | 不能覆盖，整段沿用 |
| `getRenderType(world, pos, state)` | `RenderPSDTop` / `RenderAPGGlass` | 不动（决定这块面板画 ROUTE / 站名 / 箭头 / NONE） |
| `renderAdditional(...)` | `RenderPSDTop` | 不动（画 `getColorStrip` 那条 1px 下沿色带；只有配置了 `lineColors` 才由我们重画） |
| `renderAdditionalUnmodified(...)` | `RenderPSDTop` | 不动（柜体 / 斜角那块 `psd_top.png`） |
| `getAdditionalOffset(state)` / `getStationNameForRendering(platformId)` | 各子类 | 不动 |

`transparentWhite`（构造器第 6 个参数）：**吊板 `RenderPSDTop` = true，半高 APG 玻璃 `RenderAPGGlass` = false**。
它的语义只是“这张图的白色要不要在最后被 `clearColor(-1)` 变成透明”：
- `true`（吊板）：图片里的白 = 透明，面板下面的 `psd_top.png` 深色板透出来；
- `false`（APG 玻璃）：白就是白，整张不透明。

我们的画法**两端都成立**：白底长条只画在图片中间 60% 的高度上，上下本来就是透明像素，
所以吊板上下露出自己的板、APG 玻璃上下露出玻璃 —— **没有换背景**，也不依赖 `transparentWhite`。

## 3. LCD 参数 → Java 实现值（`XiamenStripPainter`）

参照 `live-pack/assets/mtr/xmlcd/draw.js` 的 `drawFullLineMap` / `drawStationMarker` /
`drawSimpleLineBox`。**bandH = 白底条带高度**（= 图片高 320 × 0.60 = 192 px）。

| LCD（`draw.js`） | Java（`XiamenStripPainter`） | 典型值（aspect 8.5，2720×320） |
|---|---|---|
| `lcdH(0.05)` 线路带高 | `lineW = bandH * 0.09`（`LINE_RATIO`） | **17 px**（条带高的 8.9%） |
| 圆点 `min(lcdH(0.125), step*0.75)`，下限 `lcdH(0.03)` | `circle = max(bandH*0.09, min(bandH*0.22, step*0.75))`（`CIRCLE_RATIO`） | **42 px** 直径（= 2.5× 线宽） |
| 当前/下一站橙底 `#F5A623` | 当前站 = **加大的空心圆 + 中心实心**，`circle * 1.35`（`CURRENT_SCALE`） | **57 px** 外径，中心实心 24 px |
| 圆环描边 `size*0.15`、内环 `0.58*size` | 同（`RING_RATIO` / `INNER_RATIO`） | 描边 **6.3 px**（当前站 7.2 px），内环 **24 px** |
| 站名 `min(lcdH(0.125), step*1.35/字数, lcdH(0.34)/字数)` | 按**实测字宽**反解：`size = min(100*step*0.92/(字宽*0.707), 100*可用竖直空间/(字宽*0.707), bandH*0.125)`（`NAME_RATIO`），下限 `NAME_FLOOR = 8 px` | **35 px**（12 站）/ **28 px**（24 站）/ 26 px（正排） |
| 站名 45° 斜排在线条上方（`rotate(1.75π)`） | 同；`routeStripNamesBelow = true` 时改成线下正排 | 基线 = 圆点上沿 - 4 px |
| 已过站灰 `#B2B2B2` / 区间灰 `#A7A8A8` | **不用灰**：实拍吊板整条线都是线路色（已过站/未到站同色） | — |
| 区间方向小箭头 `xmlcdChevronRow` | 不画（吊板只要终点箭头） | — |
| `drawSimpleLineBox` 圆角色块 | 左侧线路号色块：`side = bandH*0.46`，圆角 0.22×side，数字 `0.62×side` | **88 px**，数字 55 px |
| — | 终点箭头：`arrowLen = 2.4*lineW`，`arrowHalf = 1.5*lineW` | 长 **41 px**，半高 **26 px** |
| — | 白底条带：图片中间 `BAND_RATIO = 0.60` 高度，整宽 | 192 px / 320 px |
| — | 线条竖直位置：斜排时 `bandTop + 0.68*bandH`，正排时 `0.34` | 195 / 129 |

颜色（**全部跟线路走，不写死**）：
- 主线 / 圆环 / 色块 / 终点箭头 = 线路色（`Route#color`）；
- 白底上的普通站名 `#1A1A1A`、当前站名 = 线路色加深 0.88（浅色线路退回 `#1A1A1A`）、正排模式非当前站 `#9E9E9E`；
- 色块数字 / 任何画在线路色上的文字 = 按 `0.299R+0.587G+0.114B > 128` 判黑/白（与 LCD `lib/color_util.js#isLightColor` 同一阈值）；
- 浅色线路（黄/浅绿）的**圆环与当前站中心**改用 `darken(color, 0.72)`，白底上才看得见（主线仍用原色）；
- 线路没设色（`color == 0`）→ `#808080` fallback。

超长线路：字号一路缩到 8 px 就改成“隔一个画一个”（stride 2/3/4），再不行就不画站名 —— **不换行、不重叠**。
最后一站的斜排站名会顶到图片右沿时整体左移（方向不变，避免和前一站交叉）；正排时第一站的名字左边界卡在线路号色块右边。

## 4. 数据来源（与 MTR 自己那张图同一份数据）

| 需要 | 方法 / 字段 |
|---|---|
| 站台 → 线路列表、站序、当前站 | `ClientData.DATA_CACHE.requestPlatformIdToRoutes(platformId)` → `ClientCache.PlatformRouteDetails{routeName, routeColor, circularState, currentStationIndex, stationDetails[i].stationName}` |
| 线路对象（id / color / lightRailRouteNumber） | `ClientData.ROUTES` 里 `containsPlatformId(platformId)` 且短名相同的那一条 |
| 面板长宽比 | MTR 传进 `generateRouteMap` 的 `aspectRatio`（= 板宽/板高，吊板 4 格 ≈ 8.57） |
| 下沿色带 | `RouteMapGenerator.generateColorStrip(platformId)`（只在 `lineColors` 有覆盖时才接管） |
| 门所在线路 | `RailwayData.getClosePlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE, pos)` + `DataCache.needsRefresh(long)` 做缓存失效 |

注入点（`RouteMapArtMixin`，`@Mixin(RouteMapGenerator.class, remap = false)`）：
`generateRouteMap(long,boolean,boolean,float,boolean)` 与 `generateColorStrip(long)` 的 HEAD，`cancellable`。
编译产物已核对：handler 描述符 `(JZZFZLorg/.../CallbackInfoReturnable;)V`，泛型参数被 Loom 正确 remap 成
`net/minecraft/class_1011`（= 目标方法的返回类型）。

## 5. 门灯颜色

MTR jar 里的真实文件名与颜色（`javap` 生产 jar + 像素统计）：

| 文件 | 尺寸 | 颜色 | 用在哪 |
|---|---|---|---|
| `mtr:textures/block/light_on.png` | 16×16 | **#FF9900**（全像素不透明） | `door type 0/1`（PSD 门） |
| `mtr:textures/block/light_off.png` | 16×16 | **#993300** | 同上（关门） |
| `mtr:textures/block/apg_door_light_on.png` | 64×64 | **#FF9900**（不透明像素 19.6%，其余 alpha=0） | `door type 2`（APG 门） |
| `mtr:textures/block/apg_door_light_off.png` | 64×64 | **#993300** | 同上 |

`RenderPSDAPGDoor#render` 里的格式串就是 `"mtr:textures/block/light_%s.png"`（type 0/1）与
`"mtr:textures/block/apg_door_light_%s.png"`（type 2），`%s` = `on` / `off`。

模组里 `assets/mtrlte/textures/block/` 的 `light_on/off.png`、`apg_door_light_on/off.png`
与 MTR jar 里那四张 **md5 完全一致**（橙色），所以默认就是橙色、且优先于资源包（厦门包里的蓝色）。
新增同色变体（保留原图 alpha，只换 RGB）：

| 变体 | on | off |
|---|---|---|
| `*_orange`（= 基础图，不另存） | #FF9900 | #993300 |
| `*_green` | #00C853 | #00662A |
| `*_blue` | #0099FF | #004C80 |

选择链路：`RenderPsdDoor/RenderPsdApgDoor` → `XiamenDoorLights.colorFor(pos)`（按“门所在站台的线路号”
查 `doorLightByLine`，没有就 `doorLightColor`）→ `XiamenTextures.withNamespace(ns, color, ...)`
→ `RenderPsdDoorTextureMixin` 把 `String.format` 出来的 id 改成 `..._green.png` / `..._blue.png`。

## 6. 新增配置（`config/mtrlte.json`，版本 20）

```json
"xiamenRouteStrip": true,          // 关掉 = 回到 MTR 原版条带
"routeStripNamesBelow": false,     // true = 站名正排到线条下方（默认 45° 斜排在上方）
"lineColors": {},                  // 可选覆盖：{"1": "#009A44", "4": "#D6001C"}；默认空 = 用线路自己的 color
"doorLightColor": "orange",        // orange | green | blue
"doorLightByLine": {}              // 按线路：{"4": "green", "6": "green"}（预留 4/6 号线切绿）
```

## 7. 自检

- `BUILD SUCCESSFUL`（`-q`，只有既有 4 条警告：3 条 fangsu/ANTE soft-target + 1 条过时 API）；
- jar 内 `mtrlte.mtr.mixins.json` 已含 `RouteMapArtMixin`，`defaultRequire: 0` + config `required: false` 兜底；
- jar 内新增 6 个 class + 8 张门灯变体贴图；
- 版式用 `StripPreview` 离线渲染 5 个用例核对（红 12 站 / 绿 24 站 / 正排 / flip 左向 / 黄浅色），
  像素统计：白底行 64..255（192 px = 60%）、主线 17 px、圆点 42 px、当前站 57 px。
- 未启动游戏（按任务要求）。
