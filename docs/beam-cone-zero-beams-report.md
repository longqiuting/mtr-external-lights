# 光柱 beams=0 与 coneQuads 口径（2026-11-09）

代码：`common/.../ProtoBeamCone.java`、`common/.../DebugProbe.java`、`common/.../RailWeather.java`、
`v1.20.1/.../RainSpray.java`。只构建 `v1.20.1`。

## 1. 结论：拦在「距离闸门」，而且是写死的 64

真实日志（`PCL/.../1.20.1-Fabric 0.19.3/logs/latest.log`）里 `ProtoBeamCone` 自己那行才是判据：

```
[lte] cone: calls=666 trains=6660 models=2331 noModel=4329 noSlot=0 drawn=0
            quads=0 probe=0 near=0 far=6660 shaders=false peak=0.160 depthTex=4 volume=active
```

- `near=0 / far=6660`：这一窗口**每一盏灯的 apex 都超出 64 格**，`emit()` 在
  `MAX_BEAM_DISTANCE` 上 `return false`。
- 同一份日志里 `near` 不为 0 的窗口 `beams` 是 8/5/9/3 —— `beams` 并不是恒 0，而是
  「玩家离所有车都超过 64 格」的那些窗口恒 0。
- 硬编码值与配置冲突：`cfg.beamDistance`（用户配置 128）由 `BeamGlow` 使用，
  `ProtoBeamCone` 却写死 64。**64~128 格之间：光带画、光柱不画**，正是「灯位解析出来了、
  光柱一条没有」。

修法：`emit()` 的距离闸门改用逐车 `Effective.beamDistance`（钳到 16–256 块），
和 `BeamGlow` 同源；壳层锥那条路以前**完全没有**距离闸门（远处车照样堆四边形），现在共用同一道。

## 2. coneQuads 为什么永远是 0（口径问题）

1.20.1 上 `ConeVolumeRenderer.ready()` 返回 true（`volume=active`）时，光柱走 raymarch：
只有一个覆盖代理 + 一条全屏 march，**根本不会产生四边形**。所以 `coneQuads=0` 与
「画没画」无关；旧的计数器还把 `/mtr_lte debug cone` 的探针四边形混在同一个数里
（探针开时 `coneQuads>0`，与车灯无关）。

现在：`coneQuads` 只算车灯壳层锥，`probeQuads` 只算探针，另外新增 `volBeams`
（交给 raymarch 的光束数）—— 三种"画了"各有各的数。

## 3. 逐闸门计数（g1..g8）

`ProtoBeamCone` 每道闸门累加 `GATE[1..8]`，`DebugProbe` 按 5 秒窗口打印差值
（render 在 g1/g2 上整帧早退，它自己的 5 秒块不会打印，所以必须走单调累计 + 差值）：

| 闸 | 含义 |
|----|------|
| g1 | `cfg.enabled == false`（帧级 return） |
| g2 | `!beamCone && !beamConeAnySiding()`（帧级 return） |
| g3 | 没有可读的动态车型（`modelOf == null`） |
| g4 | 解析不出头/尾灯位（`layout == null` 或 head/tail 为 null） |
| g5 | 这条侧线关了光柱（`eff.beamCone == false`） |
| g6 | 位姿为空（`cars <= 0` / 切线退化为零向量） |
| g7 | 光束 apex 超出距离闸门 |
| g8 | 名额满（`MAX_BEAM_CANDIDATES=64`）或 length/radius/alpha 退化 |

`dmin` = 本窗口最近一条光束 apex 到眼睛的距离，`range` = 生效闸门。`dmin > range` 时
`beams=0` 是「射程内没有车」，不是管道坏了。

## 4. 修完后期望的日志

```
[lte] debug: coneCalls=~600 coneQuads=0 probeQuads=0 volBeams=~40 spraySpawned=0
             beams=4 slots=14 dmin=37.2 range=128 g1=0 g2=0 g3=2 g4=0 g5=0 g6=0 g7=6 g8=0
[lte] cone: calls=... trains=... models=... noModel=... noSlot=0 drawn=...
            quads=0 probeQuads=0 volBeams=.. probe=0 near=.. dmin=37.2 range=128
            g1=0 ... g7=6 g8=0 shaders=false peak=0.160 depthTex=4 volume=active
```

判读：`beams>0` 且 g1..g8 全 0 = 正常；`beams=0` 时看哪一个 g 不为 0。
`coneQuads=0 + volBeams>0` 在 1.20.1 上是正常的（raymarch 没有四边形）。

## 5. 雨天水雾

`RainSpray.reason=dry` 的唯一来源是 `RailWeather.raining(world)` 为 false，即
`!world.isRaining() || world.getRainGradient(1f) <= 0.2`。`/weather rain` 会让
`isRaining()` 为 true、`rainGradient` 在 1 秒内到 1.0，两个条件都满足，因此 `reason` 会从
`dry` 变成 `slow(...)` / `chance` / `roofed` / `far(...)` / `ok`。

为了在游戏里一眼确认，`[lte] spray:` 行新增 `weather=`（`isRaining()` 本体）和
`grad=`（雨量渐变）：

```
[lte] spray: trains=0 spawned=0 skipped=0 raining=true weather=true grad=1.0 particles=ALL
```

`weather=false grad=0.00` 才是真的没下雨。
