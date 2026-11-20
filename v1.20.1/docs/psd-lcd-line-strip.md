# PSD-LCD 挂板：新模型（坐标变换）+ MTR 式线路色带

适用版本：`v1.20.1`（只改这一版）。模型源：用户给的 Blockbench 工程
`psd_lcd_xiamen-2.json`（两组 `mornal` = element 0..6、`half` = element 7..13），
仓库里的参考件是 `v1.20.1/tools/psd/psd_lcd_xiamen.json`（原封不动）。

---

## A. 新模型 → 我们的坐标系（`tools/psd-lcd-models.js`）

### 两个坐标系

| | 铺开轴 | 厚度轴 | 高度 |
|---|---|---|---|
| 用户的 Blockbench 工程（MTR 自己的朝向） | **Z** 0…16 | **X** 16…22（x=22 = 正面） | Y 0…16 |
| 我们的挂板（已验收的旧模型） | **X** 0…16 | **Z** 10…16（z=10 = 正面） | Y 0…16 |

### 变换（三步，全部由脚本算，没有一个数字是手敲的）

1. **把元素自带的 rotation 摆上去** —— Blockbench 的 mesh Euler 序 `R = Rz·Ry·Rx`，绕它自己的
   `origin`。用户模型的每个元素都带一个 `y = -90`（两块倒角是 `x/y/z` 三轴），这一步就是把
   「铺开沿 Z」转成「铺开沿 X」的那个四分之一圈。
2. **镜像 z**：`z' = 16 − z`。Blockbench 的 y 手性和 MC 相反；不镜像的话正面（用户模型的 x=22）
   会落到 z=16（背面），插屏凹槽会朝里 —— 镜像后凹槽朝观察者，和上一版验收模型一致。
3. **不平移**：第 1 步的 origin 在 x=16，四分之一圈之后 x 正好落在 0…16；第 2 步把厚度
   0…6 折到 10…16，与 `PsdLcdLayout.PANEL_Z0_PX = 10 / PANEL_Z1_PX = 16` 严丝合缝。

等价的一行公式（对 `y` 单轴旋转的那些元素）：
`(x, y, z) → (16 − z, y, 32 − x)`。倒角那两块因为是三轴复合旋转，脚本走「摆好 → 镜像」的
通用路径，再**反算验证**。

### 自检（`node tools/psd-lcd-models.js`，失败 exit 1）

* **等距**：每个元素 8 个角点的三条棱长、体积、对角线长度，变换前后必须一模一样；
* **包围盒**：
  * `mornal`：x 0…16、y 0…16、z 10…16
  * `half`：x 0…16、y 7.5…16、z 10…16
  （倒角是 22.5° 转出来的，最低那个角在三角函数最后一位上差 −1.06e-6px，容差 2e-3）
* **反算**：产出的 `(from, to, rotation)` 重新算 8 个角点，与变换后的角点误差 < 0.001px
  （实际 2e-6px）——「没有手调数字」这条由它保证；
* **贴图**：不许出现 `mtr:` 命名空间（`mtr:block/psd_top` → `mtrlte:block/psd_top`，同一张图）；
* **结构**：面名合法、`texture` 都在 `textures` 里、`uv` 是 4 个数、`from ≤ to`。

### 产出（`node tools/psd-lcd-models.js --write`）

`mornal` → `assets/mtrlte/models/block/psd_lcd_xiamen.json`（整块），
`half` → `psd_lcd_xiamen_half.json`（剪后只剩上半格）。两档各 7 个元素。

| 元素 | from → to | rotation |
|---|---|---|
| Main particle（唇边） | [0, 1, 10] → [16, 1.5, 15] | — |
| Bottom part（底板台阶） | [0, 0, 11] → [16, 1, 16] | — |
| Bottom curve lower | [0, 0, 10.2346] → [16, 1, 11] | x **+22.5°** @ [0,0,11] |
| Bottom curve upper | [0, 0.2346, 10] → [16, 1, 11] | x **−22.5°** @ [0,1,10] |
| Main particle（箱体） | [0, 1, 10.2] → [16, 16, 16] | — |
| Main particle（上半块蒙皮） | [0, 8.5, 10] → [16, 16, 15.8] | — |
| Bottom part（8.5 处的底口平面） | [0, 8.5, 10] → [16, 8.5, 11] | — |

（`half` 组是同一套形状整体上移：7.5…16。）

### 与旧模型的差别（用户确实改了模型）

* 插屏凹槽从 **1.6px 深**（屏幕面 z=10.5）变成 **0.2px 深**（屏幕面 **z=10.2**，BER 四边形 10.184）；
* 上半块蒙皮（第 5 个元素）现在一路铺到 y=16、z 10…15.8（旧模型是 8.5…15.5 + 15.5…16 顶板）；
* 用户不再导出 `ScreenPlane` 那一块 —— 屏幕上没有模型元素了，窗口带改成**从几何反推**
  （扫高度，找「最前面那个面 = 凹槽底」的连续区间，见 `derivedWindowBand()`）；
* 窗口 y 带没变：整块 1.5…8.5、剪后 9…16（都是 7px 高、整格宽）。
* 面名跟着面走：`north↔east`、`south↔west`（贴图不会跑到别的面上）；`cullface` 只在和面同向时
  保留（用户模型里有几处 `cullface` 和面不同向，照抄会让我们的正脸被旁边那格的方块剔掉）。
* 变换含一次 z 镜像，所以模型沿铺开轴是**左右镜像**的；这块板的贴图是金属板
  （`psd_top.png`，几何本身左右对称），肉眼不可见，文字由 BER 单独画，不受影响。

---

## B. MTR 是怎么画线路色带的（`mtr.render.RenderPSDTop`，jar 3.6.4.2，CFR 反编译）

### 1. 位置

`RenderPSDTop#renderAdditional(...)`：

```java
IDrawing.drawTexture(matrices, vertexConsumer,
    airLeft ? 0.625f : 0.0f, 0.90625f, 0.0f,
    airRight ? 0.375f : 1.0f, 0.9375f, 0.0f,
    facing, color, light);
```

* 常量 `COLOR_STRIP_START = 0.90625f`、`COLOR_STRIP_END = 0.9375f`；
* 这一层画在 `RenderRouteBase#render` 里「先 translate 到方块中心、rotY(facing)、再
  `translate(0, 1, 0)` + `rotZ(180)` + `translate(-0.5, -offset, 1.95/16)`」的**局部坐标**里，
  局部 y 是「从板子顶往下量」。换算成方块坐标：
  **y 14.5 … 15.0px（0.5px 高）**，上方留 1px 边；
* **z 方向**：MTR 的吊板是「6px 厚的箱体贴着方块背侧」，我们的板子在**自己的朝向**上一样占
  格子的后半格 z 10…16（正面落在 10）。色带那一层的偏移来自 `RenderRouteBase` 的构造参数
  `z = 1.95f`：沿板面法线离开方块表面 1.95px，比模型正脸再往外一点（防 z-fighting），
  和我们的 `FACE_GAP = 0.016px` 是同一个目的、只是量级不同；
* 铺开轴：整条链宽 `x 0…1`（= 16px/格）；**左右端头各内缩**：左侧邻格是空气时起点 0.625px，
  右侧邻格是空气时终点 0.375px（MTR 吊板端头有两道倒角，色带跟着收进去）。这三处只在
  `persistent == NONE` 时生效。

### 2. 颜色从哪来

`RenderTrains.scheduleRender(ClientData.DATA_CACHE.getColorStrip(platformId).resourceLocation, ...)`：

* `ClientCache#getColorStrip(platformId)` → `RouteMapGenerator#generateColorStrip(platformId)`
  —— **现场生成一张 1×N 的 PNG**，第 i 个像素 = `0xFF000000 | colors.get(i)`；
* 颜色列表来自 `RouteMapGenerator#getRouteStream(platformId, callback)`：

  ```java
  ClientData.ROUTES.stream()
      .filter(route -> route.containsPlatformId(platformId) && !route.isHidden)
      .sorted((a, b) -> a.color == b.color ? a.compareTo(b) : a.color - b.color)
      .forEach(route -> {
          int i = route.getPlatformIdIndex(platformId);
          if (i < route.platformIds.size() - 1) colors.add(route.color);        // 不是终点站
          else if (!terminatingColors.contains(route.color)) terminatingColors.add(route.color);
      });
  if (colors.isEmpty()) colors.addAll(terminatingColors);   // 全都终到本台才用终点色
  ```

  也就是 **`Route#color`（线路色）**，不是站台风向、也不是 `Platform` 的字段
  （`Platform#getPsdDisplayMode()` 只决定画不画，见 `RenderPSDTop#getRenderType` /
  `isDisplayMode2`）。同色去重；多线共站时色带就是几条横条。

### 3. 怎么画的

* **纹理**：运行时生成的 1×N 位图（`DynamicResource`），`u 0…1 / v 0…1` 拉满那条 0.5px 的带子；
* **渲染层**：`RenderTrains.QueuedRenderLayer.EXTERIOR` → `MoreRenderLayers.getExterior(id)`
  = `RenderLayer.getEntityCutout(...)`（cutout，**受场景光**，`light` 就是 BER 递进来的值）；
* **不发光**（没有用 `LIGHT` 层）；顶点色是 `RenderRouteBase.getShadingColor(facing, -1)`
  —— 那是一个灰度（正常 0xFFFFFFFF），**不是线路色**，所以颜色全部来自贴图；
* **只跟 `persistent` 有关**：`persistent != NONE` 时不画色带（`renderAdditional` 里的
  `isNotPersistent` 只影响 airLeft/airRight 的内缩）；**与门开合无关**（门是
  `BlockPSDAPGDoorBase` 的事，`getRenderType` 用它选择 `STATION_NAME` 还是 `ROUTE`，
  但色带照画）。
* 结论：**不是把带子烘进 `psd_top.png`**，而是一张**单独的、按线路色生成的 1×N 位图**，
  贴在一个纯色 quad 上（`psd_top.png` 只管板子本体）。

---

## C. 我们这边怎么做（`v1.20.1`）

### 色带的坐标

画在用户模型**留出来的那条 0.5px 前脸**上（`z = 10`，即 `PANEL_Z0_PX`），四边形再往前
`FACE_GAP_PX = 0.016` → **z = 9.984**：

| | y 带 | 高度 | 说明 |
|---|---|---|---|
| 整块（`half_cut=false`） | **1.0 … 1.5** | 0.5px | 窗口下沿那条唇边（element "Main particle"） |
| 剪后（`half_cut=true`） | **8.5 … 9.0** | 0.5px | 同一个元素在 `half` 组里的位置 |

**与 MTR 的差值**：MTR 是 y 14.5…15.0。我们的整块档画在 1.0…1.5（**低 13.0px**），
剪后档画在 8.5…9.0（**低 6.0px**）。原因：
* 用户模型里唯一一条 0.5px 的专用前脸就是那条唇边（整块在 y 1…1.5，剪后在 y 8.5…9）；
* 整块档 y 8.5…16 是一整片 z=10 的大面，理论上也能画 14.5…15，但剪过以后 y 9…16 **整块都是屏幕**，
  画在那里会压住 LCD 画面 —— 两档统一放在唇边上，位置一致、永远不和屏幕打架。

铺开轴：整条链 16px/格；**没有** MTR 那种端头内缩（0.625 / 0.375）—— 我们的板子是方角，
没有端头倒角可避。

### 颜色

`PsdLcdStrip#colors(platformId)` —— 逐条照抄 `getRouteStream` 的判据（`containsPlatformId`、
`!isHidden`、按颜色/名字排序、非终点站优先、终点色兜底、同色去重），颜色本身过
`XiamenRouteStyle#lineColor(cfg, route, route.name)`（和厦门 PIDS 的线路徽标、线路条带**同一个入口**：
先看 `lineColors` 手工覆盖，再退回 `Route#color`）。

* `platformId` = `TileEntityPIDS#getPlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE)`
  （和 MTR 自己取站台的方式一致）；
* 拿不到任何线路（没绑站台 / 数据没同步）→ **退化到白色**，并 warning 一次：
  `[lte] psd lcd: no route colour for platform …`；
* 多线共站：0.5px 的带子按颜色数均分成几条横带（= MTR 把 1×N 位图拉满那条带子）。

### 怎么画的

* `PsdLcdRenderer#drawStrip`：一格一段，每条颜色一个 **quad**（顶点色 = 线路色，
  贴图是一张 16×16 全白 `mtrlte:textures/block/white.png`），**不生成位图**、
  不往 MTR 的纹理缓存里塞东西，服务端零依赖、方块实体零新增字段；
* 渲染层是 `RenderLayer.getEntitySolid(白图)`（顶点格式
  `POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL`，**带 lightmap 元素**）。白图由 entity 层
  **按资源路径直绑**（`Sampler0` 读原图），不经过方块图集 —— 所以不会像老版本那样
  「只被代码引用 ⇒ 没进图集 ⇒ 缺失贴图 ⇒ 一格格黑紫方块」；
* 光照取**这一格自己**的位置：`WorldRenderer.getLightmapCoordinates(world, pos)`
  （天空光 + 方块光打包，夜间由 lightmap 纹理里的环境暗度压暗），和隔壁 MTR 原版吊板一致。
  以前用的是 `POSITION_COLOR`（没有 lightmap 元素）→ 色带恒满亮，用户对比原版吊板后指出
  「像在发光」，所以改回受光。**屏幕本身仍然满亮**（`MoreRenderLayers.getLight` + `FULL_LIGHT`，
  屏幕该像屏幕一样常亮），这里只改色带；
* 几何走 `PsdLcdLayout#stripQuad`，和屏幕四边形共用同一段 `planeQuad`（铺开轴、左右起点、
  朝向翻转同一份算术），所以不可能出现「色带画到屏幕背面」。绕序朝外（自检脚本四向验算
  `cross·normal > 0`），`getEntitySolid` 开剔除也不会被剔掉。

### 开关

`PidsXiamenSettings#lineStrip`（默认 **开**，`config/lte_xiamen_pids.json` 里的 `lineStrip`），
开关在**刷子打开的「厦门 PIDS」配置界面最上面一行**（开关类一律排在上面），
文案 `gui.mtrlte.psd_lcd.line_strip`（中：显示线路色带（下沿 0.5px）／英：Show the line colour strip）。
它是全局设置，点一下立刻落盘，关界面不发包。

---

## 验收

```
gradle -p v1.20.1 build                                   # 通过
node v1.20.1/tools/psd-lcd-models.js                      # 通过（含等距 + 包围盒 + 反算断言）
node v1.20.1/tools/psd-lcd-facing-check.js                # 通过
node v1.20.1/tools/psd-lcd-fit-check.js                   # 通过（含 3 张真出图的预览）
```
