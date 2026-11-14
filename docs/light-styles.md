# 车灯可见样式：光带 / 灯晕 / 两者（默认光带）

> 适用：1.20.1 模块。配置键 `lightStyle`，默认 `BAND`（光带）。

车灯在"看得见"这一层一直有两套画法。它们画的是**同一盏灯**：同一份灯位（`TrainLamps`）、同一份
颜色（`beamHeadColor` / `beamTailColor`）、同一份亮度与长度（`beamAlpha` / `beamHeadLength` /
`beamTailLength` / `beamHeight`）。区别只是**怎么画**，所以可以互为备份、由玩家自己选，也可以
两个一起开。

## 三种样式

| `lightStyle` | 画什么 | 观感 | 开销 |
|---|---|---|---|
| `BAND`（默认） | `BeamGlow`：从车头沿车体朝向直线推出的贴地带状面片 | 近看有"灯光打在地上"的体积感，弯道上也能看出车灯指哪 | 每束 1 个面片批次（几十个顶点），每帧 1 次 draw |
| `FLARE` | `LightFlare`：每个物理灯位一张面向相机的加色贴图 | 远看、侧看都是一颗亮灯；转弯时随灯位朝向明暗变化 | 每灯 1 个四边形，所有灯合成 1 次 draw |
| `BOTH` | 两者叠加 | 灯头有亮斑、地面有光带，最"满"的一种 | 上面两项相加 |

三者都**不含**地面的实际照明：地面/墙上的亮光来自真实方块光照（`beamBlockLight`，写进区块网格），
那是一个独立开关，与样式无关。

光带是玩家公认最好看的一版，因此它也是新装与升级后的默认值。

## 怎么切

三个入口，写的是同一个键，效果立刻可见（渲染每帧读配置）：

1. **配置面板**：`/mtr_lte` → 全局配置 → 光源 → 第一行「光样式：…」。整行一个按钮，点一下换下一档：
   `光带 → 灯晕 → 光带 + 灯晕`。
2. **指令**：
   ```
   /mtr_lte flare style band      # 只画光带
   /mtr_lte flare style flare     # 只画灯晕
   /mtr_lte flare style both      # 两者都画
   /mtr_lte flare status          # 看现在是什么样式、两层各自画不画
   ```
3. **手改 `config/mtrlte.json`**：`"lightStyle": "BAND" | "FLARE" | "BOTH"`。写错或写成 `null`
   会被读成 `BAND`，不会让整份配置读不出来。

## 和旧开关的关系（`beamGlow` / `beamFlare`）

`beamGlow` 与 `beamFlare` 是样式出现之前"这一层画不画"的两个开关，现在**跟着样式一起写**：

| 样式 | `beamGlow` | `beamFlare` |
|---|---|---|
| `BAND` | `true` | `false` |
| `FLARE` | `false` | `true` |
| `BOTH` | `true` | `true` |

所以 JSON 里的三个键永远自洽（不会出现"样式是光带，但 beamFlare 还写着 true"）。实际是否绘制
= 样式允许 **且** 这一层的开关没被关掉，两份条件都在 `HeadlightConfig.bandGlobal()` /
`flareGlobal()` 里，渲染器和界面都只读这两个方法，不会出现两边判断不一致。

开着光带时，"光源"页上的那行**灯晕**开关等价于"在当前样式上把灯晕加上/去掉"：

* 灯晕 打开 → 有光带则 `BOTH`，没有则 `FLARE`；
* 灯晕 关闭 → `BAND`。

它能按、按了也有反应（不会变成死开关），但想精确三选一请用「光样式」那一行。

## 旧配置升级

配置文件里没有 `lightStyle` 键的（也就是 0.2.x 及更早写的 `config/mtrlte.json`），读到 `BAND`：

* 光带照旧画 —— 与"光带不要删"的要求一致；
* 灯晕不画；
* 迁移时会顺带把 `beamFlare` 写成 `false`、`beamGlow` 写成 `true`，让文件内容与实际绘制一致；
* **逐侧线的覆盖不动**：`sidingSettings[<id>].beamGlow` / `.beamFlare` 是玩家对某条侧线的明确选择，
  迁移不碰。

想让灯晕回来：`/mtr_lte flare style flare`（或面板里点一下）。

## 逐侧线覆盖

全局样式决定默认画法；侧线页的「灯晕」开关仍然可以给**某一条侧线**单独打开或关掉灯晕：

* 侧线覆盖了 `beamFlare = true`，全局样式是 `BAND` → 这条侧线的车也画灯晕；
* 侧线覆盖了 `beamFlare = false`，全局样式是 `BOTH` → 这条侧线不画灯晕（光带照画）；
* 点 `↺` = 清除覆盖，重新跟随全局样式。

## 屏幕空间散射（独立开关，可与任意样式叠加）

`dev.js.mtrheadlight.sss.ScatterPass`（配置 `scatterEnabled` / `scatterSurface` /
`scatterStrength` / `scatterRadius`）是**世界画完之后的一次全屏 pass**，它做两件事：

| 层 | 效果 | 关掉的开关 |
|---|---|---|
| **表面项** | 车灯**真的照亮**地面 / 墙面 / 站台 / 车体：拷贝一份场景颜色当 albedo，用深度的 `dFdx × dFdy` 求面法线，逐灯算 `spot × 距离窗 × 1/(1+d²/9) × max(N·L,0)`，乘上 albedo 后加回画面 | `scatterSurface`（默认开） |
| **体积项** | 空气里的光雾：视线穿过灯周围那团空气的长度 × 锥门 × 相位函数 × 透射，终点被场景深度夹住 | 只能连 `scatterEnabled` 一起关 |

合成（一次颜色**替换**，混合关闭）：

```
added = 体积和 × 1.6 + 表面和 × (scene × 8.0 + 0.05)
lit   = scene + (1 - exp(-added × strength)) × (1 - 0.5 × scene)
```

* 表面项乘的是 `scene`（已经画好的像素颜色 = 材质 albedo），所以**深色站台暗着亮、白墙亮着亮**，
  这就是「真照亮」和「贴一张光斑」的区别；
* `added == 0` 时输出**逐字等于 `scene`**，所以矩阵算错或没有灯时这一帧只是保持不变，不会把屏幕涂黑；
* 只有一层深度、只有正面法线，所以**没有自阴影**，被别的物体挡住的表面拿不到光。

它与光样式（光带 / 灯晕 / 两者）的关系：

* `scatterEnabled = true` 时，光带、灯晕、两者三种样式下都会出散射（表面项 + 体积项）；
* 三种样式都不会影响散射的强弱或半径（散射读同一份灯位与颜色）；
* 反之，关掉散射也不影响光带/灯晕。

一句话：**样式管"灯怎么画"，散射管"灯照不照到东西、空气里散不散"，两组开关互不干扰。**

## 叠加时会不会互相影响

`BOTH` 时 `RenderHooks` 先调 `BeamGlow.render` 再调 `LightFlare.render`，两者都是自包含的：

* 各自 `begin` / `end` 自己的顶点缓冲，不会把对方写了一半的面片接过来（`BeamGlow` 走
  `GlowCompat.draw`，`buffer.end()` 立刻提交；`LightFlare` 自己 `begin` / `endNullable`）；
* 各自设置并**复位**同一组 GL 状态（`depthMask(false) → depthMask(true)`、`SRC_ALPHA/ONE →
  defaultBlendFunc`、`disableCull → enableCull`），所以后者看到的是干净的状态；
* 早/晚两趟回调仍然"每帧只有一趟真的画"（按 `ShaderPackProbe` 决定），两套几何用的是同一个判定，
  不会一帧画两次；
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
  灯晕先被 `facing < MIN_FACING` 判成"背对相机"剔除，剩下的尺寸/亮度也按负值算 ——
  画面上就是**"光晕朝向反了：车头往车后照"**，同时三档切换看不出区别（灯晕在 BAND 之外的档位里
  根本不可见）；
* 方块光照段（`LightController.endSegment`）和散射锥轴（`ScatterLights`）要的是"灯照出去的方向"，
  所以它们读 `Lamp.beam()`（= `-dir` = `out`），朝向与改 `dir` 之前逐字相同 —— 位置、地面亮斑、
  空气散射都不动，只有灯晕的朝向变了。

## 自检

* `/mtr_lte flare status`：`lightStyle`、`style=… band=… flare=…`（**渲染层实际用的两个布尔**，
  与两处帧级门槛同源）、光带/灯晕各自画不画、方块光照挂钩状态；
* 切档瞬间日志里多一行 `[lte] lightstyle: applied style=FLARE band=false flare=true (beamGlow=false beamFlare=true)`
  —— 只在值变化时打，所以「切了样式日志里没这行」= 没写进配置或没同步；「有这行但画面没变」= 值都对，
  问题在几何（看下面的 `f=` 朝向 / 距离 / alpha）；
* 日志每 5 秒一行 `[lte] glow: style=… band=… flare=… cars=… beams=… dirFix=…`（光带）和
  `[lte] flare: style=… band=… flare=… trains=… lamps=… quads=… noLamp=… drawn=… tex=…`（灯晕），紧跟一行
  `[lte] flare near: #0 d=… f=… w=… px=… a=… fade=… ok|far|back|dim|faded/来源` —— 相机最近的
  3 盏灯，把「quads 在涨但屏幕上没有」拆成**距离 / 朝向 / 世界尺寸（格）/ 屏幕像素 / 最终 alpha**
  五个数；`drawn=false` 或 `err=…` 说明绘制本身抛了，`tex=missing` 说明贴图没打进包。
  **判朝向**：站在车头正前方看头灯，`f` 应该接近 `+1.00`；`f≈-1.00` 就是灯面朝向反了。
* 光带看不到时有 `[lte] glow: … near= skipped=` 的分类计数，灯晕看不到时看 `noLamp=` 和 `near:` 行。
