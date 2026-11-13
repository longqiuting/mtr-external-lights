# 屏幕空间散射（车灯在空气里的光雾）· 1.20.1

日期：2026-11-13　范围：**只做 1.20.1**
新包：`v1.20.1/src/main/java/dev/js/mtrheadlight/sss/`
新资源：`assets/mtrlte/shaders/core/light_scatter.{json,vsh,fsh}`

---

## 0. 一句话

世界画完之后跑**一次全屏 pass**：读主帧缓冲的深度，逐像素反投影出视线，对每盏点亮的车灯
求「相机→像素」这条视线穿过灯周围那团空气的长度与入射条件，加色写回颜色缓冲。几何一个顶点都不加，
所以墙 / 站台 / 车体会**按深度**把光雾切断——这正是几何光柱做不到、而 billboard 灯晕做不到的事。

---

## 1. 结构

| 文件 | 职责 |
|---|---|
| `sss/ScatterPass.java` | pass 本体：`WorldRenderEvents.LAST` 入口、灯数门、全屏四边形、GL 状态保存/还原、5 秒诊断行、`status()` |
| `sss/ScatterLights.java` | 收集灯：复用 `TrainLamps` + `HeadlightSwitch.isLit`，按到相机距离取最近 **16** 盏，池化候选以免每帧分配 |
| `sss/ScatterProgram.java` | 手写 `new ShaderProgram(...)` 加载 core shader、取 uniform 句柄、失败一次即停用 |
| `sss/DepthCopy.java` | 深度拷贝：查询源格式 → 建同格式深度纹理 + FBO → 每帧 blit |
| `shaders/core/light_scatter.*` | 顶点阶段只把 NDC 四边形传下去；片元阶段做全部积分 |

不在 `common/`：深度附件 blit、`ShaderProgram`、`GlUniform` 都是 1.20.1 的类型，下沉没有意义
（1.18.2 / 1.19.x 的 `ConeVolumeRenderer` 就是 20 行恒 `false` 的壳）。

---

## 2. 为什么在 `WorldRenderEvents.LAST`

Fabric 对 `LAST` 的定义正是这里需要的：「所有 framebuffer 写入已完成，但世界渲染还没拆掉」——
主帧缓冲仍然绑着，深度附件仍然是这一帧的深度，而 GUI / 手还没开始画。换成
`AFTER_TRANSLUCENT`（灯晕/光带所在的回调）就会与半透明排序、天气、云抢同一个 pass，
而且那时 `RenderSystem` 的视图矩阵还不是相机视图（Fabric 明确写了这一点）。

---

## 3. 深度从哪来，为什么必须拷

* 源：`MinecraftClient.getFramebuffer().getDepthAttachment()`（GL 纹理 id）。
* **不能直接采样**：我们正在往同一个 FBO 的颜色附件里画，深度附件也挂在这个 FBO 上；
  采样它就是读写同一附件（feedback loop，结果未定义）。所以每帧 `glBlitFramebuffer`
  `GL_DEPTH_BUFFER_BIT` 到自建纹理。
* 目标格式**查询**而不是猜：`glGetTexLevelParameteri(GL_TEXTURE_INTERNAL_FORMAT)`，深度格式
  不一致 blit 会被驱动拒绝（本机实测 `0x1902 = GL_DEPTH_COMPONENT16`）。纹理必须显式
  `NEAREST` / `CLAMP` / `COMPARE_MODE=NONE`：默认 mipmap min filter 会让深度纹理**不完整**，
  采样恒 0，整幅画面会被判成"贴脸"。
* **颜色附件从不读、从不拷**：pass 是加色的，读颜色附件就是 feedback；不需要它也能自限幅
  （见 §5 的 `1-exp`）。

## 4. 深度重建用的矩阵

```
viewProj  = ProjMat * View
pixel     = inverse(viewProj) * vec4(ndc.xy, depth*2-1, 1)      // 相机相对坐标
tHit      = |pixel|                                             // 深度==1（天空）时取 MaxRay
rd        = normalize(inverse(viewProj)*(ndc,1,1) - inverse(viewProj)*(ndc,-1,1))
```

`ProjMat` 取 `RenderSystem.getProjectionMatrix()`（就是产生这张深度的那个投影）。
`View` 有三个来源，按可信度依次尝试，**每个都先验证是不是刚体**（右下 0,0,0,1 且 |det R|≈1）：

1. `WorldRenderContext.matrixStack()` 栈顶（Fabric 文档：`LAST` 时已是相机视图）；
2. `RenderSystem.getModelViewMatrix()`（原版画世界时真正 apply 的那个）；
3. 用 `Camera.getPitch()/getYaw()` 重建 `Rx(pitch)*Ry(yaw+180)`（原版同一个构造，只差视角摇晃）。

这一层不是洁癖：视图矩阵错了不会报错，只会把整个世界重建成错的位置，光雾悄悄落在灯旁边。
会话里第一次成功时打一行 `[lte] scatter: depth reconstruction uses inverse(ProjMat * context-derived view)`，
报告的就是这三档里实际用了哪一档。

---

## 5. 散射积分（自己写的，不是 RTE 的）

对每盏灯（`L` 灯位，`d̂` 朝向，`R` 光雾半径，`len` 光束长度，`I` 强度）：

```
tC     = dot(L, rd)                       // 视线上离灯最近的点
perp2  = |L|² - tC²                        // 灯到视线的垂距平方  ← 规格里的"垂距"
if perp2 >= R² → 这盏灯对这一像素没有贡献
h      = sqrt(R² - perp2)
[t0,t1]= [max(tC-h,0), min(tC+h, tHit)]    // 有效区间：球内 ∩ 相机前方 ∩ 未被场景挡住
chord  = t1 - t0                           // ← 视线穿过这团空气的长度（积分近似的主体）
mid    = rd * (t0+t1)/2                    // 在这段的中点上求一次被照条件
dist   = |mid - L|
cone   = max( smooth(cos∠(mid-L, d̂); cosOuter→cosInner), 1 - smooth(dist/R) )   // 锥内 + 灯边光晕
window = max( 1 - smooth((axial - 0.6len)/0.4len), 1 - smooth(dist/R) )         // 光束末端不跳变
phase  = min( (1-g²) / (1+g²-2g·cosθ)^1.5 , 4 )      // Henyey-Greenstein，g=0.55，θ = ∠(d̂, -rd)
trans  = exp(-k · dist)                              // 灯→散射点之间的介质吸收
fall   = 1 / (1 + dist²/9)                           // 灯照介质的 ~1/d²
tau_i  = (chord/R) · cone · window · phase · trans · fall · I · 0.125
colour = 1 - exp( -Σ tau_i · gain )                  // 逐通道，gain = strength·1.6
```

要点：

* **为什么是 `chord` 而不是"沿轴步进"**：单次散射的辐亮度 ∝ 视线穿过的发光体积长度。弦长在
  正对灯心时最大、在球的轮廓处归零，天然给出柔和的球形衰减，而且一次算完，没有 8–16 步的循环
  （16 盏 × 16 步 = 256 次采样/像素，4K 下不可接受）。
* **为什么中点求一次**：这段弦通常只有几格，`cone/window/phase/trans/fall` 在弦上变化不大，
  取中点误差远小于视觉容忍度——换来的是每盏灯 ~40 次浮点、零次纹理采样。

色彩上 `1 - exp(-x)` 让每个通道独立饱和：亮灯不会把画面推成白块，深色场景里的车灯也不会
被场景色"吃掉"（因为根本不读场景色）。

---

## 6. GL 状态：这一段踩过坑，所以逐项还原

进来：`setShader(ourProgram)`、`setShaderTexture(0, depthCopy)`、`enableBlend` +
`blendFunc(SRC_ALPHA, ONE)`、**`disableDepthTest()`**、**`depthMask(false)`**、`disableCull()`。
出去（`finally`，无论 draw 是否抛）：`depthMask(true)`、`enableDepthTest()`、`defaultBlendFunc()`、
`enableCull()`、`setShader(GameRenderer::getPositionColorProgram)`、`setShaderTexture(0, 0)`、
`setShaderColor(1,1,1,1)`。

不还的后果是上一轮光锥工作里真实发生过的：**粒子集体消失**——之后画的东西继续用我们那个带深度
采样的程序、0 号单元上绑着我们的深度纹理、depthMask 还是关着的。

---

## 7. 1.20.1 core shader 手写加载的两个坑

1. **`new ShaderProgram(factory, name, format)` 的 name 不能带命名空间。**
   1.20.1 的构造函数里 id 是硬拼的 `new Identifier("shaders/core/" + name + ".json")`；
   传 `"mtrlte:light_scatter"` 直接 `InvalidIdentifierException`（两个冒号），传裸名字则去找
   `assets/minecraft/shaders/core/...`。Fabric 用 `ShaderMixin` 绕开了这一点，但那个 mixin
   只在 `this instanceof FabricShader`（= 通过它自己的 `CoreShaderRegistrationCallback` 创建）
   时生效，我们自己 `new` 出来的程序不吃这一套。
   **解法**：把 `ResourceFactory` 包一层——`minecraft:shaders/**` 的查询改写为
   `mtrlte:shaders/**`。vanilla 的 id 始终合法，我们的文件仍在 `assets/mtrlte/shaders/core/`，
   而且 `.vsh/.fsh` 也走同一个工厂（json 里的 `vertex`/`fragment` 由构造函数经同一工厂解析）。
2. **uniform 必须真的被着色器读到。** 驱动器会把没用到的 uniform 优化掉，然后 MC 打
   `Shader X could not find uniform named Y` 并返回 `null`——如果代码不检查，pass 会拿旧值画一整帧，
   表现为"效果偶尔不对"而不是报错。这里每个 uniform 都 `require()`，缺一个就整体停用并 WARN 一次。

其它：构造参数是 `(ResourceFactory, String, VertexFormat)`（**没有** drawMode / bufferSize 那套旧参数），
`VertexFormat` 用 `VertexFormats.POSITION`，json 的 `attributes` 必须与之对应（只有 `Position`）。
顶点阶段自己算 NDC，不用 `ModelViewMat`/`ProjMat`，避免和"世界矩阵已经被 pop 掉"这种时序问题纠缠。

---

## 8. 性能

* 灯数上限 **16**（着色器 `uLightPos[16]` 的硬上限，CPU 侧同样截断）。
* 每帧一次全屏四边形 + **一次深度 blit**；灯循环里**没有纹理采样**，只有点积/指数。
* **0 盏灯 → 直接 return**：不建 framebuffer、不拷深度、不绑程序、不设 uniform。空轨道开销为 0。
* 收集阶段：先按 `eff.beamDistance` 和 96 格双重门限过滤，再对剩余候选插入排序取前 16。
  候选池是静态复用的，热路径不分配。
* 品质旋钮是 `scatterRadius`（半径越小，球内像素越少）和 `scatterStrength`（只是指数上的增益）。

## 9. 光影（Iris / OptiFine）

`ShaderPackProbe.active()` 为真时**整个 pass 跳过**，只 WARN/INFO 一次。理由：光影自己拥有
framebuffer 与深度缓冲，我们再去 blit 它的深度只会拿到一张错的图（RTE beta.4 那条"取 Iris 深度"
的分支实际恒回退到原版主帧缓冲，等于它也没真正接通）。跳过是明确的行为，不是失败。

## 10. 实测（隔离实例 `temp/scatter-probe`）

```
[23:52:23] [Render thread/INFO]: [lte] scatter: program=ok id=mtrlte:light_scatter format=POSITION (loaded from the resource manager by ShaderProgram)
[23:52:23] [Render thread/INFO]: [lte] scatter: lights=0 program=ok depth=none frames=0 (shader compiled; the pass runs once a world with lit lamps is drawn)
```

* 实例：77 个第三方 mod + 本模组（`lte-1.0.57.jar`），`--gameDir temp/scatter-probe`，跑到标题画面。
* 日志里**没有** `Shader ... could not find uniform/sampler`、没有编译/链接错误指向 `light_scatter`
  （只有一条与本功能无关的 `rendertype_entity_translucent_emissive` 警告，来自别的 mod）。
* 该实例没有存档，所以**深度拷贝 + 绘制这一段没有在游戏里跑到**；数学已用
  `tools/scatter-math-check.js`（fsh 公式的 JS 移植，`node tools/scatter-math-check.js`）逐案验证：
  正对灯 0.80、偏轴 4 格 0.13、球边缘 0.04、灯朝反方向 0.07、灯在相机后 0.00、前方 3 格有墙 0.08、
  灯在墙前 0.80、两灯车头正面 0.91 / 偏轴 3 格 0.60。
  视觉验收（是否"真的在空气里有光"）仍需在真实存档里看一次。

## 11. 开关与命令

* `config/mtrlte.json`：`scatterEnabled`（默认 `true`）、`scatterStrength`（0.1–3，默认 1.0）、
  `scatterRadius`（1–24 格，默认 6.0）。
* 界面：**配置 → 光源 → 空气散射**（开关 + 强度同一行，半径下一行；只有全局值）。
* `/mtr_lte scatter` / `scatter status` / `scatter on|off`。
