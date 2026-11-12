# 厦门 PIDS：按参考图重写排版 + 两个老问题

范围：`v1.20.1` 的 `dev/js/mtrheadlight/xiamen/pids/`。只改 1.20.1，`compileJava` 通过，未进游戏。

## A. 排版：两块屏各一套，全部以底图像素为单位

底图没动（`pids_xiamen_metro.png` / `pids_xiamen_brt.png` 与桌面素材 md5 一致），
**框线、圆角、M 标志、右上角线路徽章条、底部标语框都已在图里**，渲染器只往里写文字。

坐标系换成「底图像素」：屏幕平面 = 512×235，`scale(1/261.1)` → 1.96×0.90 格（与原来 251×115 / 128 物理尺寸相同）。

### 量出来的框（脚本扫 PNG 的描边/卡片连通域）

| | 地铁 | BRT |
|---|---|---|
| 左列 | x 7..169 | x 9..167 |
| 左上框（标志+日期时间） | y 6..47 | y 9..70 |
| 竖排框 | y 51..107 / 111..166 / 172..227（**三个**） | y 78..147 / 156..225（**两个**） |
| 广告区 | x 176..501, y 34..197（无描边，可拉伸） | x 178..504, y 9..176 |
| 标语条 | x 178..501, y 199..227 | x 178..504, y 185..225 |

两个版本的广告区与标语条**右边界都对齐**（501 / 504）。

### 字号 = 框高 × 比例（不再用天津的固定值）

- 地铁竖排框：标签 0.18、标签小字 0.105、终点站 0.27、英文 0.125、倒计时 0.36、单位 0.16；
  上下内边距 0.07、行距 0.05；左列 x=9px、右列 x=69px（框内偏移）。
- BRT 竖排框：第一行 0.21、「线路号」0.21、「约」0.16、数字 0.30、单位 0.15；内边距 0.10、行距 0.12。
- 左上框：地铁日期 0.22 / 时间 0.30，BRT 日期 0.15 / 时间 0.20；都写在 M 标志右侧（x 比例 0.33 / 0.41）。

## B. 叠字：换掉 MTR 的 `drawStringWithFont`

`docs/pids-ticker.md` 之外，这次新增 `PidsXiamenText`：直接用 `TextRenderer.draw`（1 px = 1 px，
`scale = 目标字高 / 8`），一行字占的高度就是它的字高，行与行由 `drawFlow()` 依次往下递 —— 不可能叠。
`IDrawing.drawStringWithFont` 的坑（字号 = 1/lineHeight、y 的含义随 VerticalAlignment 变、maxWidth 是行高倍数）
不再参与排版。

## C. 拆一半另一半掉落

`BlockPIDSBaseHorizontal.getStateForNeighborUpdate` 一旦发现 `facing` 方向的邻居不是自己就返回 AIR，
那条路径会 drop。现在：

- `onBreak` 先 `world.breakBlock(partnerPos, false, player, 512)`（`drop=false` = SKIP_DROPS，只放粒子和音效），
  再让本格正常掉 1 个 → **不管拆哪一半都只得到 1 个**；
- 新增 `onStateReplaced` 兜底（活塞/爆炸/`/setblock`），并用 `clearingPartner` 标记防止反向递归
  （否则 `onStateReplaced` 会把玩家正在拆的那格也按「不掉」删掉）。

## D. 靠近时方块变透明

上一版把原因归到「收尾 `enableCull()` 覆盖了别人的状态」并去掉了收尾；这次继续往下查：

- 底图/广告原来走 `MoreRenderLayers.getLight(...)` = `RenderLayer.getBeaconBeam`，
  它的 `WriteMaskState` 是 **COLOR_MASK（不写深度）**；1.20.1 的半透明区块通道在 BER 之后才画，
  于是屏幕永远挡不住背后的东西，走近时屏幕会「透」出来。
- 改成 `RenderLayer.getEntityCutoutNoCull(texture)`：**写深度**、不剔除、entity 通道，
  顶点补 `overlay(DEFAULT_UV)`（这一层的格式多一个 overlay 元素）。
- 渲染器仍然一个全局 GL 状态都不碰；每一段（底图/广告/时钟/到站/标语）各自 try-catch，
  某段抛异常不会打断同一面屏的其它段，也不会把共享 BufferBuilder 留在半个四边形上。

## E. 诊断日志（用户要求的那条）

```
[lte] pids draw halves=1 faces=2 kind=METRO pos=... facing=... partner=true rows=3
[lte] pids draw: pos=... 同一帧被画了 N 次（两半都在渲染？）      ← 只在真的重画时出现
```

`halves=1` 是设计值（只有 `facing ∈ {SOUTH, WEST}` 那一半画）；`faces=2` = V 形双面屏的两面各一块。
两块都打 `halves=1` 且没有第二条警告，说明**不是两半重复渲染**。

## F. 验证方式

`temp/preview/LayoutPreview.java` 直接引用 `PidsXiamenLayout` 的真常量，在底图上用 AWT 把字摆出来
（MC 字宽近似：ASCII 0.75×字高、CJK 1.0×字高），输出 `temp/preview/out/preview_{metro,brt}[_long].png`，
用来核对「不叠字 / 不压边 / 长站名自动缩 / 长标语被裁在框内」。

---

> **后续修订（2026-11-12 晚）**：地铁广告矩形改成按用户粉色标记量出来的 `179,33–512,198`（本文里的
> 170/34–512/199 已作废），标语不再等比缩小、改成固定字号按边裁。见
> `docs/report-2026-11-12-pids-dep-leftovers.md`。
