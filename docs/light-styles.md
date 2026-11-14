# 车灯的三件可见光效：光带 / 灯晕 / 散射（三个独立开关，默认全开）

> 适用：1.20.1 模块。配置键 `beamBand` / `beamFlare` / `beamScatter`，默认都是 `true`。

车灯在"看得见"这一层有三件互相独立的效果。它们读的是**同一盏灯**：同一份灯位
（`TrainLamps`）、同一份颜色（`beamHeadColor` / `beamTailColor`）、同一份亮度与长度
（`beamAlpha` / `beamHeadLength` / `beamTailLength` / `beamHeight`）。区别只是**怎么画**，
所以任意组合都能同时开，谁也不会影响谁。

## 三个开关

| 配置键 | 画什么 | 观感 | 开销 |
|---|---|---|---|
| `beamBand`（默认开） | `BeamGlow`：从车头沿车体朝向直线推出的贴地带状面片 | 近看有"灯光打在地上"的体积感，弯道上也能看出车灯指哪 | 每束 1 个面片批次（几十个顶点），每帧 1 次 draw |
| `beamFlare`（默认开） | `LightFlare`：每个物理灯位一张面向相机的加色贴图 | 远看、侧看都是一颗亮灯；转弯时随灯位朝向明暗变化 | 每灯 1 个四边形，所有灯合成 1 次 draw |
| `beamScatter`（默认开） | `sss.ScatterPass`：世界画完之后的一次全屏 pass | 车灯在空气里的光雾 + 真的照亮地面 / 墙面 | 一次全屏 pass（读深度 + 读场景色） |

三者都**不含**地面的实际照明数据：地面/墙上的方块光照来自 `beamBlockLight`（写进区块网格，
`BeamBlockLight` + `BeamRelight`），那是一个独立开关，与这三件无关。

## 怎么切

三个入口，写的都是同一份配置，效果立刻可见（渲染每帧读配置）：

1. **配置面板**：`/mtr_lte` → 全局配置 → 光源 → 三行独立开关「光带 / 灯晕 / 散射」。
2. **指令**：
   ```
   /mtr_lte flare style band on|off       # 光带
   /mtr_lte flare style flare on|off      # 灯晕
   /mtr_lte flare style scatter on|off    # 散射
   /mtr_lte flare status                  # 三个开关 + 渲染层实际画的三个布尔
   ```
3. **手改 `config/mtrlte.json`**：`"beamBand": true` / `"beamFlare": true` / `"beamScatter": true`
   任意组合。键缺失时按默认 `true` 读，不会让整份配置读不出来。

## 从旧版「光样式」迁移（版本 25）

旧版本用一个三态键 `lightStyle`（`BAND` / `FLARE` / `BOTH`）选画法。配置文件里还有这个键时，
读盘会按下面这张表折算成三个开关，然后把 `lightStyle` 从文件里删掉（之后再也不会写出）：

| 旧 `lightStyle` | `beamBand` | `beamFlare` | `beamScatter` |
|---|---|---|---|
| `BAND` | `true` | `false` | `false` |
| `FLARE` | `false` | `true` | `true` |
| `BOTH` | `true` | `true` | `true` |

更早的文件（v22 之前，没有 `lightStyle`）按当时的两个逐层开关 `beamGlow` / `beamFlare`
折算，等价于 `BAND`。旧的 `beamGlow` / `scatterEnabled` 键同样只读一次、迁移后置空。

## 逐侧线覆盖

侧线页的「光带」「灯晕」两行可以给**某一条侧线**单独收紧（`sidingSettings[<id>].beamBand` /
`.beamFlare`）：

* 全局开了光带、某条侧线关掉 → 只有那条侧线的车不画光带；
* 全局关掉光带 → 任何侧线的 `beamBand = true` 都不能把它画出来（覆盖只能更严）；
* 点 `↺` = 清除覆盖，重新跟随全局。

散射是全局唯一的全屏 pass（代价与"哪条侧线的车"无关），所以侧线页不生成、也不接受这项写盘。

## 散射的两层

`dev.js.mtrheadlight.sss.ScatterPass`（`beamScatter` / `scatterSurface` /
`scatterStrength` / `scatterRadius`）是**世界画完之后的一次全屏 pass**，两层可以分开关：

| 层 | 效果 | 关掉的开关 |
|---|---|---|
| **表面项** | 车灯**真的照亮**地面 / 墙面 / 站台 / 车体：拷贝一份场景颜色当 albedo，用深度的 `dFdx × dFdy` 求面法线，逐灯算 `spot × 距离窗 × 1/(1+d²/9) × max(N·L,0)`，乘上 albedo 后加回画面 | `scatterSurface`（默认开） |
| **体积项** | 空气里的光雾：视线穿过灯周围那团空气的长度 × 锥门 × 相位函数 × 透射，终点被场景深度夹住 | 只能连 `beamScatter` 一起关 |

合成（一次颜色**替换**，混合关闭）：

```
added = 体积和 × 1.6 + 表面和 × (scene × 8.0 + 0.05)
lit   = scene + (1 - exp(-added × strength)) × (1 - 0.5 × scene)
```

* 表面项乘的是 `scene`（已经画好的像素颜色 = 材质 albedo），所以**深色站台暗着亮、白墙亮着亮**；
* `added == 0` 时输出**逐字等于 `scene`**，所以矩阵算错或没有灯时这一帧只是保持不变，不会把屏幕涂黑；
* 只有一层深度、只有正面法线，所以**没有自阴影**，被别的物体挡住的表面拿不到光。

## 叠加时会不会互相影响

`RenderHooks` 无条件调三个渲染器，每个都在自己内部判门（`drawBand()` / `drawFlare()` /
`drawScatter()`），并且都是自包含的：

* 各自 `begin` / `end` 自己的顶点缓冲，不会把对方写了一半的面片接过来（`BeamGlow` 走
  `GlowCompat.draw`，`buffer.end()` 立刻提交；`LightFlare` 自己 `begin` / `endNullable`）；
* 各自设置并**复位**同一组 GL 状态（`depthMask(false) → depthMask(true)`、`SRC_ALPHA/ONE →
  defaultBlendFunc`、`disableCull → enableCull`），所以后者看到的是干净的状态；
* 早/晚两趟回调仍然"每帧只有一趟真的画"（按 `ShaderPackProbe` 决定），不会一帧画两次；
* 任何一边抛异常都只影响它自己（各自 try/catch），另一边照旧。

## 灯位（pos）与朝向（dir）是两个符号

`TrainLamps.place()` 里这两件事**各写各的**，别再当成一个符号一起翻：

```
pos = base + right * lateral + (0, height, 0) + out * (|inset| + FLARE_GAP)   // 位置：+out，推向车脸外
dir = -out                                                                   // 朝向：−out，渲染用
beam = out = -dir                                                            // 灯照出去的方向
```

* `out` 是车端面的**外法线**（车头 +弦、车尾 −弦），位置沿它推出去，灯才不会埋在车头网格里；
* `LightFlare` 的朝向判定是 `facing = -dot(dir, view)`，`view` 是"灯 → 相机"。灯面朝车外、相机站在
  车头正前时 `view ≈ out`，要让 `facing ≈ +1`，`dir` 就必须是 `-out`；取 `+out` 时 `facing ≈ -1`，
  灯晕先被 `facing < MIN_FACING` 判成"背对相机"剔除 —— 画面上就是**"光晕朝向反了"**；
* 方块光照段（`LightController.endSegment`）和散射锥轴（`ScatterLights`）要的是"灯照出去的方向"，
  所以它们读 `Lamp.beam()`（= `-dir` = `out`）。

## 自检

* `/mtr_lte flare status`：`beamBand` / `beamFlare` / `beamScatter` 三个值，以及
  `draws= band … / flare … / scatter …`（渲染层实际用的三个布尔，与三处帧级门槛同源）；
* 日志每 5 秒一行 `[lte] glow: band=… flare=… cars=… beams=… dirFix=…`（光带）、
  `[lte] flare: band=… flare=… trains=… lamps=… quads=… noLamp=… drawn=… tex=…`（灯晕）、
  `[lte] scatter pass: frames=… lights=… reason=…`（散射），紧跟一行
  `[lte] lights: band=… flare=… scatter=… block=… test=…` 汇总这一帧五条路径的闸门；
* 灯晕看不到时看 `noLamp=` 与 `[lte] flare near:` 行（距离 / 朝向 `f=` / 世界尺寸 / 屏幕像素 /
  最终 alpha 五个数）；`f≈-1.00` 就是灯面朝向反了；
* 光带看不到时有 `[lte] glow: … near= skipped=` 的分类计数。
