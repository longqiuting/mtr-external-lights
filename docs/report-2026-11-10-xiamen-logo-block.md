# 厦门地铁标志方块（`mtrlte:xiamen_logo`）

日期：2026-11-10 · 目标版本：v1.20.1 only · 参照物：MTR 原版 `mtr:logo`

## 1. MTR 原版 logo 方块是怎么做的（反编译证据）

反编译工具 CFR 0.152（`D:\MC-MTR-JS\temp\cfr.jar`），对象
`libs/MTR-fabric-1.20.1-3.6.4.2.jar`（= 实例里装的那份 3.6.4.2）。

### 1.1 它是一个普通方块，没有 BlockEntity、没有 BER

`mtr/Blocks.class`（CFR 原样输出）：

```java
public static final RegistryObject<class_2248> LOGO = new RegistryObject(() -> new class_2248(
        class_4970.class_2251.method_9637()    // AbstractBlock.Settings.create()
                .method_29292()                // requiresTool()
                .method_9632(2.0f)             // hardness(2.0f)
                .method_9631(state -> 10)));   // luminance(state -> 10)
```

- 类型就是 `net.minecraft.block.Block`（`class_2248`），**不是** `BlockEntityProvider`。
- `mtr/BlockEntityTypes.class` 里没有 logo 条目 —— 所以也没有 BER。
- 方法名对照（1.20.1 yarn）：`method_9637 = Settings.create`、`method_29292 = requiresTool`、
  `method_9632 = hardness`、`method_9631 = luminance`。

### 1.2 贴图：静态 PNG，整张 0–16 UV 铺满，不切图、不依赖字体

- `assets/mtr/textures/block/sign/logo.png`（**128×128**，RGBA，四角 alpha=0）。
- `assets/mtr/models/block/logo.json`：

```json
{
  "parent": "mtr:block/block_overlay_glowing_base",
  "textures": { "particle": "mtr:block/metal", "side": "mtr:block/sign/logo" }
}
```

- `assets/mtr/blockstates/logo.json` 只有一个变体 `""` —— **没有 facing**。
- `assets/mtr/models/item/logo.json` = `{"parent": "mtr:block/logo"}`。
- 掉落：`data/mtr/loot_tables/blocks/logo.json`（普通 `survives_explosion` 掉落自己）。

### 1.3 几何：满格立方体 + 0.05 外扩的无阴影 logo 覆盖层

`mtr:block/block_overlay_glowing_base`：

```json
elements: [
  { "from": [0,0,0], "to": [16,16,16],  faces: 六面 #particle（metal） },
  { "from": [-0.05,0,-0.05], "to": [16.05,16,16.05], "shade": false,
    faces: { east/west/north/south: #side（logo）, uv 0–16 } }
]
```

即：**四面包一层「metal 实体 + 0.05 外扩的 logo 面」**。因为 logo 贴图外面是透明的，
透过它看到的是 metal 底板；而那层 quad 是 `shade: false`，不受方块朝向明暗影响，
配合 `luminance = 10` 就得到「微微发亮的标志牌」的效果。1.20.1 没有 emissive render type
（那是 1.21.2+ 的 `emissiveLighting`），MTR 也没有 BER 去画发光 —— 亮度就是靠这两样。

### 1.4 渲染层：cutout

`mtr/MTRClient.class` 里：

```java
RegistryClient.registerBlockRenderType(RenderLayer.getCutout(), Blocks.LOGO.get());
```

（`RenderLayer.method_23581()` = `getCutout`，`method_23583()` = `getTranslucent`。）

**结论：MTR 的 logo 是「普通方块 + 纯 JSON 模型」，和字体/动态文本无关，
换成任何静态 PNG 都能直接照抄这套做法。**

## 2. 本模组选的做法

`mtrlte:xiamen_logo` → `XiamenLogoBlock`（`v1.20.1/.../xiamen/XiamenLogoBlock.java`）

**照抄的部分**

| MTR 原版 | 本方块 |
| --- | --- |
| 普通 `Block`，无 BE / 无 BER | 同样，纯 JSON 模型 |
| `requiresTool().hardness(2.0f)` | 一样 |
| `luminance(state -> 10)` | 一样（10） |
| metal 底板 + `shade:false` 的 logo 覆盖层（外扩 0.05） | 一样（底板 `mtr:block/metal`，覆盖层 `-0.05`） |
| `RenderLayer.getCutout()` | `XiamenClient` 里同样挂 cutout |
| 掉落：`survives_explosion` 掉自己 | `data/mtrlte/loot_tables/blocks/xiamen_logo.json` |

**故意不一样的部分（按需求）**

- MTR 是**满格立方体**、无 facing；本方块是**贴墙的 2px 薄牌 + `facing` 四向**：
  - `facing` = 标志面向的方向，背后（`facing.getOpposite()`）必须是实心面；
  - 支撑块被拆 → `getStateForNeighborUpdate` 返回 air，自己掉；
  - 放置时标志正对玩家（`getPlacementState`）+ `canPlaceAt` 校验。
- 贴图换成玩家的厦门地铁 logo（600×600 → 缩到 **512×512** 的 2 次幂，避免非 POT 走 mipmap 时
  被 stitcher 处理得难看），仍然整张 0–16 UV 铺满，透明留白照用。
- 额外 `nonOpaque()`（薄板不挡光）与 `sounds(METAL)`。

### 朝向约定（和 MTR 的 psd 系列同一套）

模型按 **y=0 = facing=west** 写：板在 x∈[0,2]，标志面朝 −X，覆盖层在 x∈[-0.05,0]。
blockstate：west→0°、north→90°、east→180°、south→270°（与 `mtr:blockstates/psd_glass.json`
以及本模组 `xiamen_psd_glass.json` 完全一致）。碰撞/描边形状按同一套 y 旋转推出四份：

| facing | 板体形状 |
| --- | --- |
| west | x∈[0,2] |
| north | z∈[0,2] |
| east | x∈[14,16] |
| south | z∈[14,16] |

## 3. 改动的文件

新增：

- `v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/XiamenLogoBlock.java`
- `v1.20.1/src/main/resources/assets/mtrlte/blockstates/xiamen_logo.json`
- `v1.20.1/src/main/resources/assets/mtrlte/models/block/xiamen_logo.json`
- `v1.20.1/src/main/resources/assets/mtrlte/models/item/xiamen_logo.json`
- `v1.20.1/src/main/resources/assets/mtrlte/textures/block/xiamen_logo.png`（512×512，透明背景）
- `v1.20.1/src/main/resources/data/mtrlte/loot_tables/blocks/xiamen_logo.json`

修改：

- `xiamen/XiamenObjects.java`：`LOGO_ID`、`logo()`、`registerLogo()`、页签 `entries.add(logo)`、类注释
- `xiamen/XiamenClient.java`：`putBlock(logo, RenderLayer.getCutout())`
- `assets/mtrlte/lang/zh_cn.json`：`block.mtrlte.xiamen_logo` = 厦门地铁标志
- `assets/mtrlte/lang/en_us.json`：`block.mtrlte.xiamen_logo` = Xiamen Metro Logo

素材来源：`resourcepacks/厦门地铁一号线车辆/assets/mtr/textures/block/sign/logo.png`
（600×600 RGBA，四角 alpha=0）。600 不是 2 的幂，直接进图集走 mipmap 时容易糊，
所以用 System.Drawing 高质量双三次缩到 **512×512**（保持比例、保留透明），命名 `xiamen_logo.png`。

## 4. 构建

```
cd D:\MC-MTR-JS\mtr-headlight\v1.20.1
gradle-8.14 build          # JAVA_HOME = C:\Program Files\Java\jdk-21.0.10
→ BUILD SUCCESSFUL
```

产物 `v1.20.1/build/libs/lte-1.0.0.jar`（884 746 B），已确认包含：

```
assets/mtrlte/blockstates/xiamen_logo.json
assets/mtrlte/models/block/xiamen_logo.json
assets/mtrlte/models/item/xiamen_logo.json
assets/mtrlte/textures/block/xiamen_logo.png
data/mtrlte/loot_tables/blocks/xiamen_logo.json
dev/js/mtrheadlight/xiamen/XiamenLogoBlock.class
```

已拷到（sha256 三份一致 `5aaafa8d…0e0fb1`）：

- `C:\Users\AD\Desktop\lte-1.0.0+1.20.1.jar`
- `…\versions\1.20.1-Fabric 0.19.3\mods\lte-1.0.0+1.20.1.jar`

未提交 git。
