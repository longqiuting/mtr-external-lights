# 光柱对照模式（beamConeSource = MODEL | GLOW）（2026-11-09）

代码：`common/.../BeamGeom.java`（新）、`common/.../BeamGlow.java`、`common/.../ProtoBeamCone.java`、
`common/.../HeadlightConfig.java`、`common/.../DebugProbe.java`、`v1.20.1/.../Commands.java`。
只构建 `v1.20.1`。

## 1. 这个开关回答什么问题

现状：光带（`BeamGlow`）用户能看到而且位置正确；光柱（`ProtoBeamCone`）"看不到绑在车上"。
日志已经证明 `beams=6 / volBeams>0 / dmin=70 < range=128` —— 也就是**光束确实提交给渲染器了**，
所以只剩两种可能：

1. **绑定错了**：`VehicleLights` 解析出来的灯位（车型组、内缩、左右灯对、局部坐标 → 世界坐标）
   把光柱放到了车外/地下/背面；
2. **光柱这条路画不出来**：几何是对的，但 raymarch / 壳层 / 深度 / GL 状态让真实光束不可见
   （探针锥能看见只证明探针那条固定几何能画）。

`beamConeSource` 就是这两个假设的**对照实验**：让光柱改用一条**已经确认看得见**的光带的几何。

| | `MODEL`（默认，原有行为） | `GLOW`（对照） |
|---|---|---|
| 灯位来源 | `VehicleLights` 车型灯位槽（组名匹配 + 几何校验 + 实测内缩） | **完全不读** `VehicleLights` / `DynamicTrainModel` |
| base | 车端面 + `FACE_GAP - inset` + 实测横向偏移 | `BeamGeom.of(车端, 切线, headOffset/tailOffset, …)` —— 光带同一个工厂 |
| y | `槽位 height` | `BeamGlow.startHeight()` + `beamLift + beamHead/TailHeight` —— 光带同一个方法 |
| 方向 | 车节切线（逐车） | `pose.forward()` / `pose.backward()` —— 光带同一个值 |
| 长度 | `max(4, beamHeadLength)` / `max(2, beamTailLength)` | `beamHeadLength` / `beamTailLength`（光带原值） |
| 颜色 | `eff.beamHeadColor` / `eff.beamTailColor` | 同上（同一个字段） |
| 每端盏数 | 槽位里的左右灯对，按 `1/sqrt(n)` 分摊 alpha | 1 盏（光带本身每端只有一条片） |
| 附加闸门 | 无（停库/自动关灯的车也照发） | 照抄光带的 `DepartureState.departed` + `HeadlightSwitch.isLit`（g9） |
| 渲染路径 | 不变 | **不变**（raymarch 可用时仍走 raymarch，否则壳层） |
| 半角 / 半径 | 13°，头 ×0.6 / 尾 ×0.5 | 同一个 `coneRadius()` |

## 2. 判读

```
GLOW 下光柱出现，且灯口和光带重合  → 几何没问题，问题在 MODEL 的灯位绑定
GLOW 下仍然什么都没有              → 几何已经和一条看得见的光带逐位相同，问题在光柱渲染路径
```

进一步细分（日志里 `volume=` 那一位）：

- `volume=active` —— 两种模式都走同一条 raymarch。GLOW 仍看不到时，先 `/mtr_lte cone occl off`
  强制走加色壳层：壳层出现 = raymarch 坏了；壳层也没有 = 两条路共用的部分（事件挂载 /
  `GlowCompat` / 深度状态）坏了。
- `volume=shaderpack / shader-missing / failed(…)` —— 本来就走壳层，GLOW 看不到就是壳层几何或
  事件的问题，和灯位无关。

## 3. 命令

```
/mtr_lte debug coneSource status        # 当前来源 + 诊断行里的 source=
/mtr_lte debug coneSource glow          # 光柱改用光带的几何（实时生效，写 config/mtrlte.json）
/mtr_lte debug coneSource model         # 回到车型灯位（默认）
/mtr_lte debug coneSource <model|glow>  # 同上，带参数形式（非法值回落 MODEL 并回显）
```

切换会立刻清空"已打印"的列车集合，下一帧重新逐车打印对照行。

## 4. 日志

5 秒一行的 `[lte] cone:` 与 `[lte] debug:` 都多了 `source=MODEL|GLOW`，`g1..g8` 后多了 `g9`：

```
[lte] cone: calls=... trains=... models=0 noModel=0 noSlot=0 drawn=2
            quads=0 probeQuads=0 volBeams=4 probe=0 near=2 dmin=37.1
            range=128 g1=0 g2=0 g3=0 g4=0 g5=0 g6=0 g7=0 g8=0 g9=3
            source=GLOW shaders=false peak=0.160 depthTex=4 volume=active
```

- `GLOW` 下 `models=0 / noModel=0 / noSlot=0` 是**正常的**：这条路根本不解析车型。
- `g9` 只在 GLOW 下可能非 0：那些车光带本来就没画（停库 / 自动关灯），所以光柱也不画。

`GLOW` 下每列车**首帧一次**（按 `train.id` 去重，最多 512 条后清空重来）：

```
[lte] coneSource glow: train=123 base=(1024.35, 68.22, -311.90) axis=(0.998, -0.012, 0.061) len=16.00
                        tailBase=(1007.12, 68.20, -310.86) tailLen=4.00
```

`base` 就是光带第一排中心线顶点的位置（同一个 `BeamGlow.startHeight` + 同一个 `BeamGeom`），
`axis` 是 `pose.forward()`，`len` 是 `beamHeadLength`。拿它和光带灯口对一眼即可。

## 5. 同源是怎么保证的（不是"两边各算一遍"）

- `BeamGeom.of(railPoint, dir, offset, length, height, rgb)`：**唯一**算 `base/axis/length/height/colour`
  的地方。`BeamGlow` 的每帧两束光和 `ProtoBeamCone` 的 GLOW 两束光都调它。
- `BeamGlow.startHeight(world, origin)`：**唯一**算灯口 y 的地方（高度图 → 偏离超过 3 格 / 读不到时
  退回轨道点）。`BeamGlow.beam()` 的 `k = 0` 采样和 `BeamGeom.apex()` 都调它。
- `ProtoBeamCone.coneRadius(length, head)`：两个源共用的末端半径。
- 因此 GLOW 的 apex 与光带 `t = 0` 的中心线顶点在所有分量上相等，不是"接近"。

## 6. 构建与产物

```
bash tools/build-all.sh v1.20.1
BUILD SUCCESSFUL in 12s
```

- `v1.20.1/build/libs/lte-1.0.0.jar` → `~/Desktop/lte-1.0.0+1.20.1.jar`（494607 字节）
- 同一份 jar 已复制到实例 `PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.0+1.20.1.jar`
- sha256（libs / 桌面 / 实例三份一致）：`a6b30e7ae3c0f0082ab8f541bc418cd6deb5fa4ba0ea67a831ded876724d59d9`

未启动游戏，对比由用户自己做。

## 7. 已知限制（有意为之）

- GLOW 只有"每端一盏"：光带本身就是一条片，没有左右灯对；这是对照实验，不是最终效果。
- 光带的横向宽度 `beamWidth` 不参与光柱半径（半径仍按 13° 半角 × 长度），两者只在**轴线与灯口**
  上要求重合。
- GLOW 不改变 alpha / 半径 / 渲染路径，只换几何来源 —— 否则"看不见"的结论会被第二个变量污染。
