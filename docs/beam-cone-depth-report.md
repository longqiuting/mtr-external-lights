# 车灯光锥：几何体 → 深度感知体积光（1.20.1）

日期：2026-11-09　范围：**只做 1.20.1**（1.18.2 / 1.19.x 暂不迁移）
产物：`桌面/lte-1.0.0+1.20.1.jar`，339,306 字节，md5 `6350ac562e7d027b68b6983721f1185d`，120 个 class

---

## 0. 结论先说（含未完成项）

| 项 | 状态 |
|---|---|
| 深度纹理来源 + 每帧拷贝 | **已实现、已跑通**（实测 `854x480 internal=0x1902`，blit 无 GL 错误） |
| 自定义 core shader（独立程序，不动共享 `position_color`） | **已实现、已编译链接**（`volume=active` 只在 program 非空时出现） |
| 沿视线 8–12 步 raymarch + 逐步深度判定 | **已实现**，`steps=10` 为默认，钳制 4–16 |
| 每帧 ≤8 束、距离 ≤64 格、就近优先 | **已实现**（本轮最后修正了「先到先得」的错误） |
| Iris 下自动降级 | **逻辑已接好，本轮未实测**（见 §5） |
| 原版「墙面处截断」的**可视证据** | **未取得**（见 §6，这是本次最大的缺口） |
| 实测耗时 | 稳态 CPU ~0.15–0.24 ms/帧；含 GPU 同步的采样帧 1.4–6.6 ms（见 §4） |

一句话：**管线全部接通并跑起来了，但「对着墙开/关遮挡」的对比截图没拍成**——两台机位下的画面里光锥都没出现，而同一时间窗口的计数是 `drawn=0 / beams=0`，说明渲染器**没被调用**（不是画错），所以那几张图既不能证明有效、也不能证明无效。原因与后续排查见 §6。

---

## 1. 实现结构

```
common/.../ConeVolumeBeam.java         一束光的纯数据（apex / axis / length / radius / rgb / alpha）
common/.../ProtoBeamCone.java          车灯位置、颜色、长度仍在 common；新增 emit() 分流：
                                       volume=true  → 交给版本本地渲染器
                                       volume=false → 旧的四层壳加色几何（原样保留）
v1.20.1/.../ConeVolumeRenderer.java    真正的实现：深度拷贝 + 代理网格 + uniform + 统计
v1.18.2|1.19.2|1.19.4/.../ConeVolumeRenderer.java   20 行壳，ready() 恒 false
v1.20.1/src/main/resources/assets/mtrlte/shaders/core/lte_cone_volume.{json,vsh,fsh}
```

分流只在 `ProtoBeamCone.emit()` 一处，两条路径拿到的是**同一组** apex/axis/length/radius，
所以运行期切换（配置开关 / 光影 / GL 失败）不会有几何跳变，唯一差别就是有没有遮挡。

---

## 2. 用的是哪个深度纹理

* 源：`MinecraftClient.getFramebuffer().getDepthAttachment()`（1.20.1 返回 GL 纹理 id，javap 已确认）。
* **不能直接采样**：那时它仍然挂在「正在绘制的 framebuffer」上，采样就是反馈环。所以每帧把它
  拷进我们自己的一张纹理：

  ```
  GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, fb.fbo)
  GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, ourDepthFbo)
  GlStateManager._glBlitFrameBuffer(0,0,w,h, 0,0,w,h, GL_DEPTH_BUFFER_BIT, GL_NEAREST)
  恢复 GL_FRAMEBUFFER = fb.fbo
  ```

* 目的纹理的 internal format **不是猜的**：先 `glGetTexLevelParameteri(..., GL_TEXTURE_INTERNAL_FORMAT)`
  读源纹理的真实格式，再用同一个格式建纹理、建 FBO（深度格式必须完全一致，否则 blit 被 GL 拒绝）。
  实机读到 `internal=0x1902`，即 `GL_DEPTH_COMPONENT16`，窗口 854x480。
* 纹理必须显式设 `MIN/MAG=NEAREST`、`WRAP=CLAMP`、`COMPARE_MODE=NONE`：默认的 mipmap min filter
  会让深度纹理**不完整**，采样恒为 0，那会把整束光判成「被遮挡」。
* 只有在本帧**真的有光束**时才做这次 blit（空轨道零成本）。
* 失败自愈：blit / 纹理创建 / uniform 查找任一步出错，`failed` 永久置位，立刻回退旧几何并打一行 WARN。

## 3. shader：文件、uniform、算法

文件：`assets/mtrlte/shaders/core/lte_cone_volume.{json,vsh,fsh}`，注册 id `mtrlte:lte_cone_volume`
（`CoreShaderRegistrationCallback`）。**新程序**，`GlowCompat` 里共享的 `position_color` 一行没动。

uniform：

| 名字 | 类型 | 含义 |
|---|---|---|
| `Sampler0` | sampler2D | 第 2 节那张深度拷贝（窗口空间深度，`.r`） |
| `BeamApex` | vec4 | 锥顶，相机相对坐标 |
| `BeamAxis` | vec4 | 单位轴（锥顶 → 尖端） |
| `BeamParams` | vec4 | x=长度, y=tan(半角), z=步数, w=密度(0.6) |
| `ModelViewMat` / `ProjMat` | mat4 | MC 每帧自动填（`VertexBuffer.drawInternal`） |

顶点色携带 rgb + alpha 预算（即 `beamConeAlpha`），不用额外 uniform。

片元算法：

1. 视线与**有限圆锥**解析求交（二次方程 + 轴向 clamp 到 `[0, length]`），得到体积内的 t 区间；
   顶点网格只是**覆盖代理**（锥侧面 fan + 底面圆盘，12 段），只负责让覆盖到的像素各跑一次片元。
2. 区间内走 `steps` 步（默认 10，抖动抖动步进起点抑制条带）。
3. 每步：算径向 `(1-r²)²`、轴向 `exp(-2u)(1-u)`（u=轴向/长度，尖端恰好归零）→ 体积分权重 `×dt`。
4. 每步把采样点投影回屏幕，取深度拷贝比较：

   ```
   float nearP = ProjMat[3][2] / (ProjMat[2][2] - 1.0);   // = 近平面 0.05，已用 JOML 实测校验
   float bias  = 0.75 * nearP / max(t*t, 0.25);           // 0.75 格的世界空间容差 → 窗口深度
   float vis   = smoothstep(0.0, bias, sceneDepth - sampleDepth);
   ```

   `bias` 必须随距离平方缩放：窗口深度每格的增量 ≈ near/d²（16 格时 1.9e-4，64 格时 1.2e-5），
   固定常数在远处会大得离谱（光束穿墙）。
5. `alpha = (1 - exp(-sum * 0.6)) * vertexColor.a`，加色混合（json 里 `srcalpha → one`，由
   `GlBlendState` 在 bind 时应用，所以 `RenderSystem` 不用再设 blendFunc）。

因为是**积分**，亮度取决于视线穿过多少空气：正对光柱最亮、掠射边缘自然变淡，这就是“光柱里有空气”的来源。

## 4. 性能与实测

* 距离 ≤64 格（按**光束自己的 apex** 判定，不是车头——长编组列车车头可能在 100 格外而眼前这节车就在旁边）。
* 每帧 ≤8 束，**就近优先**（先按 apex 到相机距离排序再截断）。
* `beamConeSteps` 默认 10，钳制 4–16；每步一次深度采样 + 一次投影，是唯一的质量/开销旋钮。
* 诊断行（5 s 一条，INFO，由 `ConeVolumeRenderer` 产生）：

```
[lte] cone: depth copy 854x480 internal=0x1902 fbo=6
[lte] cone: steps=10 occluded=0/6 (0%) ms=10.88 avgMs=10.88 frames=1   beams=2   skipped=0 depthTex=66
[lte] cone: steps=10 occluded=0/0 (0%) ms=1.39  avgMs=0.83  frames=2   beams=4   skipped=0 depthTex=66
[lte] cone: steps=10 occluded=0/0 (0%) ms=5.78  avgMs=0.24  frames=128 beams=256 skipped=0 depthTex=66
[lte] cone: steps=10 occluded=0/0 (0%) ms=2.74  avgMs=0.18  frames=149 beams=298 skipped=0 depthTex=66
[lte] cone: steps=10 occluded=0/0 (0%) ms=6.61  avgMs=0.19  frames=190 beams=566 skipped=0 depthTex=66
```

读法：

* `beams` 是真实执行的 `drawBeam` 次数（256/128 帧 = 每帧 2 束），所以**绘制确实发生了**；
* `avgMs` 是 CPU 侧（建代理网格 + blit）每帧成本，稳态 **0.15–0.24 ms**；
* `ms` 是**统计窗口那一帧**的耗时，那一帧末尾加了 `glFinish()`，所以它包含 GPU 时间：
  实测 **1.4–6.6 ms**（这些光束在远处、屏幕占比很小；贴脸满屏的光柱会更高，未测）；
* `occluded=K/T` 是**真的深度比较**：统计帧把深度图缩采 48×48 读回，CPU 用与 shader 相同的投影/容差
  对每束光的轴上采样点做遮挡判定。上面的 `0/6`、`0/0` 说明当时视野里的采样点都没被挡（相机在轨道上，
  前方没有遮挡物），不是“没做测试”——没做测试会出现 `skipped` 或 `allSamples=0`。
* **`occluded` 从未测出非零值**，这也是 §6 缺口的一部分。

## 5. Iris / 光影下的降级

* 检测：既有的 `ShaderPackProbe.active()`（Iris `IrisApi#isShaderPackInUse` → OptiFine `Shaders#isShaderPackInUse`，
  反射 + 1 s 缓存，rail light 那套已在用）。
* 行为：`active()` 为真时 `ConeVolumeRenderer.ready()` 返回 false，`ProtoBeamCone` 走**旧的加色壳层几何**，
  完全不碰深度拷贝、不绑我们的程序。状态字符串会打印 `volume=shaderpack`。
* 其它降级入口同样只回答 false：`volume=occlusion-off`（`/mtr_lte cone occl off`）、
  `volume=shader-missing`（资源包加载失败/程序没链接）、`volume=failed(...)`（GL 步骤被拒）。
* **本轮没有实测 Iris**：MakeUp 包已就位（`shaderpacks/MakeUp-UltraFast-9.3c.zip`），
  `config/iris.properties` 仍 `enableShaders=false`。旧壳层路径在 Iris+MakeUp 下可见是**上一轮已验过**的
  （`docs/img/proto-cone-iris-makeup.png`），而这次改动没有碰那条路径，所以「不崩、退化成旧效果」的
  依据是「降级目标本身就是已经验过的路径」＋「降级分支只读一个布尔量」。这不算实测。

## 6. 未解决：为什么没拍到“截断”

测试装置（`ConeTest`，已按交付要求删除）做的事：找最慢的一列车 → 在车头前 6 格 `/fill` 一根 3×7×3 石柱
→ 侧面 11 格、正面 7 格各拍「遮挡开/关」两帧 → 拆柱 → 自动退游戏。

实机跑了两轮，**三次机位都拍到石柱和列车，但画面里没有光锥**，同时窗口计数是：

```
[lte-proto] cone: calls=644 trains=4969 models=1288 noModel=3681 noSlot=0 drawn=0 quads=0 probe=0 ...
[lte] cone: steps=0 occluded=0/0 (0%) ms=0.00 ... frames=0 beams=0 depthTex=-1
```

`frames=0 / beams=0` 说明 `ConeVolumeRenderer.render()` 根本没被调用 → `BEAMS` 是空的 → 不是 shader 画错，
是**光束没被发出**（`drawn=0`）。据此盲改了两处、**改完没有再进游戏**：

1. 距离闸门原来挂在**车头**上：19 节编组的车头可能在 100 格外，而眼前这节车上的灯就在旁边 →
   改成按光束自己的 apex 判定（`emit()` 内）。
2. 8 束上限原来是**先到先得**：先枚举到的（远的）占满名额，近的光束被丢掉 → 改成收集后按距离排序取最近 8。

还有一个**未排除**的可能：`TrainPose.ofShared(train, reversed).front()` 对长编组/静止列车返回的位置与
`ProtoBeamCone` 循环里算出来的不一致（测试脚本里同一次调用给出的车头位置是对的：相机距车头 12.5 格，
理论上应该通过 64 格闸门）。下一轮的第一件事应该是把 `cNear/cFar` 打出来（这两个计数器已经加进
`[lte-proto] cone:` 行：`near=` 车头在 64 格内的列车数，`far=` 被 apex 闸门丢弃的光束数），一行就能定性。

## 7. 代码开关 / 命令

`config/mtrlte.json`（CONFIG_VERSION 升到 10，老文件会自动补键）：

```json
"beamConeAlpha": 0.16,
"beamConeOcclusion": true,
"beamConeSteps": 10
```

* `/mtr_lte cone` → `occlusion=… steps=… volume=active|shaderpack|shader-missing|occlusion-off|failed(...)`
* `/mtr_lte cone occl on|off` → A/B 开关（立刻生效、写盘）
* `/mtr_lte cone steps 4..16`
* 调试探针：`-Dlte.coneprobe=true` 才画相机前方的固定探针锥，**默认不画**（交付前从 `PROBE=true` 改掉）。
* 测试类 `ConeTest` 已删除、entrypoint 已移除。仓库里仍有更早的 `RainTest` / `BeamLightTest`
  （不是我这次加的，未动），发版前应一并处理。

## 8. 下一步（按性价比排序）

1. 进游戏看一眼 `near=` / `far=`：`near>0, far>0, drawn=0` ⇒ apex 闸门或 `pose.front()` 问题；
   `drawn>0` 而画面没有 ⇒ shader 输出 alpha≈0（先怀疑深度拷贝全是 0 / uv 上下颠倒）。
2. 用 `-Dlte.coneprobe=true` 的探针锥做存在性探针（它一定在相机前方，不依赖列车数据）。
3. 拍成对比图后，再跑一次 Iris + MakeUp（把 `enableShaders` 打开）确认 `volume=shaderpack` 且画面回到旧效果。
