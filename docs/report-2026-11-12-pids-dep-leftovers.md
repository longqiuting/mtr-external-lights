# 厦门 PIDS / DEP：六个遗留问题（只 1.20.1）

范围：`v1.20.1` 的 `xiamen/pids/`、`xiamen/dep/`、`ConfigScreen`、`ParameterScreen`、lang 未动。
`gradle -p v1.20.1 compileJava` 通过（0 error，只剩改动前就有的 4 条 deprecation 警告）；**未进游戏、未 commit**。
父级把底图/广告层换成 `MoreRenderLayers.getLight(id, false)`（beacon beam）这件事**保持不动**。

| # | 问题 | 结论 / 改法 | 状态 |
|---|---|---|---|
| 1 | DEP 文字被模型挡住 | 屏幕整块从 `z=12.28/16`（边柱正面 z=12 **之后**，缩在口袋里）挪到 `11.96/16`（正面**之前** 0.04/16）；数字层再往前 1.5px | 已改，只能实机确认观感 |
| 2 | BRT「约 即将进站」 | 无单位那一档（即将进站 / 已到站）不再写「约」，改成「线路号 + 空格 + 状态」 | 已改 |
| 3 | BRT 广告消失 | 广告层单独再往前推 6px（`AD_LAYER_Z`），让**深度测试**而不是绘制顺序决定谁在上面 | 已改 + 新增一行诊断日志 |
| 4 | 地铁广告区按粉色标记 | 矩形从 `170,34–512,199` 改成量出来的粉色 bbox `179,33–512,198` | 已改，有叠图核对 |
| 5 | 标语自然截断 | 删掉「长句等比缩小」，固定字号滚动、只在条边逐字裁掉（= 天津的 UV 裁边效果） | 已改 |
| 6 | 仪表板入口位置 | 「厦门 PIDS」从主界面第 4 个按钮搬到 **全局配置 → 其他**；顺手把「立即重载光源」搬到 **光源** | 已改 |

---

## 1. DEP 文字被模型挡住

`models/block/dep_on_wall.json` 一共 6 个元素（用脚本按元素逐面光栅化核对过，没有任何「支架/横臂」
挡在屏幕前面）：

```
元素 0  背面板       z = 16
元素 1  LED 发光板   z = 12.3   （x 2..14, y 4..12 = 96×64 px 的屏幕）
元素 2..5 四周边柱/横梁  z = 12..16，正面在 z = 12
```

旧值 `ANCHOR_Z = 12.28/16`：

- 底图在 `z = 0.7675`（模型板面 12.3/16 = 0.76875 之前 0.00125 格）—— 和模型自己那块发光板**共面打架**；
- 但整块屏还在边柱正面（0.75）**之后** 0.0175 格，等于缩在 0.3/16 深的口袋里；
- 数字再往前 0.5px 只到 `12.2175`，仍然在边柱正面之后、离模型板面也只有 0.005 格。

于是「模型里的那块板 / 口袋内壁」在正面和斜角都在抢同一个深度 —— 就是用户看到的
「文字被模型挡住」。现在：

| | 旧 | 新 |
|---|---|---|
| `ANCHOR_Z` | `12.28/16`（= 0.7675） | `11.96/16`（= 0.7475，边柱正面前 0.04/16 ≈ 0.0025 格） |
| `TEXT_LAYER_Z`（数字） | `-0.5px`（0.0039 格） | `-1.5px`（0.0117 格） |

屏幕（96×64）本来就正好等于边柱中间的开口，挪到开口**前面**以后，模型上没有任何东西能挡在它前面，
也不会再和模型板面 z-fighting。0.0025 格的位移在 0.75 格宽的屏上量不出来。
另外给渲染器加了 `rendersOutsideBoundingBox() = true`（屏幕比方块包围盒探出 0.0025 格）。

> 限制：这一条只能进游戏看。我核过模型几何（见上）和深度关系，但没有实机画面能证明「挡住」的
> 具体是哪一块几何；这个改法把**所有**模型在前的可能性一次排除。

## 2. BRT「约 即将进站」

`routeLine()` 原来无条件拼四段：`线路号` + `约` + `数字` + `单位`。
「即将进站 / 已到站」这一档 `unit` 是空串、`number` 是状态词，于是读成 **「1号线 约 即将进站」**。

现在按单位是否为空分两支：空 → `线路号（线路色）` + 空格 + `状态`（状态用倒计时那一档字号，
`0.30 × 卡高` ≈ 20.7px）；非空 → 原来的「线路号 约 N 分钟」。
地铁版本来就只写状态（`countLine`），不受影响。

## 3. BRT 广告消失

底图和广告是**两张贴图 = 两个 `RenderLayer`**，谁先落进
`VertexConsumerProvider` 的缓冲区只看「这一帧谁先请求」，而**同一张广告图的层会被上一块屏先排队**
（两屏共用同一套广告图）。当广告层的排队位置在底图**之前**时，底图后画：

- 旧的 `getEntityTranslucentEmissive` 是「混合 + 写深度」，还能透出一点；
- 现在父级换成的 beacon 层（`getLight(id, false)`）= `NO_TRANSPARENCY + ALL_MASK`，**颜色直接覆盖**；
- 两者只差 `TEXT_LAYER_Z = 0.35px`（0.0013 格），远处早已小于深度精度 —— 于是广告被底图整块盖掉。
  同一对屏里先画的那块（地铁）顺序是好的，所以「**只有 BRT 广告消失**」。

改法：广告单独用 `AD_LAYER_Z = -6px`（≈0.023 格，屏幕上量不出来）再往前推，
让**深度测试**而不是绘制顺序决定胜负：不管谁先画，近的一定赢。
文字层同时从 `-0.35px` 抬到 `-7px`（`TEXT_LAYER_Z`，仍在广告之前），这样「文字压在广告上」的
版式以后也不会反过来被广告挡住。另外每种屏第一次画广告时打一行日志，以后能一眼分清
「没画」和「被盖住」：

```
[lte] pids ad: kind=BRT box=(179.0,10.0..504.0,176.0) index=0/3 tex=mtrlte:advertisements/scenery1.png
```

## 4. 地铁广告区按粉色标记

参考图 `C:\Users\AD\Desktop\厦门地铁pids示例1.png` 用脚本扫粉色像素（R>220、R−G>40 等）：

```
粉色 bbox = x 179..511, y 33..197     （行 35..195、列 180..500 全部命中，共 50975 px）
底图同位置的边界：左栏白描边 x 168..169；徽章条最后一行 y 32；标语框白边框 y 199 / x 177..178
```

所以矩形取 `179,33 – 512,198`（bbox 各 +1 作开区间）：

- 左 179 = 标语框白边框**内侧**那一列（图上的「和下面标语边框对齐」就是这条）；
- 上 33 = 徽章条（最后一行 32）下面第一行；
- 下 198 = 标语框白色上边框（199）上面一行；
- 右 512 = 屏幕右边缘（粉色一直铺到 511）。

上一版左边界 170 是按「左栏外沿 169 再让 1px」推的，比标记宽 9px。
核对图：`temp/out_ad_metro_pinkfit.png`（新矩形半透明红叠在底图上，左栏白边、标语框白边都没被盖）。

BRT 的广告卡片仍是 `179,10 – 504,176`（1px 描边内侧），不变。

## 5. 标语自然截断

看天津 `ziyue.tjmetro.client.ScrollingText` 的字节码：字号只由 `availableHeight / 图高` 决定
（**不缩字**），宽度用 `min(availableWidth, 图宽 × 比例)`，滚动时用 `u0/u1` 在两端**裁边**
（`class_4588` 顶点 uv 截断，见 `IDrawing.drawTexture` 调用前的 `Math.max((offset - col) / cols, 0)`
与 `Math.min(offset / cols, 1)`）。也就是说「边缘自然截断」= 固定字号 + 两端裁掉。

我们的做法本来就逐字裁切（`PidsXiamenText.drawClipped`），多出来的只有「长句等比缩小」那一步：

- 删掉 `TICKER_MAX_WIDTH_RATIO`（原 1.0 = 内宽）；
- `PidsXiamenTicker.draw()` 的 `width[]` 改用自然宽度，`drawRun(maxWidth = 0)` = 不限宽（不缩字）；
- 滚动行程仍按真实宽度算，长句只是滚得久一点，字不再变小。

## 6. 仪表板入口位置

- `ConfigScreen`（LTE 仪表板主界面）去掉「厦门 PIDS」按钮：**五个入口 → 四个**
  （全局配置 / 侧线配置 / 隐藏原版列车 / 重载）。
- 「厦门 PIDS」三开关（本地时间 / 即将进站 / 广告）改成 **全局配置 → 其他** 分区里的一行
  （`ParameterScreen` 的 `OTHER`，`Row.action` → `PidsXiamenSettingsScreen`，返回时回分区页）。
- 顺手把「立即重载光源」从「其他」搬到 **光源** 分区最底部（`btn.reload` 重载的就是光源缓存）。
- 这两个开关/按钮本身一点就落盘，不进主界面的「完成 / 取消」快照 —— 行为没变。

## 改动文件

```
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenLayout.java     广告矩形 / AD_LAYER_Z / 删 TICKER_MAX_WIDTH_RATIO
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenRenderer.java   广告层 z + 诊断日志；BRT 即将进站文案
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenTicker.java     不缩字、按边裁
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenSettingsScreen.java  入口注释
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/dep/DepOnWallLayout.java       ANCHOR_Z 11.96/16、TEXT_LAYER_Z -1.5px
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/dep/DepOnWallRenderer.java     rendersOutsideBoundingBox
v1.20.1/src/main/java/dev/js/mtrheadlight/ConfigScreen.java                     去掉 PIDS 入口（四入口）
v1.20.1/src/main/java/dev/js/mtrheadlight/ParameterScreen.java                  PIDS 进「其他」、重载进「光源」
temp/preview/LayoutPreview.java                                                预览工具同步（不缩字）
temp/out_ad_metro_pinkfit.png                                                  新广告矩形的叠图核对
temp/pids_ref_measure.js、temp/dep_model_view.js / temp/dep_north.png          量粉色 bbox / 逐面核对 DEP 模型
docs/pids-ticker.md、docs/dep-on-wall-countdown.md                             行为/几何说明同步
```

## 进游戏要看的四件事

1. DEP 倒数时数字完整、不再被边框/板面切掉（正面 + 斜 45° 各看一眼）；
2. BRT 卡在最后一分钟时第二行读作「1号线 即将进站」，没有「约」；
3. BRT 屏上有广告图（`logs/latest.log` 里应有 `[lte] pids ad: kind=BRT ...`）；
4. 地铁广告区左边缘和标语框左白边对齐（不再多出 9px）。

UI 入口：主界面只剩四个按钮；「厦门 PIDS」在 **全局配置 → 其他**。
