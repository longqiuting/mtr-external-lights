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

---

# 线路条带 v2（实拍纠正版）— 2026-11-10

产物：`v1.20.1/build/libs/lte-1.0.26.jar`（1,198,985 B，`md5 2d5a872701f90fbc93952ccc4193547d`，
`BUILD SUCCESSFUL`）→ 桌面 `lte-1.0.26.jar` + 实例
`PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.26.jar`（三份 md5 一致，旧的 1.0.25 已删）。

离线版式预览：`D:\MC-MTR-JS\temp\strip-preview\00-all.png`（8 条叠图）+ `01..08-*.png` 单张。
渲染器源码 `D:\MC-MTR-JS\temp\strip-preview2\src\dev\js\mtrheadlight\xiamen\StripPreview.java`
（只 `javac` 编译 `XiamenStripPainter`，不碰 MC/MTR，不用开游戏）。

## 7.1 三条纠正

| 用户要求 | 落地 |
|---|---|
| 普通站 = 空心圆环，白心、线路色描边、环宽 15–18% | `drawDot` 只画**一圈**环（`RING_RATIO = 0.15` × 直径），内环与实心点全部删除。直径 = `min(bandH*0.22, step*0.75)`，12 站时 42 px → 环宽 6.3 px = 15.0% |
| 终点 = 空心圆环 + 指向终点的实心箭头（长 2.4×线宽、宽 1.4×线宽） | 终点站仍是普通空心环；`drawArrow` 的箭头贴在线端、尖端朝行进方向：长 `round(lineW*2.4)` = **41 px**，半高 `round(lineW*0.7)` → 宽 **24 px** = 1.4×17（原来半高是 1.5×线宽，宽 3 倍，已改窄） |
| 换乘节点 = 追逐箭头图标 + 下方短箭头 + 换乘色小牌 | `drawInterchange`：线上白底圆内画**两段 120° 弧 + 两个箭头尖**（整圈换乘线路色），圆环下缘接一个**短向下箭头**指着下面的圆角小牌 |

当前站（`CURRENT_SCALE = 1.35`）也改成“更大的空心环 + 一圈细内环”，**不再有中心实心点**。

## 7.2 换乘数据的真实来源（没有猜）

MTR 3.6.4.2 的 `ClientCache$PlatformRouteDetails$StationDetails` 就有换乘信息：

```
public final class ClientCache$PlatformRouteDetails$StationDetails {
  public final String stationName;
  public final List<ColorNameTuple> interchangeRoutes;   // ← 就是它
}
public class ClientCache$ColorNameTuple { public final int color; public final String name; }
```

`ClientCache#requestPlatformIdToRoutes` 在构造每个 `StationDetails` 时，用
`lambda$requestPlatformIdToRoutes$5(Route, ColorNameTuple)` 过滤这个列表，**过滤条件只有一条**：
`tuple.color != route.color`（javap：`getfield ColorNameTuple.color` / `Route.color` / `if_icmpne`），
即“这个站上颜色不同于本线的其它线路”。MTR 自己画换乘圆环用的就是这一份，所以我们直接用。

取值优先级（`XiamenRouteStrip#interchanges`）：

1. `LineConfig.lineColors` 里对该换乘线路的手工覆盖（键 = 线路 id / 全名 / 线路号）；
2. `ColorNameTuple.color`（MTR 缓存里已经算好的线路色）；
3. 按 `tuple.name` 到 `ClientData.ROUTES` 找 `Route#color`；
4. 都没有 → `#808080`。

**兜底**（`interchangeRoutes` 为空，或唯一的换乘线正好同色被 MTR 过滤掉）：按站名匹配
`ClientData.STATIONS` 拿到 `station.id`，再读 `DATA_CACHE.stationIdToRoutes`
（`Map<stationId, Map<index, ColorNameTuple>>`，同样是 MTR 缓存里的派生表），跳过本站自己的线路，取第一条。

两条路都拿不到 → 该站**不画换乘标记**（退回普通空心环），不瞎猜。
日志（每“车站+线路”一行，`INTERCHANGE_LOGGED` 去重）：

```
[lte] strip: interchange station=厦门北站 -> line=1号线 color=#009A44
[lte] route strip: platform=… route=4号线 id=… color=#D6001C stations=12 tex=2720x320
                   band=192 line=17 dot=42 current=57 swap=1 ring=42 plate=124 font=Noto Sans SC nameSize=35
```

线路号取 `XiamenRouteStyle#lineNumber(tuple.name)`（线路名里第一串数字：`1号线|Line 1` → `1`）；
拿不到数字时小牌只写线路中文名。

## 7.3 换乘标记尺寸与颜色（bandH = 192 px，2720×320 纹理）

| 元素 | 常量 | 实测 |
|---|---|---|
| 追逐箭头圆环直径 | `IX_RING_RATIO = 0.22` × bandH | **42 px**（和普通站环一样大，不会被当成更小的点） |
| 环内弧半径 / 描边 | `0.44 × d` / `0.145 × d` | 半径 18.5 px、描边 6.1 px、每段 **120°**，两段之间留 60° 缺口 |
| 箭头尖 | `0.40 × d` 三角 | 16.8 px |
| 环下短箭头杆宽 | `IX_ARROW_RATIO = 0.038` × bandH | **7 px**，箭头头 20×7.7 px |
| 圆环↔小牌间距 | `IX_PLATE_GAP_RATIO = 0.052` | **10 px**（短箭头就画在这段里） |
| 小牌高度 | `IX_PLATE_H_RATIO = 0.145` | **28 px**，圆角 0.46×高 |
| 小牌左右内边距 | `0.26 × 牌高` | 7 px |
| 小牌最大宽度 | `min(0.62×图宽, 1.55×站距)` | 12 站 ≈ 124 px；24 站被压到 ~163 px 上限内 |

颜色：
- 追逐箭头环（弧 + 两个箭头尖）、短箭头、小牌底 = **换乘线路色**；
- 环内的圆心是**白色**（画在线上也是空心，不会和主线混在一起）；
- 小牌文字 = 按 `0.299R+0.587G+0.114B > 128` 判黑 / 白（与 LCD `isLightColor` 同一阈值），
  深色线路（1 绿 / 4 红 / 2 蓝 / 8 品红）→ 白字；
- 主线 / 普通环 / 终点箭头 / 线路号色块 = 当前线路色（浅色线路的环用 `darken(0.72)` 才看得见）。

文案优先级：`1号线 / Line 1` → `1号线 Line 1` → `1号线` → `Line 1` → `1`，
但**宁可少写几个字也要字号够大**：只有更短的写法明显更大（> 2%）时才替换。
24 站密集线路里三块牌子都靠这条规则避免了互相挤压。

`routeStripNamesBelow = true`（站名在线下）时，小牌与短箭头**整体镜像到线上**，否则会和站名叠在一起
（离线预览 03 复现过，已修）。

## 7.4 站台号码

- 我们这个条带**从来没有画过站台号码**：左侧那个圆角色块是**线路号**（`4`），按要求保留；
- MTR 原版 route map 里的那个站台号角标是 `RouteMapGenerator.generateRouteMap` 用
  `ClientData.DATA_CACHE.platformIdMap.get(platformId).name` 画在线路起点的（javap 定位到
  `generateRouteMap` 中 `int[] dimensionsPlatformNumber` 那一段），我们整张图替换掉，它自然不存在了；
- 离线预览 8 张图里除线路号色块外没有任何数字角标（可逐张核对）。

## 7.5 门上的“当前站名居中”（未做，附定位结论）

- MTR 的**站名面板**（`RenderType.STATION_NAME`，吊板正下方是门时走这条）用的是
  `ClientData.DATA_CACHE.getStationName(station.name, aspect)` → `RouteMapGenerator.generateStationName`，
  源码里已经是 `getTextPixels(..., HorizontalAlignment.CENTER)` + `drawString(..., width/2, ..., CENTER, CENTER)`，
  **本来就居中**，没有给站台号留位的空隙；
- 真正“左边一个站台号、右边文字靠左”的是**方向箭头面板**（`RenderType.ARROW` →
  `RouteMapGenerator.generateDirectionArrow(platformId, hasLeft, hasRight, CENTER, showToString=true, 0.25, aspect, …)`）：
  它在左侧画线路色圆（`CIRCLE_RESOURCE`），圆里写 `platform.name`（站台号），
  “往 <站名 | English>” 从圆的右边**左对齐**开始（`HorizontalAlignment.LEFT` + `leftSize` 偏移）。
  要去掉站台号并把文字居中，得整块重画这张位图（`drawString` / `drawResource` 都是 private），
  属于独立改动，这次没有动 —— 需要的话按这个入口做。



---

# 线路条带 v3（实拍纠正 · 换乘分色 / 满高 / 黑体 / 吊板去站台号）— 2026-11-10

产物：`v1.20.1/build/libs/lte-1.0.26.jar`（1,206,835 B，`md5 e61111b4553f739e15372b7c2ca561c4`，
`BUILD SUCCESSFUL`）→ 桌面 `lte-1.0.26.jar` + 实例
`PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.26.jar`（三份 md5 一致，覆盖同名旧 jar）。
**没提交 git，没启动游戏。**

离线预览：`D:\MC-MTR-JS\temp\strip-preview\{00-all,01..09-*,10..13-direction-*}.png`
渲染器 `D:\MC-MTR-JS\temp\strip-preview2\src\dev\js\mtrheadlight\xiamen\{StripPreview,XiamenStripPainter,XiamenDirectionPanel}.java`
（`javac` 只编这三个纯 AWT 类，不碰 MC/MTR）。

## 7 条逐项落地

| # | 要求 | 落地 | 参数（bandH = 320） |
|---|---|---|---|
| 1 | 换乘图标分色、连成一体 | `drawInterchange`：一个环按“上半 / 下半”两段画，**共圆心、半径、厚度**，只是颜色不同 —— 上段 = 当前线路色（`20°→150°` + 尖头），下段 = 换乘线路色（`175°→340°` + 尖头），下段再伸出同色锥形垂尾到线路牌。白心圆先铺，环在线上也保持空心 | `IX_RING_RATIO 0.26`→**83 px**、`IX_RING_THICK 0.20`→**17 px**、尖头 `1.55×/1.85×` 环厚、垂尾根宽 `0.50×` 环径 |
| 2 | 去灰底 | 灰底不是画上去的：v2 的白条只占 60%，上下 40% 是**透明像素**，吊板后面那块深色面板透出来。v3 白底直接铺满整张图（`BAND_RATIO 0.60 → 1.00`），面板可用高度里没有一处透明 | `BAND_RATIO = 1.00` |
| 3 | 终点三角箭头位置 | `drawTerminalArrow` 重写：**不再画在线端外侧**，改为画在终点圆环**内侧、靠线路那一侧**，尖端顶在圆环外缘上（`tip = cx ∓ termR`），三角沿线路方向往后展 3×线宽 | 长 `3.0×lineW`=**81 px**、半高 `1.0×lineW`=**27 px**、终点环 `TERMINAL_SCALE 1.60`=**98 px** |
| 4 | 终点 + 换乘 → 牌子跑侧边 | 牌子位置改成**永远 `px = cx - plateW/2`**，绝不为了塞进画面而左右平移；改夹“牌宽”（`min(2×(到最近图边距离), 1.55×站距)`），放不下就缩字。顺带修了 flip 时名字没镜像的错位（v2 只镜像了当前站/换乘牌） | `IX_PLATE_H_RATIO 0.155`→**50 px**，`plateW` 实测 **82 px**，牌中文 23 px / 英文 14 px |
| 5 | 高度对齐 MTR | 见 #2：条带铺满 = MTR `generateRouteMap` 先 `fillRect(全部, -1)` 的做法。APG 玻璃与 PSD 吊板走的是同一条 `RenderRouteBase → getRouteMap`，一处改两个面板都变；主线宽也改成 MTR 的 `lineSize = 图高/8` 量级 | `LINE_RATIO 0.085`→**27 px**、`CIRCLE_RATIO 0.19`→**61 px** |
| 6 | 字体换黑体 | `resolveFont` 换成按 **PS 名 / 英文族名**打分：`SimHei` > `Microsoft YaHei` > Noto/Source Han；全部 `Font.BOLD`。（v2 用 `getAvailableFontFamilyNames()` 按名字匹配，中文 Windows 上 SimHei 的族名是“黑体”，所以永远匹配不到，落到 Noto Sans 细体） | 实测 `font=SimHei`，站名 **45 px**（`NAME_RATIO 0.14` 上限） |
| 7 | 吊板面板去站台号 + 居中 | 现在改的是 **`RenderType.ARROW` / `STATION_NAME`（站名为空）→ `RouteMapGenerator#generateDirectionArrow`** 那张位图（吊板正下方是门/玻璃时的面板）。新增 `RouteMapArtMixin#lte$directionArrow` 在 HEAD 拦下，用 `XiamenDirectionPanel` 整块重画：**不画站台号圆**，目的地中英文两行**水平居中**，两端画方向箭头 | 纹理 **256 px** 高、`cjkSize = 0.46×h`（4 格宽面板实测 118 px）、英文 `0.42×` 中文 |

## 7.1 站名（顺手对齐实拍）

- 45° 斜排，**中文 + 英文两行**（英文贴在中文行下方，字号 `0.46×`）；英文放不下（`< 0.30×`）时整条线不写英文，避免小得看不见；
- 字号按实测字宽反解：`0.92 × 站距` 的 45° 投影 + 顶部可用高度，上限 `0.14 × bandH`；
- 当前站站名 = 加粗 + 线路色，其余近黑 `#1A1A1A`。

## 7.2 flip（`arrowDirection == 2`）的修正

v2 只镜像了当前站下标和换乘牌下标，站名数组没镜像 —— 三者会错位。v3 在 `paint` 开头就把
`names / namesEn / interchanges` 统一按 `flip ? count-1-i : i` 展开成“显示顺序”数组，
终点下标固定为 `flip ? 0 : count-1`（箭头那一端），所以终点箭头、当前站、换乘牌、站名永远指同一个站。

## 7.3 离线预览用例（9 张条带 + 4 张方向面板）

`01` 红 12 站（末站 = 终点 + 换乘，v2 的 bug 用例）、`02` 绿 24 站、`03` 站名正排、`04` flip、
`05` 浅黄线路（深色环）、`06` 当前站即换乘、`07` 24 站 3 换乘、`08` 无换乘（回归）、
`09` flip + 左端终点即换乘；`10/11/12` 方向面板 4 格 / 2 格 / 1 格宽，`13` 无“往”+ 单侧箭头。

实测（2720×320 纹理）：`band=320 line=27 dot=61 current=79 termRing=98 termArrow=81
head=83 plate=82 name=45 font=SimHei`。

## 7.4 仍未做 / 已知边界

- `routeStripNamesBelow = true`（站名正排到线下方）时，若换乘站正好在线的末端，站名会和牌子挤在一起
  （牌子按 #4 必须挂在图标正下方）；默认配置（站名在上）没有这个问题。
- 吊板方向面板的底色固定为**白**（与条带同一块白板）。MTR 原版在吊板上会把白清成透明，
  深色面板透出来、黑字发闷；这里按实拍改成不透明白板。玻璃上的 ARROW 面板同样会变白。
