# 厦门屏蔽门：直接移植 MTR 实现（含吊板与渲染）

日期：2026-11-10　目标版本：1.20.1（Fabric）

**来源**：Minecraft Transit Railway（MTR）3.6.4.2，MIT License。
`libs/MTR-fabric-1.20.1-3.6.4.2.jar` 用 CFR 0.152 反编译后逐类移植，
每个文件的头一行都标了 `// Ported from Minecraft Transit Railway (MTR), MIT License.`

上一版（`docs/report-2026-11-10-xiamen-psd-tricolumn.md`）是照 javap 结论自己写的实现，
门扇开合、吊板外观、玻璃链都不对。这一版**不再自己发明**：类结构、属性、判定、模型、
贴图全部照 MTR 搬，只在「注册方式」和「渲染贴图」上做本地化。

---

## 1. 移植的类

| 本模组（`dev.js.mtrheadlight.xiamen`） | MTR 原类 | 说明 |
| --- | --- | --- |
| `PsdProps` | `mtr.block.IBlock`（只取屏蔽门用到的部分） | `HALF` / `SIDE` / `SIDE_EXTENDED` / `EnumSide`、`getVoxelShapeByDirection`、`getStatePropertySafe`、`getSideDirection`、`onBreakCreative`、`checkHoldingBrush`、`isReplaceable` |
| `PsdDoubleBlock` | `mtr.block.BlockDirectionalDoubleBlockBase` | 上下两段自毁 / `onPlaced` 补上段 / `getPlacementState` / 创造模式拆上段顺带清下段 / `rotate`·`mirror` 原样 |
| `PsdAPGBaseBlock` | `mtr.block.BlockPSDAPGBase` | 设置 `create().nonOpaque().strength(2.0f).dropsNothing()`、4px 选择框、相机碰撞为空、`getPickStack` |
| `PsdDoorBlock` + `TileEntityPsdDoor` | `mtr.block.BlockPSDAPGDoorBase` + `TileEntityPSDAPGDoorBase` | `open` 0..32、`openClient` 插值、`END` 现算、刷子锁定、开门时碰撞为空、另一半没了自毁 |
| `PsdGlassBlock` | `mtr.block.BlockPSDAPGGlassBase` | `side` 四值链、刷子右键重算（`connectGlass` 逐行照抄） |
| `PsdGlassEndBlock` | `mtr.block.BlockPSDAPGGlassEndBase` | `touching_left/right`（air / door / none）+ 自由端 4px 转角并进选择框 |
| `PsdTopBlock` + `TileEntityPsdTop` | `mtr.block.BlockPSDTop` + `TileEntityPSDTop` | `AIR_LEFT` / `AIR_RIGHT` / `SIDE_EXTENDED` / `propagate_property` / `PERSISTENT`、`getActualState`、屏障右键切 persistent、刷子右键转箭头、下方没了自毁 |
| `PsdAPGItem` | `mtr.item.ItemPSDAPGBase` | 门 2 宽、玻璃/端部 1 宽，都是 2 高 + 吊板 1；`blocksNotReplaceable` 整组检查；**只扣 1 个** |
| `RenderPsdDoor` | `mtr.render.RenderPSDAPGDoor`（type 0 = PSD 样式 1） | 门扇平移、端部门内外两片、门灯闪烁、「暂停使用」牌 |
| `XiamenPsdBlocks` | `BlockPSDDoor` / `BlockPSDGlass` / `BlockPSDGlassEnd` | 只补 `createBlockEntity` 的三个具体类 |

## 2. 渲染：为什么门是 BER、玻璃和吊板是 JSON 模型

反编译 `assets/mtr/blockstates/` 后确认 MTR 自己就是这么分工的：

- `psd_door.json` → 只剩 `{"variants": {"": {"model": "minecraft:block/air"}}}`，门完全由 BER 画；
- `psd_glass.json` / `psd_glass_end.json` / `psd_top.json` → 静态模型 + multipart/variants。

所以本模组：

- **门**：`RenderPsdDoor` 逐条照抄 `RenderPSDAPGDoor` 的几何数字（见下表），只有贴图换成厦门包；
- **玻璃 / 端部 / 吊板包边**：blockstate 与 model 的 JSON **原样搬**（只把 `mtr:` 改成 `mtrlte:`），
  共 50 个 model、4 个 blockstate。这样吊板的「横梁 + 圆弧底转角 + 端头封板」和
  `persistent` 上移 7.5px 全部与 MTR 一致，不用重写。

门扇几何（单位 1/16 格，**+z 朝外（朝轨道）**，+x 沿门宽），照抄 MTR 的 `ModelSingleCube`：

| 部件 | x | y | z |
| --- | --- | --- | --- |
| 整扇门 `MODEL_PSD` (tex 36×18) | -8..8 | 0..16 | 6..8 |
| 端部门外扇 `MODEL_PSD_END_*_1` | -8..0 / 0..8 | 0..16 | 6..8 |
| 端部门内扇 `MODEL_PSD_END_*_2` | 0..8 / -8..0 | 0..16 | 4..6 |
| 门灯 `MODEL_PSD_LIGHT_LEFT/RIGHT` | -1..0 / 7..8 | 16..17 | 2..3 |
| 暂停使用牌 `MODEL_PSD_DOOR_LOCKED` | -3..3 | 4..10 | 7（贴片） |

变换：`translate(0.5+x, y, 0.5+z)` + `rotateY(-facing.asRotation())`，开多少平移多少
（`open * (side == RIGHT ? -1 : 1)` 格），端部门内扇只平移一半（`open / 2`）。
与 MTR 的区别只在 `rotateX(180)` 被省掉、几何 y/z 已换算到「y 向上、+z 朝外」的空间，
两者数值一一对应（MTR 模型里 `y-16`、`z-8` 的写法就是同一套）。

门灯用 `LightmapTextureManager.MAX_LIGHT_COORDINATE` 画（等价于 MTR 的 `QueuedRenderLayer.LIGHT`
= `RenderLayer.getBeaconBeam`，全亮），其余面用 `RenderLayer.getEntityCutoutNoCull`
（等价于 MTR 的 `EXTERIOR` = `RenderLayer.getEntityCutout`）。

## 3. 吊板灯箱面

MTR 的吊板正面是一条**动态贴图**（车站名 / 线路图 / 方向箭头，靠 `ClientData` 的平台数据现生成）。
本模组没有那套数据，所以照 MTR 的**几何**画一条静态面板（`RenderPsdTop`）：

- `z = 1.95px`：正好贴在包边朝向站台的那一面外 0.05px（MTR 的
  `translate(-0.5, -offset, 1.95/16)` 算出的是同一处）；
- `y = 1.5 .. 8.5px`：MTR 的 `topPadding = 7.5/16`、`bottomPadding = 1.5/16` 经 `rotateZ(180)` 翻过来；
- `persistent != none` 时整条上移 `PERSISTENT_OFFSET_SMALL = 0.46875` 格；
- 贴图用厦门包拉丝金属 + 橙条（`xiamen_psd_top.png`）。

## 4. 保留 / 新增的 id

| id | 形态 | 状态 |
| --- | --- | --- |
| `mtrlte:xiamen_psd_door` | 方块 + 物品 + 方块实体 `mtrlte:xiamen_psd_door` | **保留**（旧存档不破） |
| `mtrlte:xiamen_psd_glass` | 方块 + 物品 | **保留** |
| `mtrlte:xiamen_psd_end` | 方块 + 物品 | **保留** |
| `mtrlte:xiamen_psd_top` | 方块 + 方块实体 `mtrlte:xiamen_psd_top`，**无物品** | **新增** |
| `mtrlte:xiamen_logo` | 方块 + 物品 | 不变（另见 logo 任务） |

属性：门 `facing/half/side/end/unlocked`（旧存档里的 `open`、`powered` 两个属性没有了，
带这两个属性的旧方块会被当成未知状态 —— 需要重新放一次，和上一版报告里的提醒一致）；
玻璃 `facing/half/side`；端部 `+touching_left/touching_right`；吊板
`facing/side/air_left/air_right/propagate_property/persistent`。

## 5. 贴图来源

| 用途 | 文件 | 来源 |
| --- | --- | --- |
| 门扇上段 正面 / 背面 | `xiamen_psd_door_top.png` / `_top_back.png` | 厦门资源包（原 `psd_door_top_1`），**未重采样** |
| 门扇下段 正面 / 背面 | `xiamen_psd_door_bottom.png` / `_bottom_back.png` | 厦门资源包（原 `psd_door_bottom_1`） |
| 吊板灯箱面 | `xiamen_psd_top.png` | 厦门资源包（原 `psd_top`） |
| 吊板包边（40 条 multipart 用） | `psd_top.png`（= 厦门那张）、`psd_top_edge.png` | 前者厦门包，后者 MTR |
| 玻璃 / 端部全部 | `psd_glass_*.png`、`psd_glass_diagonal_*.png`、`psd_side_light.png`、`black.png`、`transparent.png` | MTR 原版（这些 model 用 MTR 的 UV 分区，换图会错位） |
| 门灯 | `light_on.png` / `light_off.png` | MTR 原版 |
| 「暂停使用」牌 | `sign/door_not_in_use.png` | MTR 原版 |
| 物品图标 | 复用 `xiamen_psd_door_top` / `psd_glass_top_1` / `psd_glass_diagonal_top_1` | 上面两张 |

贴图文件都是**直接拷字节**，没有重新编码 / 缩放 / 压缩。

## 6. 与 MTR 的三处有意差异

1. **开门通道**：MTR 的门只有列车（`TrainServer`）会开，它按 `instanceof BlockPSDAPGDoorBase`
   找自己的方块，本模组的块类不是它的子类，所以收不到列车信号。为了让门能演示 / 能用，
   `TileEntityPsdDoor#serverTick` 补了一条**红石**通道：未锁定且有红石信号 → `open = 32`，否则 0。
   **不新增任何 blockstate 属性**，方块的属性集合与 MTR 完全一致。
2. **吊板灯箱面**是静态厦门贴图，不是 MTR 的站名 / 线路图（没有 `ClientData` 平台数据）。
3. **端部门的半扇贴图**用厦门门扇图按 u 0..0.5 / 0.5..1 裁，不是 MTR 那套
   `psd_door_end_*_1_1/_1_2` 专用图（厦门包没有对应半扇图）。

## 7. 验证

- `gradle build` → **BUILD SUCCESSFUL**。
- 资源自检脚本（`temp/psdcheck.js`）：4 个 blockstate 的属性组合全覆盖
  （门 64 / 玻璃 32 / 端部 288 / 吊板 1024，无缺变体），50 个 model 的 parent 与 13 张
  `psd_*` 贴图引用全部存在。
- **未启动游戏**，实机观感（门的滑动方向、吊板与门扇的贴合）未验证。
