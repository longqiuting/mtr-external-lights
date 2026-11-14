# 散射 pass 的表面项（真照亮地面/墙面）· 1.20.1

日期：2026-11-14　范围：**只改 1.20.1**（`common/` 只加了一个配置字段）
依据：外部可见的效果（"照亮"= 全屏后处理，不是光照数据）

---

## 0. 一句话

`ScatterPass` 原来只有体积项（空气里的光雾），所以在深色地面上只看到一层浮着的光。
现在它同时算**表面项**：拷一份场景颜色当 albedo、由深度的 `dFdx × dFdy` 求面法线、逐灯算
spot × 距离窗 × 1/(1+d²/9) × max(N·L,0)，**乘上 albedo 后加回画面**——地面/墙面/车体真的被照亮。
两层一个开关各管一半：`scatterSurface`（默认开）只关表面项，光雾留着。

---

## 1. 新 shader 的 uniform / sampler 清单（已用真驱动编译验证）

`assets/mtrlte/shaders/core/light_scatter.json` ↔ `light_scatter.fsh` ↔ `ScatterProgram` 三处必须同名：

| 名字 | 类型 | json `count` | 谁写 | 含义 |
|---|---|---|---|---|
| `Sampler0` | sampler2D | — | `DepthCopy.texture()` → 单元 0 | 深度拷贝，窗口空间 `.r` |
| `Sampler1` | sampler2D | — | `ColorCopy.texture()` → 单元 1 | 颜色拷贝（albedo），同一窗口空间 |
| `uInvViewProj` | matrix4x4 | 16 | `ScatterPass` | `inverse(ProjMat * View)`，相机相对 |
| `uParams` | vec4 | 4 | `ScatterPass` | x=体积增益 1.6、y=消光 0.075、z=最长 96、w=相位 g 0.55 |
| `uCone` | vec4 | 4 | `ScatterPass` | x=cos(内锥) 0.94、y=cos(外锥) 0.55 |
| `uSurface` | **vec4（新）** | 4 | `ScatterPass` | x=strength k（=`scatterStrength`）、y=衰减参考 9.0（块²）、z=表面项开关 1/0、w=`litPx` 探针 1/0 |
| `uCount` | int | 1 | `ScatterPass` | 本帧真的填了几盏灯 |
| `uTest` | int | 1 | `LightTest` | 1 = `/mtr_lte lighttest` 固定纯红 |
| `uLightPos[16]` | vec4×16 | 64 | `ScatterLights` | xyz=灯位（相机相对），w=光雾半径 R |
| `uLightDir[16]` | vec4×16 | 64 | 同上 | xyz=单位朝向，w=光束长度 |
| `uLightColor[16]` | vec4×16 | 64 | 同上 | rgb=颜色，w=强度 |

验证方式（不需要开游戏，但要真驱动）：`tools/ShaderCompileCheck.java` 建一个 3.3 core 隐藏 GLFW 窗口，
把这两个 `.fsh/.vsh` 直接编译链接，然后 `glGetUniformLocation` 逐个查。结果：

```
GL 3.3.0 NVIDIA 621.24 | GLSL 3.30 NVIDIA via Cg compiler
vertex compile=ok   fragment compile=ok   link=ok
uInvViewProj ok @4   uParams ok @53   uCone ok @2   uSurface ok @54   uCount ok @3
uTest ok @55         uLightPos ok @37  uLightDir ok @21   uLightColor ok @5
Sampler0 @0 unit=0   Sampler1 @1 unit=1     attribute Position @0
RESULT: all names present
```

要点：**没有一个是 `-1`**，说明没有任何 uniform 被编译器优化掉（被优化掉的那个正是
`require()` 会让整个 program 失败的那一档）；`Sampler1` 落在单元 1，和 json `samplers` 数组
的顺序一致（vanilla `ShaderProgram` 按数组下标 `glUniform1i(sampler, i)`）。

---

## 2. 合成公式（混合关闭，整屏替换）

```
每像素：
  P        = rd · tHit                                  // 该像素自己的世界点（相机相对）
  N        = normalize(cross(dFdx(P), dFdy(P)))，dot(N,P)>0 时取反   // 朝向相机
  normalValid = max(|dFdx(P)|,|dFdy(P)|) < max(0.5, tHit·0.25)       // 轮廓处丢弃
  天空像素（depth==1）跳过表面项，只留体积项

每盏灯（表面项，和体积项共用一个 16 次的循环）：
  L        = normalize(lamp - P)
  spot     = smoothstep(cosOuter, cosInner, dot(axis, -L))
  win      = 1 - smoothstep((|lamp-P| - 0.6·len)/(0.4·len))
  att      = 1 / (1 + |lamp-P|² / 9)
  NdotL    = max(dot(N, L), 0)
  surface += colour · power · spot · win · att · NdotL

合成：
  added = Σ体积 · 1.6 + Σ表面 · (scene · 8.0 + 0.05)
  lit   = scene + (1 - exp(-added · strength)) · (1 - 0.5 · scene)
  out   = clamp(lit, 0, 1)          // alpha = 探针覆盖，否则 1
```

三个性质值得记住：

1. **`added == 0` 时输出逐字等于 `scene`**（`tools/scatter-math-check.js` 的 identity 段实测
   delta = 0）。视图矩阵算错、灯数 0、参数全 0 都只会"什么都没加"，**不可能把屏幕涂黑**。
   这是"整屏替换"这种做法唯一必须守住的地板。
2. **表面项乘 `scene`**：`scene` 是已经画好的像素颜色 = 材质 albedo。深色站台暗着亮、白墙亮着亮，
   `+0.05` 只是给近乎全黑的路面留一点底。这是"真照亮"和"贴一张光斑"的区别。
3. **参数是我们自己定的**（不取自任何外部实现）：
   衰减 `1/(1+0.04d²)`（≈ `1/(1+d²/25)`），合成 `1-exp(-added·1.2)`；
   我们用 `8.0 / 0.05`、`1/(1+d²/9)`（更陡的 ~1/d²，任务书指定）、`1-exp(-added·strength)`。
   结果是我们的光池更集中在灯附近，远处收得更快。

**为什么 json 里没有 `blend` 段**：1.20.1 的 `ShaderProgram.bind()` 会 apply json 解析出的
`GlBlendState`。没有 `blend` 键 → `JsonHelper.getObject(json,"blend",null)` 返回 null →
`new GlBlendState()` → `blendDisabled = true`（`dst = src`），即"混合关闭、直接替换"。
手写 `RenderSystem.disableBlend()` 只是让两边一致；**光靠手写是没用的**，bind() 会覆盖它。

---

## 3. 颜色拷贝的开销

| 项 | 值 |
|---|---|
| 每帧 | **一次全屏 blit**（`GL_COLOR_BUFFER_BIT`，`GL_NEAREST`，主 FBO 颜色附件 → 自建 `GL_RGBA8` 纹理） |
| 显存 | 1080p RGBA8 ≈ **8 MB**（1440p ≈ 15 MB，4K ≈ 33 MB）；和深度拷贝同尺寸 |
| 带宽 | GPU 内部 blit，**不跨 PCIe**（不是 `glReadPixels`）；1080p 约 16 MB 读+写 |
| 何时做 | 只有"本帧有灯"时；0 盏灯在拷贝之前就 return，一帧两份纹理都不建也不拷 |
| 失败时 | 一次性 WARN + 本会话关掉整个 pass（没有 albedo 就没法合成，宁可不出效果也不涂黑屏） |
| `litPx` 探针 | 只在 `/mtr_lte scatter status` 后的那一帧：一次 96×54 降采样 blit + **20 KB** 回读 |

对比老版本：多了一次全屏 blit（老版本只有深度 blit），但少了一次"每像素 discard/加色混合"的分支，
且**没有**多一次绘制、没有多一段灯循环——表面项和体积项在同一个 `for (i < uCount)` 里。

---

## 4. 与光带 / 灯晕互不干扰的论证

| | 光带 `BeamGlow` / 灯晕 `LightFlare` | 散射 `ScatterPass`（含表面项） |
|---|---|---|
| 时机 | `AFTER_TRANSLUCENT`（还在画世界几何） | `WorldRenderEvents.LAST`（世界画完之后） |
| 写什么 | 颜色附件，加色贴图，**会测/写深度** | 颜色附件，**整屏替换**（`dst=src`），`disableDepthTest` + `depthMask(false)` |
| 读什么 | 自己的贴图 | `Sampler0`=深度拷贝、`Sampler1`=颜色拷贝（都不是主附件本身） |
| 灯位来源 | `TrainLamps.of(...)` | `TrainLamps.of(...)`（`ScatterLights` 复用同一份门与排序） |
| 开关 | `lightStyle` / `beamGlow` / `beamFlare` | `scatterEnabled` / `scatterSurface` |

1. **时间上不重叠**：`LAST` 时灯晕/光带早已写完，颜色拷贝抓到的就是"最终画面"（含它们）。
   所以表面项读到的 albedo 里已经包含灯晕的加色，不会把灯晕擦掉——它在这个结果上再加一层灯。
2. **空间上不共享附件读写**：散射只读拷贝纹理，写主颜色附件；不存在"读自己写的附件"。
   深度同理（`DepthCopy`）。两个附件、两张纹理，各自 blit。
3. **不写深度**：`depthMask(false)` 且 `LAST` 之后没有任何东西再用深度（GUI/手是 2D），
   所以不会影响粒子、天气、手、GUI，也不会给灯晕留下"被深度挡住"的假象。
4. **状态全部还原**：`finally` 里 `depthMask(true)`、`enableDepthTest()`、`enableBlend()`、
   `defaultBlendFunc()`、`enableCull()`、`setShader(position_color)`、
   `setShaderTexture(0,0)` **和 `setShaderTexture(1,0)`**、`setShaderColor(1,1,1,1)`。
   1 号单元是新增的全局状态，必须一起还——上一轮光锥工作里就是因为忘了还深度纹理导致粒子集体消失。
5. **开关正交**：三层读的都是同一份灯位/颜色，所以关掉任一个都不会让其它层的位置或颜色变化。
   实测过的组合：`scatter on + surface off` = 只有光雾；`scatter off` = 只剩几何层。

---

## 5. 配置 / 命令 / 诊断

* 配置：`config/mtrlte.json` 新增 `"scatterSurface": true`。缺键时 Gson 走字段初始化 → `true`
  （`HeadlightConfig` 有显式无参构造，不走 Unsafe 分配），所以老配置文件升级后是**开**的。
* 界面：**配置 → 光源 → 空气散射**，开关+强度一行 → 「空气散射·照亮表面」一整行 → 半径一行
  （只有全局页；id `ConfigWriteTarget.SCATTER_SURFACE = 38`，`↺` 可单独复位到默认 `true`）。
* 语言：`opt.scatterSurface.on/off`（zh_cn「空气散射·照亮表面：开/关」、en_us「Air scatter - light surfaces」）。
* 命令：`/mtr_lte scatter status` 现在多两个字段：
  `surface=on|off`、`litPx≈N`（第一次执行显示 `measuring(run again)`，因为它让**下一帧**测量；
  没灯时是 `n/a(no lit lamps)`，关掉表面项时是 `n/a(surface off)`）。
* 日志：5 秒一行的 `[lte] scatter: ...` 末尾加了 `surface=on|off`；首次编译那行也带 `surface=`。

---

## 6. 数值验收（`node tools/scatter-math-check.js`）

体积项**完全没变**（同一组 tau / lit，正对灯 0.798、两灯车头 0.908……），表面项新增输出：

```
1 m under the lamp              s=0.000  cos=0.24    ← 灯朝前照，正下方不在锥内（正确）
2 blocks in front of the nose   s=0.234  cos=0.78 spot=0.65 ndotl=0.79 att=0.46
5 blocks in front of the nose   s=0.102  cos=0.97 spot=1.00 ndotl=0.46 att=0.22
10 blocks in front              s=0.019  cos=1.00 spot=1.00 ndotl=0.25 att=0.08
4 blocks to the side            s=0.000  cos=0.13    ← 锥外
6 blocks BEHIND the lamp        s=0.000  cos=-0.79   ← 背面

合成（surface only, k=1）：
dark asphalt  0.04 → 5 格前方 +0.036
concrete      0.15 → 5 格前方 +0.111
white wall    0.60 → 5 格前方 +0.273
identity:  scene 0.00/0.04/0.50/1.00 → delta 0.0（逐字不变）
wall(N 朝相机) 0.613  vs  同一 z 的地面 0.234      ← 法线决定亮度，不只是距离
```

## 7. 没验证到的

* **没有开游戏看画面**（任务不要求）。数学、GLSL 编译、uniform 名、配置/命令/枚举全部有机器证据；
  但"看起来像不像被照亮"仍需真实存档里看一眼。
* 表面项没有自阴影、被遮挡表面拿不到光（只有一层深度、只有正面法线）——这是屏幕空间做法的固有边界，
  任何屏幕空间做法都同样如此。
