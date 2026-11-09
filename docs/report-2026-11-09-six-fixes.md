# 2026-11-09 实机六问修复报告（1.20.1 / LTE）

构建：`bash tools/build-all.sh v1.20.1` → **BUILD SUCCESSFUL**
产物：`v1.20.1/build/libs/lte-1.0.0.jar` → 桌面 `lte-1.0.0+1.20.1.jar`，同字节复制到实例
`PCL/.../1.20.1-Fabric 0.19.3/mods/`。

> 注意：18:28 另有一次构建（`ProtoBeamCone` / `VehicleLights` 正在被另一条线改）覆盖了产物，
> 现在的桌面 / 实例 jar 是 345 001 字节、sha256 `80ca942f…f1ff8b0`。已用 `javap` 确认它包含本次
> 六项修复（`CollisionSystem.isRiding` / `isPlayerRiding`、`RailBed.physical`、
> `RainSpray.particleMode` / `MINIMAL_PUFFS`）。本次实机跑的是我这条线自己的 343 927 字节构建。
> **未提交 git。**

## 1) 乘车被挤出 / 视角抖 —— `CollisionSystem`

- 真因：MTR 的乘客不是原版 vehicle，它自己用 `Train#ridingEntities` 记账，
  `player.hasVehicle()` 在车厢里通常是 false。于是车体推出照常执行，MTR 的座位每 tick 把人拉回
  去 —— 两边互相拉扯 = 被挤出去 + 视角抖。
- 改法：新增 `isRiding(Entity)`（每 tick 只问一次，缓存）→ 反射
  `TrainClient.class.getMethod("isPlayerRiding", PlayerEntity.class)`（懒加载 + 缓存，失败即
  false，与 `ServerCollision` 同一套做法）。`afterMove` 里一旦为真：**推出 / 车顶吸附 / 道床
  整套跳过**，只保留 `flight()` 的飞行伤害；`clampMovement` 同样提前返回（车顶竖直截断也不做）。
- 诊断行新增 `riding=`（本窗口因为乘车而跳过的 tick 数），用于实机确认豁免真的生效。

## 2) 侧线页点击崩溃 —— `ParameterScreen` + `ConfigWriteTarget`

- 真因确认：`BEAM_CONE`（光柱）是 **3 号开关**，而 `buildRows()` 在侧线页也生成了这一行；
  `buildRow → toggleLabel → target.readToggle(3)` 在侧线目标上抛
  `IllegalStateException: global-only row 3 on a siding screen`（页面一渲染就炸，不只是点击）。
- 改法：`buildRows()` 里光柱行改为 `if (isGlobal())` 才生成（水雾 / 内置车 / 重载本来就在全局
  分支）。行数由 `rows.size()` 推出，滚动与页高自动同步。
- 第二道防线：`ConfigWriteTarget.globalOnly()` 换成 `ignored(which)` —— 读返回中性值
  （`0.0` / `false`）、写与重置直接丢弃，并打 **debug 一行**。以后再有 stray row，最坏是「按了
  没反应」，不会崩客户端。

## 3) 部分「恢复默认」无效 —— `ParameterScreen`

- 真因：一行里可以有两个参数（左边开关 + 右边滑块），但行尾 ↺ 只调 `resetToggle`。开关本来就在
  默认值时（例如动态光源默认就是开），按 ↺ 看起来毫无反应，旁边的数值（灯亮度）也不会回默认。
- 改法：行 ↺ 现在**同时重置这一行的开关和配对数值**（`row.value >= 0` 时再调
  `resetValue`）。重绘仍走 `reinit()`，所以数值/滑块/（跟随）后缀立刻变。
- 落盘语义不变：屏幕上的修改只改内存中的 live config（立刻可见），
  **完成 / 保存并返回** 才写 `mtrlte.json`；取消 / 返回 回滚快照。侧线页的 ↺ 仍然是
  `字段 = null` + `prune()` 删空条目，写盘后 json 里就没有那个字段了。

## 4) 车尾光带没有柔和渐变 —— `BeamGlow`

- 新增 `TAIL_FADE_BLOCKS = 1.5`，车尾改为传入它（`beam()` 内仍钳到 `length/2`，4 格尾灯最多
  2 格渐入）。车尾依旧比车头（2.0 格）短，观感对比保留，但不再是硬切。

## 5) 最少粒子档看不到水雾 —— `RainSpray`

- 真因（1.20.1 字节码核对）：`WorldRenderer#spawnParticle` 对**非强制**粒子有两道硬闸 ——
  距相机 > 32 格丢弃、`ParticlesMode.MINIMAL` **全丢**（不是抽稀）。所以「最少」档一颗都出不来，
  而日志里 `spawned>0` 只是我们的计数器。
- 改法：每 tick 读 `client.options.getParticles().getValue()`；
  - 最小档：走 8 参重载 `world.addParticle(effect, force=true, …)` 强制造粒；**事件更少**
    （抽签概率 ×0.45）但**每次一整团**（3 颗带抖动的 `CLOUD` + 55% 概率一颗 `LARGE_SMOKE`
    当雾体）；
  - 华丽 / 适中：完全不变（1 颗 `CLOUD`、原概率），不会爆量。
- 诊断行加 `particles=<档位>`：`[lte] spray: … particles=MINIMAL`。

## 6) 远距离物品掉下轨道 —— `ServerTrackbed` + `RailBed`

- 三处一起改：
  1. **不再以玩家为几何中心**：每个实体用自己的位置查轨道（`nearestCell` 的锚就是它所在区块），
     边界只剩「区块已加载」。原先的「玩家 64 格内」闸门放宽到 128 格且只当 CPU 上限，
     车开走之后掉在身后的方块不会再被直接跳过。
  2. **`MAX_DROP` 只对 resting 生效**：旧代码对 `feet < 面 − 3` 一律拒绝，而终端速度 3.92 格/tick
     一次就能掉过 3 格 —— 高处 / 高速落下的方块因此整块穿过轨面。现在只要这一 tick 的起点在轨面
     之上（`crossed` 已证明），掉多深都接。
  3. **轨道宽度兜底**：`RailBed.HALF_WIDTH` 1.0 → **1.3**（2.6 格宽的床面），并用
     `Rail#railType` 剔除 `NONE` / `AIRPLANE_DUMMY` 这类没有实体轨面的占位数据。
- 限制（报告要求写明）：MTR 不暴露轨道模型宽度（`RailType` 只有 `speedLimit/color/…`，
  没有尺寸字段），所以这里是**按视觉宽度估的 2.6 格总宽（半宽 1.3）**，不是量出来的；离中心线
  1.3 格外仍判为轨外。若以后要精确，只能读资源包里的轨道模型宽度。

## 验证

离线（真实编译产物，非游戏）：

- `ConfigWriteTarget` 六组断言全过（`VERIFY-OK`）：侧线目标访问光柱/水雾/内置车/重载不抛异常且
  不改全局字段；全局每行 ↺ 回到 `defaults()`（含车尾高度、两个碰撞延长）；侧线 ↺ 只删本条覆盖、
  清空后条目被 prune；`resetAll` 同时清所有侧线；置 null 的覆盖不再出现在 `toJson()` 里。
  （harness：`%LOCALAPPDATA%\Temp\lte-verify\src\dev\js\mtrheadlight\VerifyConfig.java`，
  对 `v1.20.1/build/classes/java/main` 直接跑）
- 反射探针（MTR 3.6.4.2 + 1.20.1 intermediary jar）：
  `FOUND public final boolean mtr.data.Train.isPlayerRiding(net.minecraft.class_1657)` —— 乘车
  判定在游戏里拿得到。

实机（`tools/mc-test-once.sh`，一次一个客户端，`lte_beamtest` 存档，5G 堆，200s 上限）：

- 模组正常加载，无新增 crash-report，日志里没有我们自己的 WARN/ERROR。
- `[lte] collision: cars=4 segs=60 … riding=0`（新列已生效，碰撞路径每 tick 正常跑完）
- `[lte] collision(server): trains=23 cars=107 hits=0`、`[lte] trackbed: players=1 items=0`
- `[lte] spray: trains=1 spawned=32 skipped=63 raining=true particles=MINIMAL` —— 该实例正好是
  最少粒子档，水雾仍在生成（且已改为 force）。

## 只能实机人工确认的

1. **坐着车时不再抖 / 不被挤出**：需要上车站着看 `riding=1` 与画面（本次没进车厢）。
2. **侧线页点了不崩**：打开 `/mtr_lte` → 按侧线配置 → 点任一侧线；页面里已无光柱行。
3. **↺ 立刻看到数值变**：点「动态光源 | 车灯亮度」这类双参数行的 ↺，开关与数值应同时回默认。
4. **车尾渐变**：夜景下车尾光带起点应是柔和过渡。
5. **最少粒子档的水雾观感**（是否够「一层」、是否太亮）需要人眼定。
6. **远处掉落物**：把方块从行驶中的车上丢下去，回头应看到它躺在道床上（受上面「2.6 格」限制）。
