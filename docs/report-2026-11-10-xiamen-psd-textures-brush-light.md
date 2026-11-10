# 屏蔽门：厦门贴图 / 门扇贴图与「变实心」/ 吊板刷子 / 门灯 / 物品图标

日期：2026-11-10 晚 · 目标版本：Minecraft 1.20.1（只构建 `v1.20.1`）· 未提交 git
产物：`lte-1.0.19.jar`（BUILD SUCCESSFUL）→ 桌面 + `PCL/.../1.20.1-Fabric 0.19.3/mods/`

---

## 0. 一句话结论

| # | 问题 | 结论 | 根因 |
| --- | --- | --- | --- |
| 1 | 贴图没用上厦门包 | 已换 | 模组里 `psd_glass_*` / `psd_top*` 是 **MTR 原版文件**（md5 与 MTR jar 完全一致）；门扇用的是资源包里的**旧格式整图** `psd_door_top_1.png`（1:1 铺满一个面），不是 MTR 3.6 用的盒子 UV 图集 |
| 2 | 吊板贴图错 + 刷子不能切方向 | 已修 | ① 贴图是模组里一份自制/过期的 `psd_top.png`；② 刷子判断写成了**原版** `minecraft:brush`，MTR 用的是 `mtr.Items.BRUSH`（`mtr:brush`），所以右键永远进不到分支里 |
| 3 | 门扇没贴图 / 开一次变实心 | 已修 | 门扇用旧格式整图 + UV 拉伸 → 大面积白底看起来就是「一块白板/实心方块」；另外「暂停使用」牌和门灯原来是**单面 quad 画在门板内部 z=7**（被 2px 门板挡住）且门灯贴图常亮；现已按 MTR 的盒 UV + 立方体位置重画。方块模型本身一直是 `minecraft:block/air`（见 §4 校验），不存在「回退成立方体」 |
| 4 | 物品图标 | 已换 | 三张图已拷进 `assets/mtrlte/textures/item/`，item model 改 `item/generated` |
| +1 | 吊板贴图（绫洛） | 已换 | `psd_top.png` / `psd_top_edge.png` 换成绫洛市导视系统包的版本；模型里 MTR 那套**逐面 UV** 原样保留 |
| +2 | 门灯 | 已补 | MTR 的门灯不在吊板方块里、也没有 `EnumDoorLight` 属性，**是 `RenderPSDAPGDoor` 用 BE 的 open 值画的一个 1×1×1 小方块** |

---

## 1. 证据（javap，MTR-fabric-1.20.1-3.6.4.2）

反编译来源：`D:\MC-MTR-JS\temp\mtrjar`（MTR jar 解包）。
工具：`"C:/Program Files/Java/jdk-21.0.10/bin/javap.exe" -p -c -constants`。

### 1.1 刷子切「线路指示方向」——问题 2 的根因

`mtr/block/BlockPSDTop.class` → `use`：

```text
0: aload_2                                  // world
... invokedynamic  #0:accept:(BlockPSDTop, World, BlockPos, BlockState)
13: iconst_2 anewarray Item
19: getstatic  mtr/Items.BRUSH:RegistryObject
22: invokevirtual RegistryObject.get:()Object   ← 第一个允许的物品 = MTR 的刷子
35: getstatic  net/minecraft/class_1802.field_8868   (= Items.BARRIER)
35: invokestatic mtr/block/IBlock.checkHoldingItem:(World, Player, Consumer, Runnable, Item...)ActionResult
```

`mtr/block/BlockPSDTop.class` → `lambda$use$2`（真正干活的分支）：

```text
2:  getstatic mtr/Items.BRUSH ; RegistryObject.get()
8:  if_acmpne 76                             ← item == mtr:brush ?
14: getstatic ARROW_DIRECTION:IntProperty
17: invokevirtual BlockState.cycle:(Property)BlockState     ← state.cycle(ARROW_DIRECTION)
23: invokevirtual World.setBlockState:(BlockPos,BlockState)Z
40: invokevirtual Direction.rotateYCounterclockwise:()Direction
47: invokevirtual propagate:(World,BlockPos,Direction,Property,int)   // 1
63: invokevirtual Direction.rotateYClockwise:()Direction
70: invokevirtual propagate:(World,BlockPos,Direction,Property,int)   // 1
--- 非刷子分支（栅栏 = 锁定/解锁整列）
76: getstatic PERSISTENT:EnumProperty<EnumPersistent>
83: getstatic BlockPSDTop$EnumPersistent.NONE
101: invokevirtual setState:(World,BlockPos,boolean)V
```

**属性名（全部在 `BlockPSDTop` 上）**：

```text
public static final BooleanProperty                    AIR_LEFT;
public static final BooleanProperty                    AIR_RIGHT;
public static final IntProperty                        ARROW_DIRECTION;   // 0..3，刷子 cycle 的就是它
public static final EnumProperty<EnumPersistent>       PERSISTENT;        // NONE / ARROW / ROUTE / BLANK
```

我们原来的 `XiamenPsdBlocks.Top.onUse` 写的是 `Items.BRUSH`（yarn 的 `net.minecraft.item.Items.BRUSH`，
= **原版刷子** minecraft:brush）。MTR 的刷子是 `mtr:brush`，两者不是同一个物品，所以
`checkHoldingItem` 直接返回 PASS、什么也不做 —— 这就是「刷子切不了方向」的全部原因。
现在改成 `mtr.Items.BRUSH.get()`，并把同一实例同时用于 `checkHoldingItem(...)` 和 `item == brush` 判断。

### 1.2 门灯：画在哪、什么颜色、状态从哪来

`mtr/render/RenderPSDAPGDoor.class` → `render`：

```text
 200: iload 11 ; ifeq 274                     ← if (half == UPPER)  只在**上段**画
 205: invokevirtual isDoorLightOn:(TileEntityPSDAPGDoorBase)Z
216: ldc "mtr:textures/block/light_%s.png"     ← light_on.png / light_off.png
243: iconst_0
249: getstatic RenderTrains$QueuedRenderLayer.LIGHT      ← 亮 = 全亮层
255: getstatic RenderTrains$QueuedRenderLayer.EXTERIOR   ← 灭 = 普通环境光
258: ... scheduleRender(...)                     ← 模型由 lambda #1 画
```

模型（`static {}`，`new ModelSingleCube(texW, texH, x, y, z, w, h, d)`）：

```text
new ModelSingleCube(16,16,  0,-1,5, 1,1,1) -> MODEL_PSD_LIGHT_LEFT
new ModelSingleCube(16,16, 15,-1,5, 1,1,1) -> MODEL_PSD_LIGHT_RIGHT
```

`ModelSingleCube` 内部 `addBox(x-8, y-16, z-8, …)`，MTR 的 `lambda$render$0` 是
`translate(x+0.5, y, z+0.5)` + `rotateY(-facing.asRotation())` + `rotateX(180°)`
（rotateX(180) 把 y、z 取反，x 不翻）。换算到本模组的局部坐标（+z 朝外、y 向上）：

| 部件 | x | y | z |
| --- | --- | --- | --- |
| 门灯 LEFT 扇 | -8..-7 | 16..17 | 2..3 |
| 门灯 RIGHT 扇 | 7..8 | 16..17 | 2..3 |
| 「暂停使用」牌（`sign/door_not_in_use.png`） | -3..3 | 4..10 | 2..3 |

y = 16..17 正好是上段门块顶上那 1px —— 也就是**吊板模型留空的那条缝**
（`psd_top_left_base` 的 elements 从 y=1 起，0..1 是空的），灯藏在这条缝里。

状态来源（`RenderPSDAPGDoor.isDoorLightOn`）：

```text
0: invokevirtual TileEntityPSDAPGDoorBase.getOpenValue:()I
6: ifgt 11        ; openValue <= 0  -> return false      （关门：灭）
12: bipush 32 ; if_icmplt 19  ; >= 32 -> return true      （全开：常亮）
19: System.currentTimeMillis() / 500L % 2L == 0L          （开合中：500ms 闪一次）
```

**不是** `EnumDoorLight`：`mtr.block.BlockPSDTop$EnumDoorLight`（ON/OFF/NONE）只被
`BlockPSDTop.class` 自己引用（`grep -rl EnumDoorLight` 只有它自己和枚举类），
`BlockPSDTop` 也**没有**这个属性字段，属于 MTR 的死代码。状态真源是方块实体的 `open` 值。

颜色：贴图 `light_on.png` / `light_off.png`，模组里已按 MTR 原图放进
`assets/mtrlte/textures/block/`（亮 = 橙色 #E8A33D 一类，灭 = 暗灰）。
容器贴图里的 `psd_side_light.png` 是玻璃板侧面的灯带，与门灯无关。

### 1.3 门扇贴图为什么是「盒子 UV」而不是一张整图

`RenderPSDAPGDoor.static{}`：

```text
new ModelSingleCube(36,18, 0,0,0, 16,16,2)  -> MODEL_PSD            // 宽 16 高 16 厚 2
new ModelSingleCube(20,18, 0,0,0,  8,16,2)  -> MODEL_PSD_END_LEFT_1 // 宽 8
new ModelSingleCube(20,18, 8,0,0,  8,16,2)  -> MODEL_PSD_END_RIGHT_1
new ModelSingleCube(20,18, 8,0,2,  8,16,2)  -> MODEL_PSD_END_LEFT_2   // z 靠里 2px
new ModelSingleCube(20,18, 0,0,2,  8,16,2)  -> MODEL_PSD_END_RIGHT_2
```

前两个参数就是 `2(d+w)` 与 `d+h`，即 box UV 的画布尺寸（36×18 / 20×18）。厦门资源包的图集正好是它的 16 倍：

| 文件 | 尺寸 | = |
| --- | --- | --- |
| `psd_door_top_left_1.png` / `_right_1` | 576×288 | 36×18 ×16 |
| `psd_door_bottom_left_1.png` / `_right_1` | 144×72 | 36×18 ×4 |
| `psd_door_end_top_{left,right}_{1,2}_1.png` | 320×288 | 20×18 ×16 |
| `psd_door_end_bottom_{left,right}_{1,2}_1.png` | 80×72 | 20×18 ×4 |

图集布局（已用肉眼核对）：**左半张 = 正面、右半张 = 背面**，右侧两条 2 单位竖条是门的窄边，
v∈[0,2] 那条是上下檐。所以面 UV：

```text
u: 0 ─ d ─ d+w ─ d+w+d ─ 2d+2w        v: 0 ─ d ─ d+h
   ↑右(+x)  ↑正面         ↑背面           ↑上下檐   ↑侧面整高
```

之前的实现把 `psd_door_top_1.png`（256×256 的旧格式整图）按 0..1 铺满一个面，
相当于把「带 32px 白边和色带的整张图」硬拉成门板 —— 大面积纯白 + 位置错位，
看起来就是「没贴图 / 一块白实心板」。

---

## 2. 改了什么

### 2.1 贴图（全部拷进模组，`mtrlte:` 命名空间，未压缩）

来源：`…/resourcepacks/厦门地铁一号线车辆/assets/mtr/textures/block/`
→ `v1.20.1/src/main/resources/assets/mtrlte/textures/block/`（文件名保持一致，方便回溯）

```text
psd_door_top_left_1.png            psd_door_top_right_1.png
psd_door_bottom_left_1.png         psd_door_bottom_right_1.png
psd_door_end_top_left_1_1.png      psd_door_end_top_right_1_1.png
psd_door_end_top_left_2_1.png      psd_door_end_top_right_2_1.png
psd_door_end_bottom_left_1_1.png   psd_door_end_bottom_right_1_1.png
psd_door_end_bottom_left_2_1.png   psd_door_end_bottom_right_2_1.png
psd_glass_top_1.png                psd_glass_top_back_1.png
psd_glass_bottom_1.png             psd_glass_bottom_back_1.png
psd_glass_diagonal_top_1.png       psd_glass_diagonal_top_back_1.png
psd_glass_diagonal_bottom_1.png    psd_glass_diagonal_bottom_back_1.png
```

来源：`…/resourcepacks/绫洛市导视系统/assets/mtr/textures/block/`

```text
psd_top.png            (332 B, 64×64)   ← 吊板正面面板 / 侧面横梁
psd_top_edge.png       (341 B, 64×64)   ← 吊板端头封板
```

删除（旧格式 / 模组自制，已无人引用）：

```text
textures/block/xiamen_psd_door_top.png  xiamen_psd_door_top_back.png
textures/block/xiamen_psd_door_bottom.png  xiamen_psd_door_bottom_back.png
textures/block/xiamen_psd_top.png
```

> 玻璃 / 端部**不需要改模型**：`models/block/psd_glass_*` 是 MTR 模型 JSON 的逐字副本
> （只把 `mtr:` 换成 `mtrlte:`），`front` / `back` 就指向 `psd_glass_*_1`。
> 之前「用的是 MTR 原版材质」只是因为这两个文件名下的**内容**是 MTR 的图
> （`psd_glass_top_1.png` md5 `b580459a…` = MTR jar 里的同名文件），换文件即换材质。

### 2.2 门扇渲染 `RenderPsdDoor.java`（重写）

* 改用**盒子 UV**：`PsdQuads.UvSet.box(w,h,d)` 按 Mc 的 box UV 生成六面矩形
  （`front=[d,d+w]`、`back=[d+w+d, 2d+2w]`、`right=[0,d]`、`left=[d+w,d+w+d]`、
  `top/bottom` 取 `v∈[0,d]`），除以 `2(d+w)` × `(d+h)`。
* 门板六面分别贴正面 / 背面 / 左右窄边 / 上下檐（`PsdQuads.plate`），不再是「一个 quad 拉一张整图」。
* 上段 / 下段、左扇 / 右扇各用对的图集；端部再用一对 `_1` / `_2`（副扇只滑 `open/2`，z 靠里 2px）。
* 门灯按 §1.2：上段才有，1×1×1 立方体，外上角，亮 → `MAX_LIGHT_COORDINATE`（对应 MTR 的 LIGHT 层），
  灭 → 跟随方块光照（对应 EXTERIOR 层）。
* 「暂停使用」牌改成 6×6×1 **立方体**（原来是一个单面 quad 画在 z=7 = 门板内部，永远被门板挡住）。

### 2.3 吊板 `XiamenPsdBlocks.Top`

* `onUse` 的刷子改为 `mtr.Items.BRUSH.get()`（见 §1.1）；`ARROW_DIRECTION` 的 cycle + 左右 propagate 逻辑原本就有，现在才真正能进到分支。
* 贴图侧：`models/block/psd_top_*` 是 MTR 原版 JSON 的副本，**逐面 UV 已经是对的**
  （主面板 `east/west/north/south` 各一组 uv，端头 `_end` 用 `#main`，`_edge` 用 `#particle=psd_top_edge`），
  这次只换了 `psd_top.png` / `psd_top_edge.png` 两个文件的**内容**。

### 2.4 物品图标

```text
C:\Users\AD\Desktop\amtr_1_psd_door_1.png       -> textures/item/psd_door_1.png      (64×64)
C:\Users\AD\Desktop\amtr_1_psd_glass_1.png      -> textures/item/psd_glass_1.png     (64×64)
C:\Users\AD\Desktop\amtr_1_psd_glass_end_1.png  -> textures/item/psd_glass_end_1.png (64×64)
```

`models/item/xiamen_psd_door.json` / `xiamen_psd_glass.json` / `xiamen_psd_end.json`
全部改成 `{"parent":"item/generated","textures":{"layer0":"mtrlte:item/…"}}`
（原来指向 `mtrlte:block/psd_glass_top_1` 这类 256×256 方块图，在物品栏里会被裁成怪样子）。

### 2.5 门扇 blockstate

`blockstates/xiamen_psd_door.json` 从 `"variants": {"": {...}}`（MTR 原写法）改成
**一条无条件 multipart**：

```json
{ "multipart": [ { "apply": { "model": "minecraft:block/air" } } ] }
```

无条件 part 对所有状态组合（facing 4 × side 2 × half 2 × end 2 × unlocked 2 = 64）都命中，
`minecraft:block/air` 在 1.20.1 的 jar 里确实存在（`assets/minecraft/models/block/air.json`，4 字节 `{}`），
所以不会再有任何组合落到 missing model。

---

## 3. 静态校验（不启动游戏）

```text
$ node tools/check-psd-assets.js  (对 build/libs/lte-1.0.19.jar 解包后的 assets/mtrlte)
blockstates/xiamen_psd_door.json       multipart=1
blockstates/xiamen_psd_end.json        multipart=64
blockstates/xiamen_psd_glass.json      variants=32
blockstates/xiamen_psd_top.json        multipart=40
--- resolved files: 89
OK: all mtrlte: model/texture refs resolve in the jar
```

另外枚举了吊板的 4×4×2×2×4 = 256 个状态组合，逐条套 multipart 的 `when`：

```text
top states: 256, without any model: 0
```

（玻璃 32 / 端部 64 / 吊板 40 条，与 MTR 自带 blockstate 的条目数完全相同 —— 这几份是 MTR 的逐字副本。）

构建：

```text
BUILD SUCCESSFUL in 15s   (clean build, v1.20.1)
build/libs/lte-1.0.19.jar  986,646 B
md5 6741bf218dc37f062ca3a074c78c2297  -> 桌面 / mods 两份一致
```

---

## 4. 逐条回答用户

1. **贴图没用上** → 玻璃 / 端部原来是 MTR 原图（md5 与 MTR jar 一致），门扇是旧格式整图；现在门 = 厦门盒 UV 图集（`psd_door_top/bottom_{left,right}_1`、`psd_door_end_*_{1,2}_1`），玻璃 / 端部 = 厦门 `psd_glass_*_1`，吊板 = 绫洛 `psd_top` / `psd_top_edge`。全部在 `mtrlte:` 下。
2. **吊板贴图错 + 刷子不能切方向** → 贴图换成绫洛两张（模型 per-face UV 本来就是 MTR 的，没动）；刷子根因是用品名判断错（`minecraft:brush` vs `mtr:brush`），已改为 `mtr.Items.BRUSH`。属性：`BlockPSDTop.ARROW_DIRECTION`（IntProperty，刷子 `cycle` 它并沿 `facing.rotateYClockwise/Counterclockwise` 各 propagate 1 格）、`BlockPSDTop.PERSISTENT`（`EnumPersistent.NONE/ARROW/ROUTE/BLANK`，栅栏右键切）、`AIR_LEFT` / `AIR_RIGHT`。
3. **门扇没贴图 / 开一次变实心** → 门扇改盒 UV 后不再有「白色整图拉伸」；门灯与锁定牌从小 quad 改成 MTR 同位置的立方体（原先把牌画在门板内部，被门板挡死）；blockstate 用无条件 multipart 全覆盖，模型就是 `minecraft:block/air`，没有父模型可丢。
4. **物品图标** → 三张图已拷入 `textures/item/`（不压缩），item model 全部 `item/generated`。
5. **门灯** → 见 §1.2：`RenderPSDAPGDoor` 内部、只在**上段**画、1×1×1 立方体、`light_on/off.png`、亮时 LIGHT 层全亮、状态来自 BE 的 `getOpenValue()`（0 灭 / 32 亮 / 中间 500ms 闪），`EnumDoorLight` 是死代码。

---

## 5. 仍未验证 / 风险

* 按要求**没有启动游戏**，以上都是 javap + 资源静态核对；门扇 UV 的左右镜像只能靠图集布局推断
  （图集左右两半本来就是同一张门的正/背面，镜像与否肉眼几乎不可分）。
* 「开一次变实心」的**直接复现**未拿到（用户上次会话日志里没有 mtrlte 的 model/texture 报错，
  也没有 BER 异常），所以根因判定为「旧整图 UV 拉伸 + 贴图内容错」，不是 blockstate 回退。
  若换包后仍出现，请抓 `logs/latest.log` 里 `mtrlte` 相关 WARN 或截图，再定位。
* 本次 jar 里同时带着同一工作区其它并行改动的代码（`ProtoBeamCone`、`SprayParticle`、`RenderHooks` 等），
  构建是整树构建，未提交 git。
