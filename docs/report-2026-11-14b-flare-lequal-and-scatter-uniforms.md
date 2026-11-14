# 灯晕回 LEQUAL + 散射灯阵 uniform 修复（1.0.62 反馈）· 1.20.1

日期：2026-11-14　范围：**只改 v1.20.1**（`common/` 未动）
前置：`docs/report-2026-11-14-flare-depth-and-scatter-probe.md`（那份报告里"深度比较改
`GL_ALWAYS`"的结论**已被本轮推翻**，读它时以本文为准）、`docs/screen-space-scatter.md`

---

## 0. 一句话

1. **灯晕**：`GL_ALWAYS` → 强制 `GL_LEQUAL`（不再穿墙），穿不过去的问题改由**位置**解决——
   `TrainLamps` 本来就把灯放在端面外侧，四边形往相机拉的量从 `min(2.2, 8%)` 降到
   `min(0.6, 5%)`。晚趟 only 不变。
2. **散射**：`debug surface` / `debug cone` 全黑、一点照亮都没有，**根因是灯阵 uniform 根本没到 GPU**，
   不是法线、不是锥角符号。1.20.1 的 core shader json **写不出 `vec4[]`**：旧 json 写
   `"type":"float","count":64`，`GlUniform` 用 `glUniform1fv` 往 `vec4[16]` 位置发 →
   `GL_INVALID_OPERATION` → 数组停在默认值，`uLightColor[i].w`（强度）= 0 →
   片元循环第一句 `if (power <= 1e-4) continue;` **把 16 盏灯全跳过**。

## 1. 灯晕：为什么 ALWAYS 是错的，LEQUAL 为什么这次不会被吃掉

* 1.0.62 用 ALWAYS 换「看得见」，代价是**隔墙可见**——加色四边形不测深度就是穿墙。用户反馈
  与代码逐字对应。
* 被深度吃掉的原始原因不是"灯在车体里"，而是**灯位坐标空间**（1.0.61 已修：世界坐标 → 相机相对）。
  位置这一层：`TrainLamps.place()` 的 `pos = base + right*lateral + up*height - out*back`，
  其中 `out` 是车体**内向**弦、`back = clamp(inset,-2,1.5) + FLARE_GAP(0.08)`，所以 `-out*back`
  是**向外**推——灯至少在端面外 0.08 格，模型量到的 `inset` 还会再往外。
  换句话说 `FLARE_GAP` **是正外推**，这一点已被 `TrainLamps` 的注释与代码确认。
* 于是剩下只需要"把四边形从灯座里拔出来"：`LightFlare.MAX_PULL 2.2 → 0.6`、
  `PULL_FRACTION 0.08 → 0.05`。近距离 0.1 格、远距离封顶 0.6 格，不会把光晕推到墙外。
* 深度函数仍走 `GlowCompat#forceDepthFunc` 直调 GL：1.20.1 的 `_depthFunc` 带缓存，Sodium /
  Embeddium 之类直调过 `glDepthFunc` 之后缓存就与硬件脱钩。现在强制的是 **LEQUAL**，
  还原仍按"进来时读到的那一个"。
* 诊断字段改名：`GlowCompat.diagAlwaysDepth` → `diagForceOcclusion`，
  `LightFlare.dDrawAlwaysDepth` → `dDrawForceOcclusion`；`[lte] flare draw:` 的
  `depthFunc=` 读回**LEQUAL 才正常**，出现 `ALWAYS` 说明被别的模组覆盖回去了。

## 2. 散射：uniform 到底传没传（真机 + 真驱动都测了）

### 2.1 证据链（全部可复现）

`GlUniform.getTypeIndex`（1.20.1 字节码）只认 `int` / `float` / `matrix2x2|3x3|4x4`：

* 写 `"type":"vec4"` → `-1` → `upload()` 打 "type value (-1) is not a valid type"，什么都不发；
* 写 `"type":"float","count":64` → `dataType=FLOAT` → `uploadFloats()` → `glUniform1fv(loc, 64 标量)`
  打向 `vec4[16]` → `GL_INVALID_OPERATION`；
* `"float" + count 1..4 = vec4` 的提升（`ShaderProgram.addUniform` 里的 `count-1`）只在
  `count <= 4` 时生效，救不了数组。

`tools/ArrayUniformCheck.java`（新增，真驱动、1 秒、不开游戏）：

```
uLightPos[0] @38  uLightDir[0] @22  uLightColor[0] @6
A) glUniform1fv(64 floats) -> glGetError=0x502 (GL_INVALID_OPERATION)
   readback uLightColor[0] = (0.0, 0.0, 0.0, 0.0)      ← power 恒 0
B) glUniform4fv(16 vec4)   -> glGetError=0x0
   readback = (1.25,-0.5,4,6) / (0,0,-1,32) / (1,0.95,0.85,0.62)，第 15 槽同样正确
```

`glGetActiveUniform` 枚举（`tools/ShaderCompileCheck.java` 的同一套加载）：

```
12 个活动 uniform：Sampler0@0 Sampler1@1 uCone@2 uCount@3 uDebug@4 uInvViewProj@5
uLightColor[0] size=16 @6   uLightDir[0] size=16 @22   uLightPos[0] size=16 @38
uParams@54 uSurface@55 uTest@56
```

数组 size 真的是 16，且**用不带 `[0]` 的数组名查首地址就能拿到**——这就是现在的写法。

### 2.2 改法

* `light_scatter.json` 里**删掉**三个灯阵的声明（留着就会被发成 `glUniform1fv`，每帧一个 GL 错误）。
* `ScatterProgram.ensure()` 里链接后各查一次首地址：
  `GlUniform.getUniformLocation(program.getGlRef(), "uLightPos"/"uLightDir"/"uLightColor")`，
  任何一个 `-1` 就整条停用并 WARN（`uniform-array-missing:...`）。
* `uniforms()` 只把 `16*4` 个 float 装进常驻 `FloatBuffer`；`ScatterPass.draw()` 里
  `program.bind()` 之后调 `ScatterProgram.uploadArrays()`，**每个数组一次 `glUniform4fv`**
  （`RenderSystem.glUniform4(loc, buf)`，count = remaining/4 = 16）。bind 必须在前：
  uniform 写进的是当前绑定的程序；而 `drawWithGlobalProgram` 里那次 `apply()` 只会重绑同一个
  程序、重发 json 的 uniform，不碰这三个。
* 新增诊断：
  * 加载时一行 `[lte] scatter uniforms: uLightPos[0]=… uLightDir[0]=… … uDebug=…`（`-1` = 被优化掉/名字不符）；
  * 每 5 秒一行 `[lte] scatter upload: pos=(…) dir=(…) rgb=(…) cosIn=… cosOut=… power=… lamps=N`
    + `gl[0] pos=(…) dir=(…) rgb=(…) power=…`（**`glGetUniformfv` 从程序里回读**，不是我们以为传的；
    `power=0` 会附 "the GPU never got the lamps"）；
  * `/mtr_lte scatter status` 追加 `uniforms[…]`。

### 2.3 `dir` 的符号（约定，一次性对齐）

> **`uLightDir = 灯照射方向（朝车外，单位向量）`**

* `TrainLamps.Lamp.dir`：`place()` 里 `dir = -out`，`out` 是车体内向弦 → **dir 朝车外**（车头灯朝前、
  车尾灯朝后），单位长度，已含坡度与 `rot`。
* 着色器里扇面轴就是 **`+axis`**：
  * 表面项 `cosAngle = dot(axis, lamp -> P)`（`toPoint = -L`，`L = P -> lamp`）；
  * 体积项 `cosAngle = dot(midDir, axis)`，`midDir = mid - lamp`；
  * 轴向窗 `axial = dot(toMid, axis)`，灯前方 > 0；
  * 相位 `cosθ = ∠(dir, -rd)`（光沿 `+dir` 走，相机沿 `rd` 看），前向散射才对。
* 结论：**这套符号本来就是对的，不需要取反**；1.0.62 的"全黑"与符号无关（是强度恒 0、循环没进）。
  本轮把 `-LD` / `-L` 换成显式的 `toPoint` 变量并逐处写注释，防止以后再被改反。
  `[lte] scatter upload:` 打印的 `dir` 应当指向车头前方。

### 2.4 `debug surface / cone` 该看到什么

`surface` = (R = max N·L, G = 锥内权重, B = 距离衰减 × 窗口)，取"所有灯里最大的那一份"：

* **R**：灯在车头外侧、略高于地面 → 屏幕上的**地面（占大头）普遍 R > 0**；朝向灯的竖墙也有 R；
  背对灯的一面与天空为 0。整屏全黑才是失败（= 灯阵又没上去）。
* **G**：只在灯锥投到屏幕上的那片楔形里（外角约 57°），楔形外为 0 是正常的。
* **B**：光束长度内都很亮（衰减 × 窗口）。
* `cone` 的 **B（有灯）** 应当整屏 1（天空除外）；`normal` 不变（地面绿、天空黑）。

数学侧交叉验证（`node tools/scatter-math-check.js`，与 fsh 同一套公式）：灯前方 6 格、俯角照射时
地面 2 格处 `s=0.234`、5 格处 `s=0.102`，混凝土站台 5 格前 `scene 0.15 → 0.261`；
灯**后方** 6 格 `cos=-0.79 → spot=0`（说明符号确实有判别力，不是恒亮）。

## 3. 验收状态

* `gradle -p v1.20.1 compileJava` → **BUILD SUCCESSFUL**。
* 着色器在真驱动上编译 + 链接通过，全部 10 个名字有效，数组 size=16（同上）。
* `tools/ArrayUniformCheck.java` 复现旧路径失败、新路径成功（同上）。
* **未做**：真机跑一遍确认"隔着墙看不到灯晕 / 散射照亮地面"。需要存档里的亮灯列车，这部分只能
  靠 `[lte] flare draw: depthFunc=LEQUAL`、`[lte] scatter upload: … gl[0] … power>0`、
  `debug surface` 三色这三处读数确认。
