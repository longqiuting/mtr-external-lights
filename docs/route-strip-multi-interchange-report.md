# 屏蔽门线路图：多换乘（一字排开 / 55% 缩放下限 / 尾部截断）

仅 `v1.20.1`。目标：PSD 玻璃 / 吊板上那条线路图（`XiamenStripPainter`），一个站挂 **多条**换乘线路时
不再只画第一条。

## 改了什么

| 文件 | 改动 |
| --- | --- |
| `v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/XiamenStripPainter.java` | 新增纯函数 `fitBadgeRow` + `BadgeFit`；`dataInterchange` → `dataInterchanges`（一个站的全部换乘）；新增 `drawSwap` 分派、`drawSwapBody`（环 + 垂尾，从 `drawInterchange` 原样抽出）、`drawInterchangeRow`（一排单行小牌） |
| `v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/XiamenRouteStrip.java` | `interchanges()` 一个站收齐**全部**换乘线路（扁平数组，同一站可连续多条，顺序 = 线路色升序）；`firstForeignTuple` → `foreignTuples`（全量 + 去重 + 升序）；日志多打 `badges=N/M+K scale=S` |
| `temp/route-strip-preview/MultiSwapPreview.java` | 新增离线验收：纯函数矩阵断言 + 6 张场景 PNG + 像素断言 |

版式分派（`drawSwap`）：**1 条**换乘 → 老的两行线路牌，一个像素都不动；**2 条以上** → 一排单行小牌。
其它元素（主线 / 圆环 / 站名 / 终点箭头 / 换乘环）位置一律不动。

## 规则（纯函数 `XiamenStripPainter.fitBadgeRow`）

输入：各标的**文字**宽（满缩时量出来的像素）、可用宽、标的**基础**高；输出：`scale` / `count` / `widths` / `gap` / `total`。

1. 每个标的宽度 = 文字宽 + 2 × 内边距，`pad = 标高 × 0.30`（`BADGE_PAD_RATIO`），标间距 = 标高 × 0.16；
2. 总宽（含间距）> 可用宽 → `scale = 可用宽 / 总宽`，**下限 0.55**（`BADGE_MIN_SCALE`），字号 / 内边距 / 间距一起缩；
3. 缩到 0.55 仍放不下 → **从尾部截断**，只留前面放得下的，**至少留 1 个**（连 1 个都放不下时锁 0.55 + `overflow` 标记，调用方收进面板）；
4. 截断后腾出来的宽度允许再放大（不超过 100%），所以“画几个”= 能让 `scale ≥ 0.55` 的最大前缀；
5. 可用宽 = **本格站距 × 0.92**（`BADGE_AVAIL_RATIO`）；已过站的整排小牌用 `PASSED_NAME_GREY`（和站名同一个灰），不用线路色。

标高楼 = 两行线路牌的 0.80（单行字），上沿仍在原来线路牌那条基线上（`lineY + 环半径 + gap`），所以
`namesBelow` 的避让、`plateFloorY` 的收边、面板左右夹边全部沿用老逻辑。

## 离线验收

```
cd /d/MC-MTR-JS/mtr-headlight
JAVA="C:/Program Files/Java/jdk-21.0.10/bin"
"$JAVA/javac" -encoding UTF-8 -d temp/route-strip-preview/out-after \
    v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/XiamenStripPainter.java \
    temp/route-strip-preview/MultiSwapPreview.java
"$JAVA/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -cp temp/route-strip-preview/out-after dev.js.mtrheadlight.xiamen.MultiSwapPreview \
    D:/MC-MTR-JS/mtr-headlight/temp/route-strip-preview/multi
```

退出码 0 = 全过。当前 **184 条断言全过**（`temp/route-strip-preview/multi/report.txt`）。

### 矩阵（标高 25px，pad 7.5，gap 4；“4号线”34px / “BRT快速公交”76px）

| 换乘数 | 宽站距 280px（可用 257） | 中站距 113px（可用 103） | 窄站距 46px（可用 42） |
| --- | --- | --- | --- |
| 1 | 1 个 @1.000（49px） | 1 个 @1.000 | 1 个 @0.864 |
| 2 | 2 个 @1.000（144px） | 2 个 @0.722（104px） | 1 个 @0.864（截断） |
| 3 | 3 个 @1.000（197px） | 2 个 @0.722（截断） | 1 个 @0.864（截断） |
| 4 | 4 个 @0.882（257.6px） | 2 个 @0.722（截断） | 1 个 @0.864（截断） |

每条都断言：`0.55 ≤ scale ≤ 1`、`1 ≤ count ≤ n`、`total ≤ 可用宽`、`widths.length == count`、
未截断时 `scale == min(1, 可用/总宽)`、截断时“n 个在 0.55 下确实放不下”且“留下的个数在 0.55 下放得下”。
极端窄（可用 12px / 2 个）：`count=1, scale=0.550, overflow=true`（仍画 1 个）。

### 场景 PNG（`temp/route-strip-preview/multi/`）

| 图 | 场景 | 关键数值 | 断言 |
| --- | --- | --- | --- |
| `S1-wide-4-swaps.png` | 10 站 / 8.5 宽高比，1 站 4 条换乘 | step 280px、可用 258px → **4 个 @1.000**、总宽 208px；四块牌 x = 1116/1169/1222/1275（各 49px，互不重叠） | 一字排开、不越格、不碰站名 |
| `S2-narrow-3-swaps-shrink.png` | 24 站，1 站 3 条 | step 110px、可用 101px → **3 个 @0.650**、总宽 100.8px（未截断） | 缩放 ≥55%、牌只在牌带里 |
| `S3-narrow-6-swaps-truncate.png` | 24 站，1 站 6 条 | 同上 → **画 3 个 @0.650，dropped=3**（尾部 3 条不画） | 截断后 ≥1、仍不越界 |
| `S4-passed-3-swaps-grey.png` | 已过站的 3 条换乘 | 3 块牌全部 `#9E9E9E`（= 站名灰），环 / 垂尾 `#B2B2B2` | 不用线路色 |
| `S5-names-below-3-swaps.png` | 站名正排 | 牌 164–188，站名顶 198 → **牌下沿 188 < 站名顶 198** | 不压站名 |
| `S6-flip-3-swaps.png` | 方向 2（整条镜像） | 牌 x 1693/1728/1763（镜像后仍从左到右不重叠） | 镜像不错位 |

每张图另有 `-zoom.png`（该站 3× 放大）。

像素断言（每张图）：
- 一排牌全部落在本格 `[cx − 半个站距, cx + 半个站距]`，总宽 ≤ 可用宽 + 3px（字体 bearing 余量）；
- `lineY → 牌上沿`这段（线的图标区）里没有牌的颜色 → 斜排站名（被裁在 `lineY` 以上）不可能被压；
- `namesBelow`：`NAME_GREY` 站名包围盒的顶 > 整排牌的下沿；
- 多块牌按颜色取包围盒，x 区间严格递增且互不重叠（一字排开）。

## 回归

单条换乘的老版式 **像素级不变**：`git show HEAD` 的旧 `XiamenStripPainter` 与新版各画一遍
`RouteStripPreview`（13 张，含当前站换乘 / 起始站 / 镜像 / 站名正排），`diff -rq` 无差异。

## 构建

```
cd /d/MC-MTR-JS/mtr-headlight && JAVA_HOME="C:/Program Files/Java/jdk-21.0.10" \
  "/d/MC-MTR-JS/temp/gradle/gradle-8.14/bin/gradle" -p v1.20.1 build --no-daemon -q
```
