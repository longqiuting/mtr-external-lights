# 厦门 4 / 6 号线屏蔽门全套（含门灯绿色）— 2026-11-11

四个版本同步：`v1.18.2` / `v1.19.2` / `v1.19.4` / `v1.20.1`。
做法沿用 2/3 号线的 `_l2` / `_l3` 机制：**同一套方块类、按 `XiamenLine` 实例化，只换贴图与门灯色**。

---

## 1. 素材对比（先枚举，再动手）

`C:\Users\AD\Desktop\素材\` 下新增：

| 目录 | 内容 |
|---|---|
| `厦门地铁4屏蔽门\` | 40 张 `*.png` + `door_tile_entity_migration\` 子目录（10 张旧版迁移图，与 1/2/3 一致，本模组不用） |
| `厦门地铁6屏蔽门\` | 同上，40 + 10，文件名集合逐字节相同 |
| `厦门地铁4号线物品贴图\` | 6 张，**文件名仍是 `amtr_3_*`**（照抄 3 号线命名），内容是新图 |
| `厦门地铁6号线物品贴图\` | 6 张，同样叫 `amtr_3_*`，内容又是另一套 |

**与 1/2/3 的结构差异（只有两处）：**

1. **物品图标文件名没改**：4/6 目录里 6 张图标仍以 `amtr_3_` 开头，6 张 md5 与 3 号线**全不同**。
   落盘时改名为 `amtr_4_*` / `amtr_6_*`，`models/item/*_l4.json` 的 `layer0` 跟着指过去，
   否则会和 3 号线互相覆盖。
2. **真的只有上段不同**：40 张贴图里 14 张与 3 号线不同，全部是「上段 / 玻璃上段 / 端部上段」，
   也就是线路色带 + 站名横幅那张；其余 26 张（玻璃、门框、下段、通用件）三线逐字节相同。
   4 号线与 6 号线之间同样是 14 张不同、26 张相同。

**吊板与 1/2/3 完全一致**：4/6 素材里的 `psd_top.png` = `98f3df4b…`、`psd_top_edge.png` = `20610796…`，
和模组里 `psd_top.png` / `psd_top_l2/l3.png` 的 md5 一模一样，所以吊板不需要区分线路（照要求统一用浅色那两张）。

---

## 2. 新增内容清单

### 2.1 方块 / 物品 id（每条线路 7 个方块、6 个物品）

`psd_` / `apg_` 前缀保持不变（MZYC better MTR 靠它认门），后缀加在最后：

| 4 号线 | 6 号线 | MTR 父类 | 格 |
|---|---|---|---|
| `mtrlte:psd_door_xiamen_l4` | `mtrlte:psd_door_xiamen_l6` | `BlockPSDAPGDoorBase` | y+0 / y+1，有 BE |
| `mtrlte:psd_glass_xiamen_l4` | `mtrlte:psd_glass_xiamen_l6` | `BlockPSDAPGGlassBase` | y+0 / y+1 |
| `mtrlte:psd_end_xiamen_l4` | `mtrlte:psd_end_xiamen_l6` | `BlockPSDAPGGlassEndBase` | y+0 / y+1 |
| `mtrlte:psd_top_xiamen_l4` | `mtrlte:psd_top_xiamen_l6` | `BlockPSDTop` | y+2 吊板（无物品） |
| `mtrlte:apg_door_xiamen_l4` | `mtrlte:apg_door_xiamen_l6` | `BlockAPGDoor` | 半高 2 格 |
| `mtrlte:apg_glass_xiamen_l4` | `mtrlte:apg_glass_xiamen_l6` | `BlockAPGGlass` | 半高 2 格 |
| `mtrlte:apg_end_xiamen_l4` | `mtrlte:apg_end_xiamen_l6` | `BlockAPGGlassEnd` | 半高 2 格 |

方块实体类型**不新增**：仍复用 `mtrlte:psd_door_xiamen` / `psd_top_xiamen` / `apg_door_xiamen` /
`apg_glass_xiamen` 四个 `BlockEntityType`（一个类型挂多条线路的方块，老存档 BE id 不变）。
渲染器同样不新增，线路与门灯色都在渲染时按**方块实例**反查。

### 2.2 每个版本的资源增量（× 2 条线路）

| 类别 | 每线 | 说明 |
|---|---|---|
| `textures/block/*_l4.png` | **40** | 素材同名 + `_l4` 后缀（`psd_top_1_l4.png` 即浅色吊板图） |
| `textures/item/amtr_4_*.png` | **6** | 4/6 号线目录里的图标，去掉 `amtr_3_` 前缀重命名 |
| `blockstates/*_l4.json` | **7** | 由 `_l3` 克隆，`_l3` → `_l4` |
| `models/block/*_l4.json` | **58** | 同上 |
| `models/item/*_l4.json` | **6** | 同上，另外 `amtr_3_` → `amtr_4_` |
| `data/mtrlte/loot_tables/blocks/*_l4.json` | **6** | 仅 v1.20.1（1.18.2/1.19.2/1.19.4 本来就没有 `data/` 目录，与 `_l3` 保持一致） |
| `lang/zh_cn.json` + `lang/en_us.json` | 7 + 7 | 「厦门地铁四号线…」/「Xiamen Line 4 …」 |
| 创造页签 | 自动 | `XiamenLine.values()` 驱动，顺序 L1 → L2 → L3 → L4 → L6 |

合计每版本新增 **234 个文件**（117 × 2 条线路），v1.20.1 再多 12 个掉落表。

### 2.3 吊板贴图

`psd_top_l4.png` / `psd_top_edge_l4.png`（以及 l6）直接取自素材里的
`psd_top.png` / `psd_top_edge.png`，与 1/2/3 号线同一对浅色图（`98f3df4b` / `20610796`），
所以五条线路的吊板视觉完全一致。

---

## 3. 门灯绿色

### 3.1 贴图（本来就有，直接复用）

`assets/mtrlte/textures/block/` 里已有门灯三色变体（像素统计，`PngProbe` 读出来）：

| 变体 | `light_on` | `light_off` | 用在哪 |
|---|---|---|---|
| 基础（橙，= MTR 原版） | `#FF9900` | `#993300` | 1/2/3 号线 |
| `*_green` | `#00C853` | `#00662A` | **4/6 号线** |
| `*_blue` | `#0099FF` | `#004C80` | 可选配置 |

`light_*.png` 16×16 全像素不透明；`apg_door_light_*.png` 64×64、不透明像素 804 个、其余 alpha=0
（与 MTR 原版那两张的 alpha 掩码逐像素相同，只换了 RGB）。on/off 是一对**明暗不同的绿**，
所以开合时真的会亮/灭，不是同一张图改个名。

### 3.2 从「按站台线路」改成「按方块 → 颜色」

旧的链路是：门坐标 → 最近的站台（`RailwayData.getClosePlatformId`）→ 站台所属线路 →
线路号 → 查 `doorLightByLine`。它依赖世界数据（站台连没连线路、线路起没起名、数据同步没），
4/6 号线只是「刚好会写进配置」，一旦站台数据不到位就会画回橙色。

现在改成**由方块实例直接给出线路**，因为每条线路的 7 个方块本来就是分别 `new` 出来的
（`XiamenPsdBlocks.LineAware#line()`）：

```java
// XiamenLine
L1("",       "1", "orange"),
L2("_l2",    "2", "orange"),
L3("_l3",    "3", "orange"),
L4("_l4",    "4", "green"),   // ← 新
L6("_l6",    "6", "green");   // ← 新
public String doorLight();    // 内置线路色
```

```java
// XiamenDoorLights.colorFor(Block, BlockPos) —— 渲染器每帧调
① doorLightByLine["4"] / ["4号线"]        逐线手工覆盖（最具体）
② doorLightColor != 默认橙                 全局手工覆盖（显式设置才算）
③ XiamenLine#doorLight()                   内置：1/2/3 橙、4/6 绿
```

原来的站台查询保留为 `colorFor(BlockPos)` 兜底（拿不到方块时才会走到），
`lineNumberAt()` 与缓存/失效逻辑一字未改。

渲染器两处调用点改成把方块一起传进去：

```java
XiamenTextures.withVariant(variant,
        XiamenDoorLights.colorFor(entity.getCachedState().getBlock(), entity.getPos()), ...);
```

于是同一条站台上混放 3 号线和 4 号线的门，也各画各的颜色 —— 不再依赖「这个站台属于哪条线」。

### 3.3 贴图改写链（未改动，只是吃到了新颜色）

`XiamenTextures.remap` 的候选顺序是「线路+颜色 → 颜色 → 线路 → 基础图」：

```
light_on.png                + _l4 + green → light_on_l4_green.png（无）→ light_on_green.png ✅
light_off.png               + _l4 + green → …                          → light_off_green.png ✅
apg_door_light_on.png       + _l4 + green → …                          → apg_door_light_on_green.png ✅
apg_door_light_off.png      + _l4 + green → …                          → apg_door_light_off_green.png ✅
psd_door_top_left_1.png     + _l4           → psd_door_top_left_1_l4.png ✅
psd_door_end_top_left_1_1.png + _l4         → psd_door_end_top_left_1_1_l4.png ✅
apg_door_*_middle.png        （模组与素材都没有）→ 退回 mtr jar 那张中性图（与 3 号线同）
```

### 3.4 吊板端头那个小灯座

`models/block/psd_top_{left,right}_end_lX.json` 里有一个静态 `light` 元素（`mtrlte:block/light_off`），
代表「灯座（灭）」。4/6 号线的这两个模型改成 `light_off_green`（`#00662A`），
否则绿色线路上会留一个暗橙色的灯座。1/2/3 号线保持 `light_off` 不动。

---

## 4. 四版同步方式

| 文件 | 同步 |
|---|---|
| `XiamenLine` / `XiamenDoorLights` / `XiamenTextures` / `XiamenPsdBlocks` / `RenderPsdDoor` / `RenderPsdApgDoor` / `XiamenClient` | 四份**逐字节相同**（改动前后都本来相同，直接复制） |
| `XiamenObjects` | 只同步注释；实现保持各版本自己的 API 差异（1.19.2 用 `FabricItemGroupBuilder`、1.19.4 用 `builder(Identifier)`、1.20.1 用 `ITEM_GROUP` registry / `AbstractBlock.Settings.create()`） |
| `assets/**`、`lang/**` | 四份逐字节相同 |
| `data/mtrlte/loot_tables/**` | 只有 v1.20.1 有（与 `_l3` 现状一致） |

---

## 5. 验证

- **资源自检**（`temp/validate-resources.js`）：四版本共 394 个 JSON 全部可解析；
  14 个新方块 id 的 blockstate 全在；`_l4` / `_l6` 每条 306 个模型引用**全部命中**（无悬空 parent / 纹理）；
  每线 40 张贴图 + 6 个图标；v1.20.1 每线 6 个掉落表。
- **贴图解析回放**（`temp/replay-remap.js`，离线复刻 `XiamenTextures#remap`）：
  L4/L6 的 4 个门灯 id 全部解析到 `*_green.png`；18 个门扇/玻璃 id 解析到 `_l4` / `_l6` 变体；
  只有 `apg_door_*_middle` 这类模组与素材都没有的图退回 MTR jar（与 3 号线完全一致）。
- **字节码核对**（`javap` 打进 jar 的类）：`XiamenLine.L4/L6` 存在且有 `doorLight()`；
  `XiamenDoorLights.colorFor(class_2248, class_2338)` 存在，`RenderPsdDoor#render` 里
  `invokestatic colorFor:(Lnet/minecraft/class_2248;Lnet/minecraft/class_2338;)`，即新入口真的在用。
- **运行时表**（`java -cp <jar> LineTable.java`）：`L4 suffix=_l4 number=4 doorLight=green`、
  `L6 suffix=_l6 number=6 doorLight=green`，`id(psd_door_xiamen)` 分别为 `…_l4` / `…_l6`。
- **构建**：见下表（`-q`，仅既有 4~6 条警告，无新增）。

| 版本 | jar | 大小 | md5 | jar 内 `_l4` / `_l6` 条目 | `XiamenLine.class` |
|---|---|---|---|---|---|
| `v1.20.1` | `lte-1.0.36.jar` | 2,888,816 B | `12b99982cca7bd2685f00913fa36eb1a` | 117 / 117 | ✅ |
| `v1.19.4` | `lte-1.0.0.jar` | 2,878,229 B | `3e4bf87adbfd82741c50a2b424b8139f` | 111 / 111 | ✅ |
| `v1.19.2` | `lte-1.0.0.jar` | 2,886,228 B | `5d5ced1dcb07450e8dce0d1e438e365b` | 111 / 111 | ✅ |
| `v1.18.2` | `lte-1.0.0.jar` | 2,886,088 B | `0089313abcf2f32fedba1c82316e9ba5` | 111 / 111 | ✅ |

四个版本 `gradle clean build --no-daemon -q` 全部 `exit=0`。
（v1.20.1 比其它三版多 6 条 = 6 个掉落表；1.18.2/1.19.2/1.19.4 本来就没有 `data/` 目录。）

> 备注：本次构建时另一个进程也在同一仓库里跑 gradle（改 `bind/BindUnbind*`），
> 出现过一次 `remapJar` / `remapSourcesJar` 的「文件被占用」失败。上面的结果是在确认没有
> 其它 JVM 之后**串行重跑**得到的，四条都干净通过。未提交 git。

