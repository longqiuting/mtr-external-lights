# 灯晕深度 + 散射自诊断 · 1.20.1

日期：2026-11-14　范围：**只改 1.20.1**（`common/` 一行未动；`/mtr_lte scatter debug` 的状态只在内存里）

用户反馈（本轮要查清的两条）：

1. 不开光影时**灯晕只有隔着轨道/方块才看得到，直接看反而看不见**；开了光影正常。
2. **表面照亮（`scatterSurface`）完全看不到。**

---

## 0. 一句话

两条路都先变成「自己会说话」：灯晕每 5 秒打一条**从 GL 读回来**的 `[lte] flare draw:`（真实
`depthFunc` / 真实 blend / 哪一趟画的），散射 `scatterEnabled=true` 时**无条件**每 5 秒打一条
`[lte] scatter pass:` + `[lte] scatter light0:`（frames / 灯数 / 两份拷贝 / litPx / 矩阵来源 /
停止原因）。同时修掉两个高概率缺陷，并加了 `/mtr_lte scatter debug surface|normal|cone` 三块纯色
可视化，用来一眼看出表面项的三个因子（N·L / 锥内 / 距离衰减）里哪一个是 0。

---

## 1. 从字节码里坐实的两件事（javap 1.20.1 映射 jar）

**① `GlStateManager._depthFunc` 是带缓存的**，值相等时**根本不调 `glDepthFunc`**：

```
public static void _depthFunc(int);
   3: iload_0
   4: getstatic  DEPTH:DepthTestState
   7: getfield   DepthTestState.func
  10: if_icmpeq  24          <-- 相等就直接 return
  13: ... putfield  func
  20: iload_0
  21: invokestatic GL11.glDepthFunc
  24: return
```

`RenderSystem.depthFunc(int)` 只是转调它（`RenderSystem.depthFunc → _depthFunc`）。所以只要有别的
东西直接调过 `glDepthFunc`（Sodium / Embeddium 这类优化模组到处都是），缓存就和真实 GL 状态脱钩，
**「我们设了 ALWAYS」什么都不会发生**。这就是旧代码
`RenderSystem.depthFunc(GL_ALWAYS)` 有可能完全无效的原因，也正是本轮改用直调 GL 的依据。

**② 混合状态在 `ShaderProgram.bind()` 里被重新下发**，而且 `GlBlendState.enable()` 还自带一份
静态缓存（`activeBlendState == this` 就 return）：

```
ShaderProgram.bind():  this.blendState.enable();
GlBlendState.enable(): if (activeBlendState == this) return;  ... else RenderSystem.disableBlend();
```

`position_tex_color.json` **自带 blend 段**（`add / srcalpha / 1-srcalpha`），`light_scatter.json`
**没有 blend 段**（= `blendDisabled`，`dst = src`）。所以我们「draw 前设的加色」从来不代表「draw 时
生效的」，一切以 `glGetInteger` 读回来的为准 —— 这也是新日志里 `blend=` 字段的来源。

---

## 2. 灯晕：做了什么

### 2.1 深度：直调 GL，并读回证据

`GlowCompat.drawTextured(..., alwaysDepth=true)`：

* 进来先读一次真实 `GL_DEPTH_FUNC`（`diagDepthFuncOnEntry`，日志里的 `entered=`）；
* `forceDepthFunc(GL_ALWAYS)`：**先 `GL11.glDepthFunc` 直调**，再用「先写另一个值再写目标值」把
  `GlStateManager` 的缓存推开、对齐 —— 否则结束时还原 `LEQUAL` 会因为「缓存里本来就是 LEQUAL」被
  跳过，把 ALWAYS 留在硬件上，后面整个世界的深度测试全废；
* draw **之后**、还原之前读回 `GL_DEPTH_FUNC` / `GL_BLEND_SRC_RGB` / `GL_BLEND_DST_RGB` /
  `GL_BLEND` / `GL_CULL_FACE` / 实际绑定的纹理 id（`texId=`）；
* 结束时按「进来时那个值」还原（不是硬写 LEQUAL）。

### 2.2 时机：只画晚趟（`AFTER_TRANSLUCENT`），并且把两趟的计数打出来

旧规则是「`ShaderPackProbe.active() == latePass` 才画」—— 也就是**不开光影时画在早趟
（`AFTER_ENTITIES`）**。早趟之后世界还要画一大段：半透明层、粒子、天气，以及 MTR 自己的车体后段
几何。**`GL_ALWAYS` 只赢深度缓冲，赢不了绘制顺序**：一个不透明三角形在灯晕之后画上来，那块像素
就是三角形的颜色。于是「灯就在眼前、屏幕上是车体」而「透过轨道缝隙、隔着别的方块看，反而看得到
那一圈光」—— 用户描述的现象逐字对应，而光影包路径（旧代码里只有晚趟在画）用户看到是正常的。

因此：两趟回调都收，**只有晚趟画**（`if (!latePass) return;`），绝不重复画。日志里
`passes=early 240/0 late 240/240` 会把「谁被调了几次、谁真的画了几次」直接摆出来。

### 2.3 新日志（每 5 秒，和 `[lte] flare:` 同一窗口）

```
[lte] flare draw: quads=6 depthFunc=ALWAYS blend=ADD cull=off tex=ok latePass=true passes=early 240/0 late 240/240 texId=27 entered=LEQUAL pack=false
```

| 字段 | 判读 |
|---|---|
| `quads` | 本窗口最后一次 draw 写进去的四边形数 |
| `depthFunc` | **光栅化那一刻**的真实 `GL_DEPTH_FUNC`。`ALWAYS` = 深度确实没在挡；仍是 `LEQUAL` = 设了没生效（缓存/被覆盖） |
| `entered` | 进入 `drawTextured` 时上一位调用者留下的值 |
| `blend` | `ADD`(SRC_ALPHA,ONE) / `ALPHA`(SRC_ALPHA,1-SRC_ALPHA，json 自带的那个) / `off` |
| `tex` / `texId` | 贴图状态（`ok`/`missing`/`notuploaded`）/ 槽 0 上真实的 GL 纹理 id（`0` = 空） |
| `latePass` | 这次 draw 是晚趟吗 |
| `passes` | 两趟回调 `被调/画了` 的次数 |
| `pack` | 当前是否有光影包 |

---

## 3. 散射：做了什么

### 3.1 无条件 5 秒行（这一条修的就是「进世界后一条日志都没有」）

旧的 `logIfDue()` 藏在 `collect()` 之后的几道闸后面（光影包 / 程序没编译 / 没灯），任何一道拦下整
行就消失 —— 「没有日志」和「没有效果」看起来一模一样。现在心跳打在 `render()` 的**最前面**，只要
`scatterEnabled=true` 就每 5 秒一行：

```
[lte] scatter pass: frames=300 lights=4 depth=1920x1080 ok color=1920x1080 ok blend=replace litPx=≈15234 matrix=context calls=300 maxLights=4 avgMs=0.42 surface=on debug=off pack=false reason=ok
[lte] scatter light0: pos=(1.23,-0.50,4.00) dir=(0.00,0.00,-1.00) rgb=(1.00,0.95,0.85) power=0.62 radius=6.0 beam=32.0 coneDeg=20.0/56.6 lamps=4 depthErr=ok
```

* `frames=` 本窗口真的画了几帧；`calls=` 回调被叫了几次（`calls>0 frames=0` 就说明是闸门问题，
  不是 pass 不存在）；
* `reason=` 一句话给名字：`ok` / `no-lights` / `shader-pack(no-framebuffer-access)` /
  `depth-copy-failed(err=0x…)` / `color-copy-failed` / `program=failed(…)` /
  `off(scatterEnabled=false)` / `exception:X`；
* `depth=`/`color=` 尺寸 + 最近一次 blit 的 `glGetError()`（`depthErr=`，`0` = 干净）；
* `litPx≈N` 现在**自己每 5 秒量一次**（覆盖写进输出 alpha，96×54 降采样回读，一次 20 KB），不再
  需要先 `/mtr_lte scatter status`；
* `matrix=context|render|rebuilt`：深度重建用的视图矩阵来自哪个候选（三种候选的既有逻辑没变，只是
  名字从 `renderSystem/camera` 改成了 `render/rebuilt`）；
* `light0`：第一盏灯真的送了什么（相机相对的 pos、朝向、rgb、power、半径、beam），`coneDeg=` 是
  `cosInner/cosOuter` 反算的角度（内角必须**小于**外角，`20.0/56.6` 是对的）。

### 3.2 三块纯色可视化：`/mtr_lte scatter debug surface|normal|cone|off`

状态只在内存里（和 `/mtr_lte lighttest` 一样，不写 config）。着色器新增可选 uniform `uDebug`
（缺失时只警告，不关 pass）。

| 模式 | 屏幕 | 怎么读 |
|---|---|---|
| `surface` | R = max(N·L)，G = max(锥内权重)，B = max(距离衰减 × 窗口) | 三色都在 = 三项都过；只有红 = 法线对但灯照不到这里（锥/朝向反了）；只有绿 = 锥对但 `max(N·L,0)` 把法线截成 0；只有蓝 = 距离对而前两项之一是 0 |
| `normal` | RGB = 法线编码（地面朝上 → 绿），黑 = 天空，洋红 = `normalValid=false`（轮廓伪影，这些像素永远没有表面项） | 整屏洋红 = 法线判定否掉了有效像素；整屏黑 = 深度重建出来的 P 是错的 |
| `cone` | R = 灯轴与「灯→像素」的余弦，G = smoothstep 后的锥内权重，B = 至少被一盏灯看到 | B=0 = 灯列表空/像素在灯后面（与锥角无关）；B=1 且 G=0 = 灯确实指向别处 |

### 3.3 顺带修的一个 NaN 源

`vec3 axis = normalize(dir.xyz)` 在 `dir == 0` 时是 NaN，NaN 进 `axis` 之后锥、体积、相位全是
NaN，整条 pass 变成「什么都没画」，而日志里每个字段都正常 —— 这是「表面项完全看不到」最难查的
一种。现在退化方向回退成「朝相机」（`dir == 0` 时 `-lamp/|lamp|`），不产出 NaN。

---

## 4. 还没定论的（下一轮用新日志判定）

1. **灯晕的 `blend=` 到底是 `ADD` 还是 `ALPHA`。** `GlBlendState.activeBlendState` 的静态缓存决定
   了它：如果上一帧最后一个 bind 的就是 `position_tex_color`，`enable()` 会 early-return，我们 draw
   前设的加色侥幸留下（`ADD`）；一旦中间插了别的程序，json 的 `ALPHA` 就会盖掉它。若日志里出现
   `ALPHA`，正解是给灯晕一条**自己的 core shader**（json 里写死 `add/srcalpha/one`），而不是继续
   跟 `position_tex_color` 抢状态。
2. **表面项是「没跑」还是「跑了但某一项是 0」。** `frames=0` → 看 `reason=`；`frames>0` 而
   `litPx≈0` → 开 `debug surface` 看是哪一项，`debug normal` 看深度重建出的法线是否可用。
3. `avgMs` 与 `litPx` 每 5 秒一次回读对帧时间的影响（20 KB 回读，正常路径仍然零回读）。

---

## 5. 改动文件

| 文件 | 改了什么 |
|---|---|
| `GlowCompat.java` | `forceDepthFunc()`（直调 GL + 缓存对齐）、`diag*` 读回字段、draw 后读回真实 GL 状态、按进入值还原深度函数 |
| `LightFlare.java` | 只画晚趟；两趟 `被调/画了` 计数；`captureDraw()`；新的 `[lte] flare draw:` 行；`funcName()`/`blendName()` |
| `RenderHooks.java` | 一条注释（灯晕只认晚趟）+ 异常行改名 `[lte] scatter: pass threw` |
| `sss/ScatterPass.java` | `heartbeat()` 无条件 5 秒行、`reason=` 全程记录、`describeFirstLight()`、`depthText()`/`colorText()`/`blendText()`、`debugMode` + `setDebug()`、`litProbeDue()` 自动量 litPx、`matrix` 名字归一 |
| `sss/ScatterProgram.java` | 可选 `uDebug`（缺失只警告）+ `debugAvailable()` |
| `sss/DepthCopy.java` | `lastError()`（blit 后的 `glGetError`） |
| `light_scatter.fsh` | `uDebug` 与三个 debug 输出；debug 累加器；`axis` 退化保护 |
| `light_scatter.json` | `uDebug` |
| `Commands.java` | `/mtr_lte scatter debug [off\|surface\|normal\|cone]` |
| `docs/COMMANDS.md` | 两行新日志、`status` 新字段、三个 debug 模式的读法 |

编译：`gradle -p v1.20.1 compileJava` **BUILD SUCCESSFUL**。
GLSL 这一轮没能在无 GL 上下文的环境里过一遍真驱动（上一轮的 uniform 清单是真驱动验证过的）；如果
`light_scatter` 编译失败，日志会直接给出 `program=failed(…)` 与原因，不会静默。
