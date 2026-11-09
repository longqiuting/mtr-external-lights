# 光柱默认走壳层路径（beamConeVolume，2026-11-09）

代码：`common/.../ProtoBeamCone.java`、`common/.../BeamGeom.java`、`common/.../HeadlightConfig.java`、
`common/.../DebugProbe.java`、`v1.20.1/.../ConeVolumeRenderer.java`、`v1.20.1/.../Commands.java`。
只构建 `v1.20.1`。未启动游戏。

## 1. 结论

**真实光束的默认渲染路径改成壳层锥**（`beamConeVolume = false`），也就是
`/mtr_lte debug cone` 探针一直在走、并且用户实机验证看得见的那条管道：

```
emit() -> cone() -> quad() -> GlowCompat.draw()
```

四层同心壳（`SHELLS = 1.0 / 0.72 / 0.45 / 0.18`）+ 纵向衰减 `exp(-2.5t)(1-t)` +
径向权重 `(1-r²)²` + 视角柔化 `0.22 + 0.78|n·v|^1.5` + 地面淡出，加色混合
（`SRC_ALPHA, ONE`）、深度测试开、深度写关、无剔除、相机相对坐标。

raymarch（`ConeVolumeRenderer`）降为可选实验项：`/mtr_lte cone volume on`。

## 2. 壳层与探针是否共用同一函数

是。探针 `/mtr_lte debug cone` 和真实光束都走：

| 步骤 | 真实光束 | 探针 |
|---|---|---|
| 顶点生成 | `cone()`（四层壳 + 三种衰减） | `debugCone()`（四层壳，无衰减） |
| 四边形写入 | `quad()` | `quad()` |
| 提交绘制 | `GlowCompat.draw(vertices, …)` | `GlowCompat.draw(vertices, …)` |

差别只在：探针是固定几何、固定 alpha 0.45、不做衰减；真实光束用逐车参数并乘三种衰减。
**绘制管线（`quad` + `GlowCompat.draw`）是同一个函数**，所以"探针可见"直接证明真实光束这条路能出像素。

## 3. 逐车参数与 BeamGeom

壳层路径逐车接受：

- **位置**：GLOW 源 = `BeamGeom.apex(world)`（`BeamGlow.startHeight` + `beamLift + height`）；
  MODEL 源 = 灯位槽算好的世界顶点（含 `FACE_GAP - inset`、左右灯对横向偏移、槽位高度）。
- **方向**：GLOW = `pose.forward()/backward()`；MODEL = 该车节切线（尾灯取反）。
- **长度**：`beamHeadLength` / `beamTailLength`（逐车 `Effective` 可覆盖）。
- **半径**：`coneRadius(length, head)`，两个源共用（13° 半角，头 ×0.6 / 尾 ×0.5）。
- **颜色**：`beamHeadColor` / `beamTailColor`（逐车覆盖）。
- **alpha**：`beamConeAlpha`（逐车 `beamConeAlpha`），尾灯 ×0.55。

两个几何源现在都构造 `BeamGeom`：

- GLOW：`BeamGeom.of(railPoint, dir, offset, length, height, rgb)` —— **和光带同一个工厂**，
  `apex()` 调 `BeamGlow.startHeight`，灯口与光带第一排中心线顶点逐位重合。
- MODEL：`BeamGeom.at(apex, dir, length, rgb)` —— 顶点已含灯位槽的横向偏移/高度，不再重算 y。
  壳层绘制只从 `BeamGeom` 读 `apex/axis/length/rgb`，两个源到这一层是**同一种输入**。

> 用户当前配置是 `beamConeSource = GLOW`，所以光柱几何与光带完全同源；若切回 `model`，
> 光柱改用车型灯位，但渲染路径不变（仍是壳层）。

## 4. 命令

```
/mtr_lte cone                    # 状态：path=shell|volume、occlusion、steps、volume=
/mtr_lte cone volume off         # 默认：加色壳层锥（与探针同一管道）
/mtr_lte cone volume on          # 实验：1.20.1 raymarch（可被墙/车体遮挡）
```

写 `config/mtrlte.json`，下一帧生效。shader pack / GL 失败时 `volume=on` 也会自动退回壳层。

## 5. 诊断行

`[lte] cone:` 与 `[lte] debug:` 都新增 `path=shell|volume`（实际生效路径，不是配置值）：

```
[lte] cone: calls=... trains=... models=0 noModel=0 noSlot=0 drawn=2
            quads=4032 probeQuads=0 volBeams=0 probe=0 near=2 dmin=37.1
            range=128 g1=0 ... g9=0 path=shell source=GLOW shaders=false peak=0.160
            depthTex=4 volume=off(shell)
```

判读：

- `path=shell` + `coneQuads>0` = 壳层路径在出四边形（每条光束 4 壳 × 6 带 × 14 段 = 336 个）。
- `path=shell` + `coneQuads=0` = 没有光束被发出（看 `beams` / `g*`），不是绘制坏了。
- `path=volume` 时 `coneQuads` 必然为 0，看 `volBeams`。
- `volume=off(shell)` = raymarch 被配置关掉（默认），**不是** shader 缺失。

## 6. 被旁路的代码

默认 `beamConeVolume=false` 时，以下路径完全不走（不是失败回退，是根本不调用）：

- `ConeVolumeRenderer.render(...)` —— 不建覆盖代理、不做深度 blit、不跑 raymarch；
- `ConeVolumeRenderer.ensureTargets / copyDepth / captureCoarse / drawBeam`；
- `BEAMS` 列表始终为空（`volumeBeams=0`）；
- `beamConeOcclusion` / `beamConeSteps` 对壳层路径无效（壳层没有深度测试与采样数）；
  侧线的 `beamConeOcclusion` 同样只在 `cone volume on` 时起作用。

壳层路径本身**没有**遮挡：光锥会画在墙/车体前面。这是已知代价，raymarch 修好后再切回。

## 7. 构建与产物

```
bash tools/build-all.sh v1.20.1
BUILD SUCCESSFUL in 14s
```

- `v1.20.1/build/libs/lte-1.0.0.jar` → `~/Desktop/lte-1.0.0+1.20.1.jar`（504357 字节）
- 同一份 jar → `PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.0+1.20.1.jar`
- sha256（三份一致）：`c06a307ed81fdd62252fa80516c1cf7dfaf904b3bc652d78d0d2015aeb93f217`

配置版本 14 → 15：首次加载会把 `beamConeVolume: false` 写进现有 `config/mtrlte.json`
（Gson 对缺失键用字段默认值，所以升级后默认就是壳层）。

未启动游戏；实机验证由用户进行。
