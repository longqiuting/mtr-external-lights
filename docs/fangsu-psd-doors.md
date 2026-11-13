# 方速屏蔽门搬进 LTE（14 个方块，不依赖方速）

素材来源：资源包 `厦门风格物件包` 的 `assets/fangsu/`。这一份记录「搬了什么、怎么驱动、哪些地方是取舍」。

## 1. 搬了什么

| 方块 id | 来源描述 | 类型 | 模型高 |
| --- | --- | --- | --- |
| `fangsu_psd_brtduanmen_left` / `_right` | `duanmen/brtduanmen.json` | 端门（旋转） | 3 格 |
| `fangsu_psd_mtrduanmen_left` / `_right` | `duanmen/mtrduanmen.json` | 端门（旋转） | 1.5625 格 |
| `fangsu_psd_drlduanmen_left` / `_right` | `duanmen/drlduanmen.json` | 端门（旋转） | 1.125 格 |
| `fangsu_psd_screendoor_low_left` / `_right` | `screendoor/xiamen/low.json` | 滑动门 | 1.25 格（含 0.1 悬空） |
| `fangsu_psd_shlowduanmen_left` / `_right` | `duanmen/shlowduanmen.json` | 端门（旋转） | 1.4375 格 |
| `fangsu_psd_shhighduanmen_left` / `_right` | `duanmen/shhighduanmen.json` | 端门（旋转） | 2 格 |
| `fangsu_psd_screendoor_high_left` / `_right` | `screendoor/xiamen/high.json` | 滑动门 | 3 格 |

资源目录（全部随 jar）：

```
assets/mtrlte/fangsu_psd/duanmen/*.json|obj|mtl
assets/mtrlte/fangsu_psd/screendoor/xiamen/*.json|obj|mtl
assets/mtrlte/textures/fangsu_psd/duanmen/*.png
assets/mtrlte/textures/fangsu_psd/screendoor/xiamen/*.png
```

描述文件里的 `"fangsu:..."` 在复制时改写成 `"mtrlte:fangsu_psd/..."`；`.obj` / `.mtl` 原样不动；
贴图必须放在 `textures/` 下（`RenderLayer.getEntityCutout(id)` 直接按 `assets/<ns>/<path>` 取文件，
不会自动补前缀），所以贴图路径统一是 `textures/fangsu_psd/<dir>/<map_Kd>`。

源素材只有 `door.png` / `door_low.png` 是 1024×1024，其余 ≤ 256×256；**没有超过 1024 的贴图，不需要降采样**。

## 2. 驱动方式：继承 MTR 的 `BlockPSDAPGDoorBase`（不是自己 tick）

javap 走的是 3.6.4.2 生产 jar，链条是：

```
Train#scanDoors(world, …)
    …
  159: instanceof mtr/block/BlockPlatform        // 站台
  167: instanceof mtr/block/BlockPSDAPGBase      // ← 屏蔽门：只要是子类就算
  181: openDoors(world, block, pos, index)

TrainServer#openDoors(world, block, pos, dwell)
    0: block instanceof BlockPSDAPGDoorBase                 // 门扇方块
   10: for (i = -1; i <= 1; i++) { pos.up(i) }              // y-1 / y / y+1 三格都扫
   49: block instanceof BlockPSDAPGDoorBase
   57: be instanceof BlockPSDAPGDoorBase$TileEntityPSDAPGDoorBase
   65: blockstate.unlocked == true
   80: target = clamp(doorValue * 64, 0, 32)
  105: be.setOpen(target)
  129: Utilities.scheduleBlockTick(world, pos, block, dwell)  // 到点触发 Block#tick
```

`BlockPSDAPGDoorBase#tick`（javap）：`if (unlocked && be instanceof TileEntityPSDAPGDoorBase) be.setOpen(0)` ——
这就是「发车自动关门」，不需要我们再写计时。

所以：

* **开门判定** = MTR 自己的列车停稳逻辑（`doorValue` 从 0 涨到 1，`setOpen` 随之写到 32）；
* **关门判定** = `openDoors` 里 `scheduleBlockTick(..., dwell)` 到期后的 `tick`；
* **不需要**自己扫列车 / 读 `Train#getDoorValue()`，也就没有「服务端 tick 每格扫一遍」的开销；
* 顺带拿到：`onPlaced` 自动补 y+1 段、`unlocked` 锁定、方块实体同步（`syncData` → 原版
  `BlockEntityUpdateS2CPacket`，见 `BlockEntityClientSerializableMapper`）、破坏时上下段一起拆。

**没有继承的部分**：几何、碰撞、右键。父类那套固定几何（`RenderPSDAPGDoor` 的盒子 + `mtr:` 贴图）完全不用，
blockstate 是「只有粒子」的空壳（`mtrlte:block/fangsu_psd_particle_only`），画面全由 BER 画 OBJ。

### 必踩的一个坑：父类的自毁判断

`BlockPSDAPGDoorBase#getStateForNeighborUpdate`（javap）里有一条：

```
if (direction == IBlock.getSideDirection(state) && !neighborState.isOf(this)) return Blocks.AIR;
```

它是给 MTR 的整列放置写的（`PsdAPGItem` 会按邻居把 `SIDE` 算成 left/middle/right/single，整列同一个方块）。
方速的门是玩家一扇一扇摆的，**左右两扇本来就是两个不同 id 的方块**，旁边摆另一扇就会被判成
「该消失」→ 门一放下、邻居一更新就变空气。所以 `FangsuPsdDoorBlock` 重写了这个方法，
**只保留「上下段必须成对」**这一条。

## 3. 渲染

* 一个方块实体类型 `mtrlte:fangsu_psd_door` 覆盖 14 个方块，变体从方块实例反查
  （`state.getBlock() instanceof FangsuPsdDoorBlock → def()`），和厦门屏蔽门同一套思路。
* `RawObjParser#materials(objId, …)`（本次新增）把 OBJ **按 `usemtl` 拆成多份 `RawMesh`**，
  每份的贴图从同目录 `.mtl` 的 `map_Kd` 解析 —— `RawMesh` 只带一张贴图，而 `brtduanmen.obj`
  的门扇有三层材质（`dkg.png` 门体 / `ltg.png` 灯带 / `blk.png` 黑框），单贴图入口会丢层。
  贴图名以 `ltg.png` 结尾的那份，四边形在注册期标成 `fullBright`（灯带自发光）。
* 再按 `o` 组拆成「静态（门框 `main_*`）」和「动（门扇 `door_*` / `left|right`）」。
* 上半段方块**不画**（`HALF == UPPER` 直接 return），整扇门由下半段一次画完 —— 和 MTR 的
  `RenderPSDAPGDoor` 同一个做法，否则两段各画一次会重合成 z-fighting。3 格高的变体（BRT、厦门高）
  从下半段一直画到 3 格，视觉上不受「方块只有 2 格」的限制。
* 朝向：`FACING` → 俯视顺时针角（north 0 / east 90 / south 180 / west 270），绕方块中心
  `(0.5, ·, 0.5)` 转 `RotationAxis.NEGATIVE_Y`（与 `eyecandy` / `dep_on_wall` 同一套约定）。
  门扇的旋转/平移压在朝向那一层**里面**，所以是在方块自己的坐标系里开合。

### 门扇变换

| 类型 | 变换 | 参数来源 |
| --- | --- | --- |
| 端门（旋转） | `translate(pivot) → rotateY(doorAngle × p) → translate(-pivot)` | `doorPos`（格，相对方块中心）→ 模型单位 `×16+8`；`doorAngle` 弧度（右扇 −1 / 左扇 +1） |
| 滑动门（平移） | `translate(step × p, 0, 0)` | `step` 格（左 0.95 / 右 −0.95） |

`p ∈ [0,1]` 是开合进度。矩阵是在 `RotationAxis.POSITIVE_Y` 上做的（右手定则，和 `RawObjParser` 里
烘 `rotation` 用的公式同一套），所以正负号与素材的语义一致。

### 动画插值

`FangsuPsdDoorEntity#progress(time)`：

```
target = clamp(getOpenValue() / 32, 0, 1)                // 来自 MTR 的 setOpen
dt     = clamp((world.getTime() + tickDelta) - lastSample, 0, 4)
step   = dt / MOVE_TICKS          // MOVE_TICKS = 15 tick（0.75 s 走完全程）
progress += sign(target - progress) * step，越界夹到 target
```

* 用 `world.getTime() + tickDelta` 而不是「每帧固定步长」：MTR 自己的 `getOpen(float)` 是
  `openClient += 0.95 * partialTicks` 每帧推进，**与帧率挂钩**（60 fps ≈ 0.56 s，144 fps ≈ 0.23 s，
  看起来就是瞬开）。这里按时间推进，60 / 144 / 240 fps 与掉帧时速度一致。
* 第一帧（刚加载区块）直接对齐目标值，不会从关到开慢慢动一次。
* `dt` 夹到 4 tick：区块重载 / 卡顿时不会一帧跳完整程。

## 4. 碰撞

`shape.close` / `shape`（1/16 格，可超过 16 —— BRT 与厦门高就是 y 到 48）在**注册期**烘成
`8` 个 `VoxelShape`（4 朝向 × 上下两段）：按 90° 转 `(x,z) → (16−z, x)`、裁到本格 0..16、
再取本段那一层 y（下段 0..16，上段 16..32 下移 16）。

* 关门：挡人；开门（`TileEntityPSDAPGDoorBase#isOpen()`）：碰撞空集。
* 轮廓（选中框）永远用关门的形状 —— 开门后也还点得到这块门。
* `getCullingShape` 返回空集：门只有 2~3 单位厚，不这样做邻居的贴面会被判成「看不见」而整面不画，
  墙上会出现一个方块大小的洞。

素材里的 `shape` 是方速自己写的粗略数（`drlduanmen` 1.125 格高、`shlowduanmen` 1.4375 格高，
`shape` 却都写 25；z 也统一偏了约 13/16 格），这里**逐字照搬** —— 方速就是按这些数判碰撞的，
照搬行为才一致，没有替素材「修」几何。

## 5. 手动开关 / 锁定

| 操作 | 效果 |
| --- | --- |
| MTR 刷子右键 | 手动开 / 关（上下两段一起写 `setOpen`） |
| 潜行 + MTR 刷子 | 锁定 / 解锁（父类原语义：锁定后列车不再开这扇门） |

自动逻辑优先：列车在站时 MTR 每个 tick 都会 `setOpen(doorValue*64)`，手动结果会被立刻覆盖。

手动打开后如果没有列车在场，就不会有 `scheduleBlockTick` 在排队，门会一直开着 —— 这是搭建时想要的，
也意味着「手动开」不是「开一次就自动关」。

## 6. 物品 / 页签

* 14 个 `BlockItem`，进「厦门风格物件」页签（`mtrlte:core`，排在 eyecandy 之后）。
* 物品图标是 **3D 方块模型**：按每个 `o` 组的包围盒生成 `<id>_icon.json`（每个组一个 cuboid，
  贴图用该组里面数最多的那个材质，UV 用该组的 uv 范围），`models/item/<id>.json` 只是
  `parent` 到它。3 格高的模型在图标里等比缩到 ≤ 28 单位（方块模型允许的 −16..32 区间）。
* 图标 / 粒子贴图走的是**方块图集**（模型管线会 stitch），而 1024×1024 基本占满整张图集、
  stitch 会失败（物品图标变紫黑）。所以 `downscale-icons.js` 给两张 1024 的贴图各生成一份
  256×256 的盒式降采样 `<name>_icon.png`，图标模型与粒子模型只引用这一份；
  BER 仍然用原尺寸（`getEntityCutout` 直绑贴图，不进图集）。

## 7. 已知限制

1. **3 格高的变体（`brtduanmen`、`screendoor_high`）最上一格没有碰撞也没占方块。**
   方块本体是 MTR 的 2 格柱（`HALF` 只有 lower/upper），模型从下半段画到 3 格是纯视觉。
   站着的玩家仍被下面 2 格挡住（跳也跳不过去），但第三格那一段穿得过去。
   要把第三格做实，需要重写 `onPlaced` / `onBreak` / 掉落条件改用 3 段柱，风险比收益大，没做。
2. **滑动门只搬了门扇，没搬方速的固定玻璃 / 门框（`glass` 那套 `auto` 规则）。**
   所以 `screendoor_low/high` 单放一格是「一扇薄板」，两格并排时两扇会各滑 ±0.95 格互相穿插。
   配合方速的玻璃方块才是标准的藏门效果。`step` 的正负按素材原值，若游戏里看着滑反了取反即可
   （`FangsuPsdDef.loadScreendoor` 一处）。
3. **端门的开合角度按素材的 `doorAngle`（±1 弧度 ≈ ±57°）**，不是 90°。素材给的就是这个数。
4. `shape` 的 z 偏移与高度误差照搬素材（见「碰撞」一节），所以碰撞箱和画出来的门扇会差
   最多一格厚的距离；在门洞里看不出来，单独摆在空地上能看出来。
5. 方块设置（硬度 / 声音 / 地图色）沿用 MTR 的 `BlockPSDAPGDoorBase()` 无参构造，
   没有另外定制。
6. 刷子锁定只影响「列车开不开」，不影响手动开关。

## 8. 校验

```bash
# 资源齐备（描述 → obj → mtl → png → blockstate → item model → loot → lang）
node v1.20.1/tools/psd/verify-resources.js v1.20.1/src/main/resources

# 连 jar 一起核对（94 个被引用的资源文件 + 6 个类文件逐个在 jar 里确认）
node v1.20.1/tools/psd/verify-resources.js v1.20.1/src/main/resources \
     v1.20.1/build/devlibs/lte-1.0.47-dev.jar
```

脚本还会检查：描述里的 `subModels.main/door` 与 `subModel` 必须真的出现在 `.obj` 的 `o` 组里
（名字打错就会整扇门透明，这是最容易静默失败的地方），以及每个 `usemtl` 在 `.mtl` 里都有 `map_Kd`。

工具脚本（都在 `v1.20.1/tools/psd/`）：

| 脚本 | 作用 |
| --- | --- |
| `inspect-obj.js` | 打印 obj 的 `o` 组 / 材质 / 包围盒 / uv 范围（选 pivot、核对高度时用） |
| `asset-report.js` | png 尺寸（判断要不要降采样）+ mtl 的材质 → 贴图对应 |
| `import-assets.js` | 把素材包复制进 jar 并改写命名空间 |
| `gen-resources.js` | 生成 14 个 blockstate / 3D 图标模型 / 掉落表 / lang |
| `downscale-icons.js` | 给 >256 的贴图生成 256 的图标副本（图集能 stitch），并把图标模型指过去 |
| `verify-resources.js` | 上面的验收脚本 |
