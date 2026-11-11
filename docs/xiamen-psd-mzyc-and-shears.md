# 屏蔽门联动：mzycBetterMTR 的石斧界面 + 我们吊板的剪刀右键

版本：lte-1.0.28（1.20.1）。MTR `3.6.4.2`、mzycBetterMTR `1.38.1201`、TianjinMetro `1.1.3`。

## A. mzycBetterMTR（`SmoothLift` / `smooth_lift`）石斧打开屏蔽门配置

入口全部是 Fabric 事件，**没有 mixin 进方块类**。

| 位置 | 内容 |
|---|---|
| `smooth.lift.client.SmoothLiftClient#onInitializeClient`（第 3 个 `UseBlockCallback`） | 石斧右键屏蔽门 → `new PsdToneSetupScreen(pos)` |
| `smooth.lift.SmoothLift#onInitialize`（服务端 `UseBlockCallback`） | 同样的条件返回 `ActionResult.CONSUME`，把这次右键吞掉（服务端不会放方块/破坏/交互） |
| `smooth.lift.SmoothLift#isPsdDoor(BlockState)` | 识别判据 |
| `smooth.lift.client.PsdToneSetupScreen#<init>(BlockPos)` | 界面本体，标题「选择屏蔽门开关门提示音」 |

### 识别方式：方块 id 前缀，不是 instanceof、不是 tag

```java
// SmoothLift#isPsdDoor
String path = registryPathOf(state);          // Registries.BLOCK.getId(state.getBlock()).getPath()
return path.startsWith("psd_door") || path.startsWith("apg_door");
```

`PsdDoorTracker#isPsdFamily` 用更宽的 `path.startsWith("psd_") || path.startsWith("apg_")`。
**两个函数都不看 namespace**，只看 registry path。

所以能命中的：`mtr:psd_door`、`mtr:psd_door_2`、`mtr:apg_door`、`tianjin:psd_door_tianjin`（TianjinMetro 的方块 id 也是 `psd_door_*` 开头）、
**以及本模组的 `mtrlte:psd_door_xiamen` / `mtrlte:psd_glass_xiamen` / `mtrlte:psd_end_xiamen` / `mtrlte:psd_top_xiamen` /
`mtrlte:apg_door_xiamen` / `mtrlte:apg_glass_xiamen` / `mtrlte:apg_end_xiamen`**（2026-11-11 已按方案 1 改名，见下）。
命不中的（历史 id，已不存在）：`xiamen_psd_*` / `xiamen_apg_*`。

### 石斧判断：原版 `Items.STONE_AXE`，写死 item，没有 tag

```java
// SmoothLiftClient 第 3 个 UseBlockCallback
if (!world.isClient() || hand != Hand.MAIN_HAND) return ActionResult.PASS;
if (!player.getMainHandStack().isOf(Items.STONE_AXE)) return ActionResult.PASS;   // class_1802.field_8062
BlockPos pos = hitResult.getBlockPos();
if (!SmoothLift.isPsdDoor(world.getBlockState(pos))) return ActionResult.PASS;
MinecraftClient.getInstance().setScreen(new PsdToneSetupScreen(pos));
return ActionResult.SUCCESS;
```

- `class_1802.field_8062` = `Items.STONE_AXE`（javap 行号对账：intermediary 788 = named `STONE_AXE` 788）。
- 只认主手、只在客户端；返回 `SUCCESS` 只用于客户端，不产生服务端交互。
- 同一套代码还对 扶梯 / 升降机地板 / 闸机 做同样的事（另外 4 个回调，分别开 `EscalatorSpeedScreen` / `LiftToneSetupScreen` / `ZhajiToneSetupScreen` / `TrainSoundScreen`）。

### 打开界面时传了什么

只有 **方块坐标 `BlockPos`**，没有 BlockEntity、没有 World、没有朝向：

```java
public PsdToneSetupScreen(BlockPos pos) {          // 第 132 行
    super(Text.literal("选择屏蔽门开关门提示音"));
    this.pos = pos;
    this.runKey       = PsdDoorTracker.runKeyOf(level, pos);        // 客户端自己扫周围方块
    this.runAnchor    = PsdDoorTracker.runAnchorOf(level, pos);
    this.runPlatformId= PsdDoorTracker.platformIdOf(level, pos);
}
```

`runKeyOf` 会从该坐标向两侧 flood-fill 找同一串门，再用 `MtrDwellAccess.platformIdAt(x,y,z)` 反查 MTR 站台 id，把「一串门」映射成「一个站台」作为配置身份。**全程只读 blockstate + MTR 的线路数据，不读 BlockEntity，也不做任何 MTR 类型的 instanceof**。

### 我们的门能否被识别：能（2026-11-11 改名后）

- 继承链本来就没问题：`Door extends BlockPSDAPGDoorBase`、`Glass extends BlockPSDAPGGlassBase`、`Top extends BlockPSDTop`，
  但 MZYC 根本不看类型，只看 id 字符串 —— 所以只要 id 前缀对上就通。
- 已采用方案 1（改名，未发布所以没有旧存档兼容负担）：

  | 旧 id | 新 id |
  |---|---|
  | `mtrlte:xiamen_psd_door` | `mtrlte:psd_door_xiamen` |
  | `mtrlte:xiamen_psd_glass` | `mtrlte:psd_glass_xiamen` |
  | `mtrlte:xiamen_psd_end` | `mtrlte:psd_end_xiamen` |
  | `mtrlte:xiamen_psd_top` | `mtrlte:psd_top_xiamen` |
  | `mtrlte:xiamen_apg_door` | `mtrlte:apg_door_xiamen` |
  | `mtrlte:xiamen_apg_glass` | `mtrlte:apg_glass_xiamen` |
  | `mtrlte:xiamen_apg_end` | `mtrlte:apg_end_xiamen` |

  同步改了：`XiamenObjects` 的 7 个方块 id + 4 个 BlockEntity 类型 id、`blockstates/`、`models/item/`、
  `loot_tables/blocks/` 的文件名，以及 `block.mtrlte.*` 语言键。
- 被否掉的另外两条路（保留记录）：2. mixin `SmoothLift#isPsdDoor` / `PsdDoorTracker#isPsdFamily`（软依赖 mixin 更脆）；
  3. 给作者提 issue 放宽成 `path.contains("psd_door")`（不由我们控制）。
- `isPsdFamily` 也用于 PSD 提示音追踪（`PsdDoorTracker` 的 run 扫描），现在**石斧界面和整套「屏蔽门提示音」一起生效**。

## B. MTR 吊板的剪刀右键

### 字节码依据（MTR 3.6.4.2）

`javap -c -p -cp <mtrjar> mtr.block.BlockPSDTop`，`method_9534`（= `onUse`）：

```
 19: getstatic   mtr/Items.BRUSH : Lmtr/RegistryObject;
 22: invokevirtual mtr/RegistryObject.get
 28: aastore
 29: dup
 30: iconst_1
 31: getstatic   net/minecraft/class_1802.field_8868   // = Items.SHEARS
 34: aastore
 35: invokestatic mtr/block/IBlock.checkHoldingItem(World, PlayerEntity, Consumer, Runnable, Item...)
```

`field_8868` 与 named jar 的 `Items.SHEARS`（javap 第 945 行）行号一致 ⇒ **剪刀**。

分支语义（源码 / CFR 对账）：

- 手持 `mtr.Items.BRUSH`：`state.cycle(ARROW_DIRECTION)` + 沿 facing 左右各 `propagate(..., 1)`（箭头方向 0..3）。
- 手持 `Items.SHEARS`：`shouldBePersistent = (PERSISTENT == NONE)`，然后 `setState()` 自己 + 左右各一格：
  - 开：看**下方方块**——`BlockPSDDoor` → `ARROW`、`BlockPSDGlass` → `ROUTE`、`BlockPSDGlassEnd` → `BLANK`；
  - 关：`EnumPersistent.NONE`。
  - ⇒ 剪刀是 **NONE ↔（ARROW/ROUTE/BLANK）二态切换**，不是四态轮转；具体值由下方是什么自动定。
- `IBlock.checkHoldingItem` 只在服务端执行回调（`world.field_9236` = `isClient` 取反判断），客户端返回 `SUCCESS`。
- 渲染侧 `mtr.render.RenderPSDTop` 也 `getstatic BlockPSDTop.PERSISTENT` 并按 `NONE / ARROW / ROUTE` 分支绘制，所以切换有可见效果（站名/箭头面板/线路条带）。

### 我们原来的 bug

`dev.js.mtrheadlight.xiamen.XiamenPsdBlocks$Top#onUse` 自己重写了 `use`（必须重写，MTR 的 `setState` 用 `instanceof BlockPSDDoor` 判下层方块，对我们不成立），但第二个 item 写成了 **`Items.BARRIER`**：

```java
}, null, brush, Items.BARRIER);   // ← 剪刀永远进不来
```

于是 `checkHoldingItem` 只匹配 `mtr:brush`，剪刀右键落到"手里没相关物品"分支 → `CONSUME`，什么都不发生。

修复：`Items.BARRIER` → `Items.SHEARS`，并把注释改成真实语义。修复后我们的 `onUse` 字节码与 MTR 一致：

```
37: getstatic net/minecraft/class_1802.field_8868   // Items.SHEARS
41: invokestatic mtr/block/IBlock.checkHoldingItem
```

`PERSISTENT` 属性本来就随 `BlockPSDTop#appendProperties` 继承注册，`assets/mtrlte/blockstates/psd_top_xiamen.json`
也已经有 `persistent: "arrow|route|blank"` 的 16 个变体（与 MTR 自己的 `psd_top.json` 结构一致），模型文件齐全，**不需要补 blockstate**。

## 产物

`v1.20.1/build/libs/lte-1.0.29.jar`（md5 `b33dfce3a164c5b8f32f9d3d69b54ecc`）
→ 桌面 `lte-1.0.29.jar`、实例 `C:\Users\AD\Desktop\PCL\.minecraft\versions\1.20.1-Fabric 0.19.3\mods\lte-1.0.29.jar`（三份 md5 一致）。
构建命令：Gradle **8.14**（loom 1.11.8 要求 ≥8.14，仓库 wrapper 仍写 8.8，未改动）：
`D:/MC-MTR-JS/temp/gradle/gradle-8.14/bin/gradle -p v1.20.1 clean build --no-daemon` → BUILD SUCCESSFUL。
