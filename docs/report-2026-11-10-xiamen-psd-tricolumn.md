# 厦门屏蔽门：按 MTR 真实结构重做（3 格高一列 / 门 2 列）

日期：2026-11-10 · 目标版本：1.20.1 · 未提交 git · 取代 `report-2026-11-10-xiamen-psd-door.md`

反编译来源：`D:\MC-MTR-JS\temp\mtrjar`（MTR 3.6.4.2 解包）+ `javap -p -c`。
工具：`C:\Program Files\Java\jdk-21.0.10\bin\javap`。

---

## 0. 结论表（一张表看完全部结构）

| 物品 | 宽（列） | 高 | 总格 | 每格内容 |
| --- | --- | --- | --- | --- |
| `PSD_APG_DOOR`（门） | 2（`side=left` / `side=right`） | 3 | 6 | y+0/y+1 = 门扇（`half=lower/upper`），y+2 = 吊板 |
| `PSD_APG_GLASS`（玻璃） | 1（`side_EXTENDED=single`） | 3 | 3 | y+0/y+1 = 玻璃，y+2 = 吊板 |
| `PSD_APG_GLASS_END`（端部） | 1（`SINGLE` + `touching_left/right`） | 3 | 3 | y+0/y+1 = 端部玻璃，y+2 = 吊板 |

> 玻璃**不是**「y+1 是玻璃」：玻璃本体就是 y+0、y+1 两格，第三格永远是吊板。
> 门扇本体 2 格高（`half` 只有 LOWER / UPPER 两个值）。

---

## 1. `HALF` / `SIDE` / `END` 的确切取值（问题 1）

### 1.1 `HALF` = 原版 `DoubleBlockHalf`，只有 LOWER / UPPER

`IBlock` 的静态初始化（`javap -p -c mtr/block/IBlock.class`）：

```
static {};
   0: getstatic  #337  // Field net/minecraft/class_2741.field_12533:Lnet/minecraft/class_2754;
   3: putstatic  #339  // Field HALF:Lnet/minecraft/class_2754;
```

`class_2741.field_12533` 就是 `net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF`；
`IBlock.HALF` 的泛型参数是 `class_2756` = `DoubleBlockHalf`，代码里比的是
`field_12607`(LOWER) / `field_12609`(UPPER)：

```
BlockDirectionalDoubleBlockBase.getStateForNeighborUpdate:
   HALF == field_12609(UPPER) && direction == UP && !newState.isOf(this) → AIR
   HALF == field_12607(LOWER) && direction == DOWN && !newState.isOf(this) → AIR
```

**所以 `HALF` 没有 MIDDLE。** `ITripleBlock` 也不是三层高度用的：

```
public interface mtr.block.ITripleBlock extends mtr.block.IBlock {
  public static final net.minecraft.class_2746 ODD;                 // 只有一个 BooleanProperty
  public static net.minecraft.class_2680 updateShape(...);
  public static void playerWillDestroy(...);
}
```

并且全 jar 里引用 `ITripleBlock` 的类只有 `BlockLiftDoorOdd` / `BlockLiftPanelBase`
（电梯），屏蔽门一族（`BlockPSDAPGBase` / `BlockPSDDoor` / `BlockPSDGlass` / `BlockPSDGlassEnd` /
`BlockPSDTop` / `ItemPSDAPGBase`）一个都没引用 —— 用 `grep -l ITripleBlock mtr/block/*.class` 核过。
真实的「三层」= **两段 `HALF` + 一个独立的 `BlockPSDTop` 方块**。

### 1.2 `SIDE`：两个同名属性，`SIDE` 只有 LEFT/RIGHT，`SIDE_EXTENDED` 有四个值

```
IBlock 静态初始化:
  SIDE_EXTENDED = EnumProperty.of("side", EnumSide.class);               // 无过滤
  SIDE          = EnumProperty.of("side", EnumSide.class, predicate);    // 带过滤
IBlock$EnumSide = { LEFT, RIGHT, MIDDLE, SINGLE }
```

- 门用 `SIDE`（`ItemPSDAPGBase` 里 `SIDE = (i == 0) ? LEFT : RIGHT`，`BlockPSDAPGDoorBase`
  只出现 `EnumSide.RIGHT`）；
- 玻璃用 `SIDE_EXTENDED`（`BlockPSDAPGGlassBase` 里出现 `LEFT/MIDDLE/RIGHT/SINGLE`
  四个常量 —— `connectGlass` 把一段玻璃修成 `LEFT … MIDDLE … RIGHT`，单独一块是 `SINGLE`）。

### 1.3 `END`：门自己的 bool，判的是「外侧是不是端部玻璃」

```
BlockPSDAPGDoorBase.END / UNLOCKED / TEMP = BooleanProperty.of("end" / "unlocked" / "temp")
BlockPSDAPGDoorBase.getStateForNeighborUpdate:
   boolean end = world.getBlockState(pos.offset(sideDirection(state).getOpposite()))
                        .getBlock() instanceof BlockPSDAPGGlassEndBase;
   return state.with(END, end);
IBlock.getSideDirection(state) = state.SIDE == LEFT ? facing.rotateYCounterclockwise() : facing.rotateYClockwise();
```

`BlockPSDAPGGlassEndBase` 侧有 `TOUCHING_LEFT` / `TOUCHING_RIGHT`
（类型 `EnumPSDAPGGlassEndSide`，值 `AIR` / `DOOR` / `NONE`），判据在 `getSideEnd`：

```
邻居 instanceof BlockPSDAPGDoorBase  → DOOR
邻居 instanceof BlockPSDAPGBase      → NONE
否则                                  → AIR
```

`BlockPSDAPGBase.getCollisionShape` 也印证了两段结构：
`isAPG() && HALF == UPPER ? 9 : 16`（APG 的上段只要 9px 高）。

---

## 2. `ItemPSDAPGBase` 到底放几格、怎么放（问题 2）

`javap -p -c mtr/item/ItemPSDAPGBase.class`（`useOnBlock`）：

```
width  = item.isDoor ? (type.isOdd ? 3 : 2) : 1;     // istore_2
height = type.isPSD  ? 3 : 2;                        // blocksNotReplaceable 的第二个参数
if (blocksNotReplaceable(ctx, width, height, block)) return FAIL;

BlockPos start = ctx.getBlockPos().offset(ctx.getSideHit());
Direction facing = ctx.getHorizontalDirection();     // 墙面法线

for (i = 0; i < width; i++) {                        // ← 宽是【外层】循环（iinc 6 at 322）
    BlockPos p = start.offset(facing.rotateYCounterclockwise(), i);
    for (j = 0; j < 2; j++) {                        // ← 高只有两层（iinc 8 at 285，边界 iconst_2）
        BlockState s = state.with(FACING, facing)
                            .with(HALF, j == 1 ? UPPER : LOWER)
                            .with(isDoor ? SIDE : SIDE_EXTENDED,
                                  isDoor ? (i == 0 ? LEFT : RIGHT) : SINGLE);
        world.setBlockState(p.up(j), s);
    }
    if (type.isPSD)
        world.setBlockState(p.up(2), BlockPSDTop.getActualState(world, p.up(2)));   // 吊板
}
stack.decrement(1);                                  // 整组只消耗 1 个物品
```

- **循环顺序：宽在外、高在内**；每列先 y+0、再 y+1，出内层循环后补 y+2 的吊板。
- 只有门是 2 列（`isOdd` 的电梯门是 3 列）；玻璃 / 端部 1 列。
- 放置前用 `blocksNotReplaceable(ctx, width, 3, block)` 检查整块 `width × 3`
  （用的是 `state.isReplaceable()`：`!state.method_45474()` 就返回 true 失败）。

---

## 3. `BlockPSDTop` 是不是自己占一格（问题 3）

是。它是**独立方块**（`extends BlockDirectionalMapper implements EntityBlockMapper, IBlock`），
由物品在 y+2 单独放下，属性与门/玻璃完全不共享：

```
BlockPSDTop: AIR_LEFT, AIR_RIGHT (BooleanProperty), ARROW_DIRECTION (IntProperty),
             PERSISTENT (EnumProperty<EnumPersistent{NONE,ARROW,ROUTE,BLANK}>)
             + BE `TileEntityPSDTop extends TileEntityRouteBase`（站牌/线路图用）
BlockPSDTop$EnumDoorLight = { ON, OFF, NONE }      // 门灯
形状：getVoxelShapeByDirection(0, PERSISTENT==NONE?0:7.5, 0, 16, 16, 6, facing)   // 6px 厚
BlockPSDTop.getActualState(world, pos) 读下方那格（门/玻璃/端部）算出 AIR_LEFT/AIR_RIGHT/PERSISTENT
```

也就是说吊板与下面的玻璃/门**没有 BE 关联**，`getActualState` 只是读邻居方块状态；
`EnumDoorLight` 只用于渲染门灯。本项目只用「独立方块 + 静态模型」，站牌/门灯那套没做。

---

## 4. `RenderPSDAPGDoor` 的上下分段贴图（问题 4）

`javap -p -c mtr/render/RenderPSDAPGDoor.class` 里贴图是拼字符串出来的：

```
String.format("mtr:textures/block/psd_door_%s_%s_%s.png",
              half == UPPER ? "top" : "bottom",        // ① 上下段
              side == RIGHT ? "right" : "left",        // ② 左扇/右扇
              type == PSD_2 ? "2" : "1")               // ③ 样式
// END == true 时改用：
String.format("mtr:textures/block/psd_door_end_%s_%s_2_%s.png", top|bottom, right|left, type)
```

关键点：**「top / bottom」是按 `HALF` 选的整格贴图，不是在一格内部上下各切 8px**。
资源侧也能对上（`assets/mtr/models/block/`）：

```
psd_glass_bottom_left_1.json → parent psd_glass_left_base, front = psd_glass_bottom_1
psd_glass_top_left_1.json    → parent psd_glass_left_base, front = psd_glass_top_1
psd_glass_left_base.json     → elements: from [2,0,0] to [4,16,16]（两者几何完全相同）
```

即：同一块 2px 板，下半格贴 `*_bottom_1`、上半格贴 `*_top_1`。
厦门资源包里拷过来的两张图实测都是 16×16 整面板（`xiamen_psd_door_top.png` 256×256、
带「排队候车 先下后上」橙条；`xiamen_psd_door_bottom.png` 64×64，只有左边框 + 底边 pavilion 橙条），
所以按「整格」贴完全成立。

滑动量：`TileEntityPSDAPGDoorBase.open ∈ [0, MAX_OPEN_VALUE=32]`，`getOpen(delta)` 返回
`openClient / 32`（0..1），渲染里 `translate(open * (side == RIGHT ? -1 : 1), 0, 0)`，
满开正好平移 1 格。

---

## 5. 实现（`dev.js.mtrheadlight.xiamen`）

| 文件 | 作用 |
| --- | --- |
| `XiamenPsdBaseBlock` | 公共基类：`FACING` / `HALF`(DoubleBlockHalf) / `SIDE` / `SIDE_EXTENDED` / `TOUCHING_*` / `UNLOCKED`，四向形状，整列拆除 |
| `XiamenPsdDoorBlock` | 门扇：`SIDE`(LEFT/RIGHT) + `OPEN` + `END` + `UNLOCKED` + `POWERED`，BE + ticker |
| `XiamenPsdGlassBlock` | 玻璃：`SIDE_EXTENDED`（LEFT/MIDDLE/RIGHT/SINGLE，按左右邻居自算） |
| `XiamenPsdEndBlock` | 端部：`TOUCHING_LEFT/RIGHT`（AIR/DOOR/NONE，`getSideEnd` 同款判定） |
| `XiamenPsdTopBlock` | 吊板：只有 `FACING`，6px 厚，无物品形态，拆它时手动掉下面那格的物品 |
| `XiamenPsdItem` | 一次放置整组（宽 × 3），只扣 1 个物品 |
| `XiamenPsdDoorEntity` | 客户端插值 `open` 0..1（MTR 的 `openClient/32` 等价物） |
| `XiamenPsdDoorRenderer` | 按 `HALF` 选 bottom/top 贴图、按 `END` 换端部贴图、按 `side` 反向滑 1 格 |

### 5.1 联动（像原版门 / 床）

- **放**：`XiamenPsdItem` 一次写 `width × 3`，门 6 格、玻璃/端部 3 格，只消耗 1 个物品。
- **拆**：`XiamenPsdBaseBlock.breakStructure` 从任意一格反推列底（`half=upper` → `down()`；
  吊板 → `down(2)`），把 3 格全清；门再把「对开的那一扇」一并清掉。
  用 `world.setBlockState(AIR, NOTIFY_ALL)` 而不是 `removeBlock`，所以只有玩家真正打掉的那一格掉落
  （和原版床一样，拆一格只掉一个）。
- **端部判定**：门的 `END` 在放置时按「外侧一格是不是 `xiamen_psd_end`」算，之后由
  `getStateForNeighborUpdate` 跟随邻居变化 —— 和 MTR 同一位置、同一判据。

### 5.2 开关（MTR 是列车开的，这里补两条）

- 右键 = **锁定 / 解锁**（MTR 的 `UNLOCKED` 语义，带 action bar 提示）；
- 潜行 + 右键 = **手动开 / 关**（仅已解锁时），落到 `OPEN` 上，两扇 × 两段一起动；
- 红石 = 电平**变化**时开 / 关（`POWERED` 锁存上一次电平，避免手动开的门被立刻弹回）；
- 开门时 `getCollisionShape` 对下段 / 上段都返回 `VoxelShapes.empty()`。

### 5.3 方块 id

- **保留**：`mtrlte:xiamen_psd_door`、`mtrlte:xiamen_psd_glass`、`mtrlte:xiamen_psd_end`
  （旧存档里的单块方块会读成 `half=lower, side=left, open=false` 的默认值，不崩，
  但不再是完整的一列 —— 需要重新放一次）。
- **新增**：`mtrlte:xiamen_psd_top`（吊板，**无物品形态**、无创造页签条目、无掉落表）。
- 其余不变：`mtrlte:xiamen_logo`、BE 类型 `mtrlte:xiamen_psd_door`。

### 5.4 贴图

| 用途 | 贴图 | 来源 |
| --- | --- | --- |
| 门上段 y+1 | `xiamen_psd_door_top` / `_back` | 已有（`psd_door_top_1`） |
| 门下段 y+0 | `xiamen_psd_door_bottom` / `_back` | 已有（`psd_door_bottom_1`） |
| 端部门 / 端部 | `xiamen_psd_end` / `_back` | 已有（`psd_door_end_top_1`） |
| 玻璃上段 | `xiamen_psd_glass` / `_back` | 已有 |
| 玻璃下段 | `xiamen_psd_glass_bottom` / `_back` | **新增**（`psd_glass_bottom_1` 拷贝） |
| 吊板 | `xiamen_psd_top` | **新生成**：MTR 的 `psd_top.png` 是纯白占位图（实测 64×64 100% 不透明纯白），
按同一色系画了拉丝金属 + 橙条 |

---

## 6. 验证

- `gradle -p v1.20.1 clean build` → **BUILD SUCCESSFUL**（jdeps 无缺类）。
- jar 内容核对：4 个 blockstate（门 256 / 玻璃 32 / 端部 72 / 吊板 4 个 variant，
  正好等于各自属性笛卡尔积）、7 个 block model、6 张贴图、`XiamenPsd*` 7 个 class。
- 部署：桌面 `lte-1.0.0+1.20.1.jar` 与 PCL 实例
  `Desktop\PCL\.minecraft\versions\1.20.1-Fabric 0.19.3\mods\lte-1.0.0+1.20.1.jar`，
  sha256 一致（拷前确认无 Minecraft 进程，只有 Gradle daemon）。
- **未启动游戏**，运行时行为（渲染、开合、联动拆除）未在实机验证。

## 7. 已知未做 / 差异

- MTR 的 `BlockPSDAPGGlassEndBase.getEndOutlineShape` 会给自由端补一块 4px 转角
  （往形状里 `combine`），本项目只保留 `TOUCHING_LEFT/RIGHT` 属性与判定，不补那块转角。
- `TEMP`（临时门）、`EnumDoorLight`（门灯）、`PERSISTENT`（站牌 / 线路图）未实现。
- `BlockPSDAPGGlassBase#useOn`（玻璃右键连成一片的手动修补）没有照抄 —— 改成放置后自动算链。
