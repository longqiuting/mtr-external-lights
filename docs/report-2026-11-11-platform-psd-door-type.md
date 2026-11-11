# 全高屏蔽门贴站台不切模型 —— 根因与修法（2026-11-11）

## 症状
`mtrlte:psd_door_xiamen`（高屏蔽门）贴在 `mtr:platform` 上，站台方块仍是 `platform_hk_1`
（无门型）的模型；`mtrlte:apg_door_xiamen`（半高门）贴上去就正常变 `platform_apg`。

## 根因：MTR 用 `instanceof` 判的是**具体壳类**，不是基类

`mtr.block.BlockPlatform#getActualState` 是 `private`，判定逻辑（`javap -p -c`，生产 jar 3.6.4.2 /
1.20.1，MTR 自己编译所以方法名没混淆）：

```
private net.minecraft.class_2680 getActualState(class_1922, class_2338, class_2680);
   0: aload_3  1: getstatic field_11177          // FACING = HorizontalFacingBlock.FACING
   4: invokestatic mtr/block/IBlock.getStatePropertySafe
  12: aload_1  13: aload_2
  14: invokevirtual class_2338.method_10084      // BlockPos.up()   ← 看的是**上方一格**
  17: invokeinterface class_1922.method_8320     // world.getBlockState(pos.up())
  24: invokevirtual class_2680.method_26204      // .getBlock()
  26: astore 6

  // ── 门型：三个 instanceof，比较的全是 MTR 自己的具体类 ──
  31: aload 6  33: instanceof mtr/block/BlockPSDDoor       ┐
  39: aload 6  41: instanceof mtr/block/BlockPSDGlass      ├→ 55: getstatic EnumDoorType.PSD
  47: aload 6  49: instanceof mtr/block/BlockPSDGlassEnd   ┘
  76: aload 6  78: instanceof mtr/block/BlockAPGDoor       ┐
  84: aload 6  86: instanceof mtr/block/BlockAPGGlass      ├→ 100: getstatic EnumDoorType.APG
  92: aload 6  94: instanceof mtr/block/BlockAPGGlassEnd   ┘
 121: getstatic EnumDoorType.NONE                        // 三个都不中 = 无门型

  // ── side（站台面上的方向箭头，纯装饰）：判据是**基类**，子类天然成立 ──
 126: aload 6 128: instanceof mtr/block/BlockPSDAPGDoorBase          // isDoor
 133: pos.up().offset(facing.rotateYCounterclockwise()) 的邻居 instanceof BlockPSDAPGDoorBase
 163: pos.up().offset(facing.rotateYClockwise())        的邻居 instanceof BlockPSDAPGDoorBase
 193: isDoor && cw  → side=2 | 209: isDoor && ccw → side=3
 225: cw           → side=1 + facing 取 cw 方的 | 249: ccw → side=4 | 273: side=0

 276: state.with(FACING, facing).with(DOOR_TYPE, doorType).with(SIDE, side)
```

关键三点：

1. **不是 `isOf(Blocks.PSD_DOOR)`** —— 是 `instanceof`，理论上子类能中；
2. 但比较的对象是 **MTR 的叶子类** `BlockPSDDoor / BlockPSDGlass / BlockPSDGlassEnd`
   （以及 APG 的三个），**不是**共同基类 `BlockPSDAPGBase`；
3. 看的位置是 `pos.up()`（`method_10084` 在 1.20.1 yarn 里确认是 `BlockPos.up()`），
   也就是门的下段那一格。

### 为什么 APG 正常、PSD 不正常

`v1.20.1/.../xiamen/XiamenPsdBlocks.java` 的继承关系（修复前）：

| 我们的方块 | 父类 | MTR 的三个 instanceof |
|---|---|---|
| `ApgDoor` | `mtr.block.BlockAPGDoor` | ✅ 中 |
| `ApgGlass` | `mtr.block.BlockAPGGlass` | ✅ 中 |
| `ApgEnd` | `mtr.block.BlockAPGGlassEnd` | ✅ 中 → `door_type=apg` → `platform_apg` |
| `Door` | `mtr.block.BlockPSDAPGDoorBase`（**上两层基类**） | ❌ 全不中 |
| `Glass` | `mtr.block.BlockPSDAPGGlassBase` | ❌ 全不中 |
| `GlassEnd` | `mtr.block.BlockPSDAPGGlassEndBase` | ❌ 全不中 → `door_type=none` → `platform_hk_1` |

即：APG 三个壳类当初就是照 MTR `BlockAPGDoor/BlockAPGGlass/BlockAPGGlassEnd` 写的（因为玻璃要拿
MTR 的 `RenderAPGGlass` BER），PSD 三个则停在基类上（玻璃/端部根本没有方块实体，是纯静态模型）。
同一条分支里 `facing` 也只在这两支被覆盖 —— 所以即使只补 `door_type`，facing 仍是站台自己的朝向。

## 修法：mixin 补 `getActualState`（不改继承链）

新增：

* `v1.20.1/src/main/java/dev/js/mtrheadlight/mixin/mtr/BlockPlatformDoorTypeMixin.java`
* `v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/XiamenPlatformDoorType.java`（等价实现 + 注释里的字节码）
* `HostProbe#mtrBlockPlatform()`、`MtrMixinPlugin` 里对应分支
* `mtrlte.mtr.mixins.json` 的 **`mixins`（common）** 列表 —— 站台 blockstate 在服务端也要算

注入点：

```
@Mixin(value = mtr.block.BlockPlatform.class, remap = false)
  @Inject(method = "getActualState", at = @At("HEAD"), cancellable = true, remap = false)
  // 描述符与目标一致：(BlockView, BlockPos, BlockState)BlockState
```

* 只接管「`pos.up()` 是本模组 `Door/Glass/GlassEnd`」这一种情况，其余（MTR 原版 PSD/APG、
  本模组 APG、无门站台）原方法照走 —— 行为与不装本模组时逐字节一致。
* 接管时一次算准三个属性：`door_type=psd`、`facing` 取门自己的朝向、`side` 按 MTR 的
  邻接规则重算（否则箭头方向会拿站台朝向去探邻格而算错）。
* `BlockPlatform$EnumDoorType` 是**包私有** `final class`，包外写不出
  `state.with(DOOR_TYPE, EnumDoorType.PSD)`。实现里把属性取成 raw `Property`，
  再从 `getValues()` 按枚举常量名（`PSD`）挑一个同一实例交给 `with` —— 泛型擦成
  `(Property, Comparable)`，运行期语义完全等价。
* `Properties.HORIZONTAL_FACING (class_2741.field_12481)` 与 MTR 用的
  `HorizontalFacingBlock.FACING (class_2383.field_11177)` 是**同一个实例**
  （`class_2383.<clinit>: getstatic class_2741.field_12481 → putstatic field_11177`），
  所以属性名能直接混用。

### 为什么不改父类
`BlockPSDDoor/BlockPSDGlass/BlockPSDGlassEnd` 都带 `style` 构造参数，`BlockPSDDoor` 还会
`createBlockEntity` 出 `TileEntityPSDDoor`（要额外注册方块实体类型，牵动渲染链路）；而 `side`
那段判据本来就是基类，我们天然满足 —— 缺的只有 `door_type` 一个属性。在判定点外面补一刀
影响面最小。

## 验证
* `gradle build` → **BUILD SUCCESSFUL**（`lte-1.0.31.jar`），mixin 类与 `mtrlte.mtr.mixins.json`
  都在 jar 里；`javap -v` 确认注解为 `@Mixin(mtr.block.BlockPlatform, remap=false)` +
  `@Inject(method="getActualState", at=HEAD, cancellable=true)`，方法与目标描述符一致
  （`class_1922 / class_2338 / class_2680`）。
* jar 已复制到桌面与 `PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods`（sha256 三者一致）。
* 进游戏验证（本轮未启动）：高门 → `platform_psd`；半高门 → `platform_apg`；拆门 → 回到
  `platform_hk_1`。

## 顺带修的编译阻塞（非本任务范围）
构建时工作区里 `XiamenClient` 正在被另一个会话改（`RenderPsdDoor/RenderPsdApgDoor` 改成继承
MTR 的 `RenderPSDAPGDoor`），`BlockEntityRendererRegistry.register(...)` 的
`BlockEntityType<TileEntityXiamenPSDDoor>` 与渲染器的 `BlockEntityRenderer<TileEntityPSDAPGDoorBase>`
泛型不一致，编译不过。按同文件里已有的 `RenderPSDTop` / `RenderAPGGlass` 做法补了一次
不检查的 `(BlockEntityRendererFactory)` 转型（两个门扇各一处）+ `BlockEntityRendererFactory`
import。若那边后续重写了这两行，以他们的写法为准。
