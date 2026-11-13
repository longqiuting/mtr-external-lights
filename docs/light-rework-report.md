# 车灯重做：去光柱 / 按 RTE 思路重算灯位 / billboard 灯晕 + 真实照亮 / 移除 LDL

日期：2026-11-13　范围：**只改 v1.20.1**（`common/` 里被四个版本共用的文件也改了，因为它们就是同一条代码路径；1.18.2 / 1.19.2 / 1.19.4 未移植，见 §8）。
编译：`gradle -p v1.20.1 compileJava` **BUILD SUCCESSFUL**（含 `--rerun-tasks` 全量重编）；
`common/` 的改动是向后兼容的，`gradle -p v1.19.4 compileJava` 同样 **BUILD SUCCESSFUL**（未移植的版本没被改坏）。

---

## 0. 一句话结论

车灯不再是"从车头射出的一个锥/一条光带"，而是 RTE 那套拆法的等价实现：
**在模型自己的灯位画一张面向相机的加色光斑（灯晕），地面/墙上的亮光由我们自己的真实方块光照（`BeamBlockLight`）提供**。
灯位不再取"轨道点在车脸上 + 固定高度"，而是取**车型自己的灯箱**（模型 LIGHT 部件 → 内置灯位表 → 尺寸兜底），
并有 `docs/rte-beta4-analysis.md` §2.4 的全部位姿约束（车身弦 `yaw+π`/`pitch+π` 等价物、`y=1` 轴不参与、局部轴 `(x,-y,-z)` 对齐）。
LDL 在 1.20.1 的构建路径里**符号级为 0**。

---

## 1. 光柱：整体删除

| 删除物 | 位置 | 说明 |
|---|---|---|
| `ConeVolumeRenderer` | `v1.20.1`（整文件） | raymarch 体积光 |
| `Ldl4Backend` / `MtrHeadlightDynamicLights` | `v1.20.1`（整文件） | LDL 4.x 后端与 entrypoint |
| `ProtoBeamCone` 的 volume 分支 | `common` | `ConeVolumeRenderer.ready/render/status/init` 四处调用全部移除，`pathName()` 恒 `"shell"`；类本身保留给 1.18/1.19 模块 |
| 渲染注册 | `v1.20.1/RenderHooks` | `BeamGlow.render` / `ProtoBeamCone.render` 两个 pass 全部换成 `LightFlare.render` |
| 命令 | `v1.20.1/Commands` | `/mtr_lte cone …`、`/mtr_lte debug cone|coneSource|coneBoost` 全部删除，换成 `/mtr_lte flare [status]` |
| 配置行 | `v1.20.1/ConfigWriteTarget` + `ParameterScreen` | `BEAM_CONE` 常量/行/标签删除；`LIGHT_BAND` 行改为**灯晕**，`DYNAMIC_LIGHT` 行改为**真实照亮**（`opt.flare.on/off`、`opt.dynamicLight.*` 文案同步，en/zh 都改） |

`BeamGlow`（贴地光带）与 `ProtoBeamCone` 的**类仍在 common**，因为 1.18.2/1.19.x 的 `RenderHooks` 还引用它们；
1.20.1 不再有任何调用点（`grep -rn "BeamGlow\.\|ProtoBeamCone\." v1.20.1/src` 为空）。
`HeadlightConfig.beamCone` 等字段保留并标 `@Deprecated`（旧配置文件能原样读回），1.20.1 的 UI 不再暴露。

---

## 2. 灯位重算（重点）

### 2.1 数据来源：四级优先链，全部走 `TrainLamps`

```
declared  lte_vehicle_lights.json / 编辑器 (VehicleLightOverrides)   ← 作者意图，最高
model     DynamicTrainModel.partsInfo × properties.parts[].stage     ← 资源包车型（VehicleLights 组名+几何）
builtin   builtin_lights.json（95/114 个内置车型，离线从 jar 量的灯箱）
default   车体尺寸估算（±0.36·宽，高 0.85）
```

`common/.../TrainLamps.java`（新）把任意一层产出的 `Resolved{lateral, height, inset, rot, color, alpha, length, width}`
换算成世界坐标；`LightFlare`（画）和 `LightController`（照亮）读的是**同一个列表**，所以灯晕与地面亮斑不可能来自两个位置。

**关键改动**：旧路径 `LightController.headSegment()` 用的是
`pose.front() + headOffset + heightOffset`——那是**轨道点**，不是灯。任何车的灯都被画在车脸外 0.6 格、离轨 0.85 格处；
内置车型（没有 `DynamicTrainModel`）更是全部拿同一个通用点，那些"很多车没有光柱"就是这个原因叠加光柱开关默认关。
现在 `EndLight.ofSegment()` 直接吃 `TrainLamps` 算出的两点。

### 2.2 位置公式（矩阵 / 部件 / 坐标变换）

```
car    = head ? 0 : cars-1                                 // 头灯属于行进方向第一节车（TrainPose 已折进 reversed ^ swapEnds）
face   = head ? pose.carStart(car) : pose.carEnd(car)       // 该车端面的轨面点（world）
out    = ±pose.carBodyForward(car)                          // 单位向量，车身弦（含坡度）
right  = normalize(out × (0,1,0))                            // 水平横向，灯永不着滚
pos    = face + right·lateral + (0,height,0) − out·(clamp(inset,−2,1.5) + 0.08)
dir    = out（若条目声明了 rot，再绕 up 转 yaw、绕 right 转 pitch）
```

- **矩阵**：没有自己拼 4×4。`carBodyForward(car)` 就是 MTR `Train#calculateCar` 用来旋转车体的那根弦（两个端面连线），
  所以灯的基准方向与玩家看见的车体**同源**；`reversed` 与 `swapEnds` 已经在 `TrainPose` 里折成一个布尔，几何里没有第二处换端分支。
- **取哪个部件**：资源包车型取 `partsInfo` 里组名命中 head/tail 词表、且落在该车节端面带内的组，
  横向把该面所有灯箱**聚类成一个灯一盏**（左右成对就是两个灯，绝不取均值）；内置车型取 `builtin_lights.json`
  里该 id 的 head/tail 灯箱（离线用捕获型 `VertexConsumer` 量的真实顶点，见 `docs/builtin-light-table.md`）。
  这两级的 `lateral/height/inset` 都由 `VehicleLights.Slot` / `BuiltinLights.slot` 给出。
- **坐标变换**：相当于 RTE 的"平移到车心 → `yaw+π` → `pitch+π` → 绕 `y=1` 滚转 → 局部 `(x,−y,−z)`"，
  但用我们自己的量表达：`(lateral, height, −inset)` 是**车体局部**三分量，`right/up/out` 是这三根轴的世界基，
  于是车体怎么转灯就怎么转；不需要侧倾角，因为灯的位置本来就由 MTR 渲染用的同一根弦决定（`y=1` 轴问题只在手工拼矩阵时出现）。

### 2.3 最小可见性（"必须看得见"）

| 规则 | 值 | 为什么 |
|---|---|---|
| 灯高下限 | 0.30（低于则取 `heightOffset`=0.85） | 灯条建模在地板高度、或模型 y 原点不是地板时不会埋在车里 |
| 灯高上限 | 3.30 | 车顶灯带不是头灯 |
| 横向上限 | ±2.4 格 | 后视镜/标志灯混进灯组时不飞出车侧 |
| inset 允许**负数** | −2.0 … +1.5 | 内置车型的鼻锥常常比 MTR 的 `spacing` 长（A-train 灯箱在 14.06 格，24 格车脸只在 12 格）。旧代码 `clamp(…,0,…)` 把灯压在车脸内侧，灯晕被车头挡住 = 看不见。现在负数表示"在车脸前方"，最多前 2 格 |
| 兜底 | 每条车都至少 4 盏（左右×头尾） | 原版 114 个车型里 19 个没有灯箱数据（矿车/船/缆车），也照样出光 |

---

## 3. 渲染：billboard 灯晕

`v1.20.1/.../LightFlare.java`（新，~330 行）

```
facing  = −dot(dir, normalize(lamp − camera))          1 = 灯正对相机
world   = base · (0.45 + 1.4·facing³)                  base = config.beamFlareSize（默认 0.9）
pixels  = world / (2·d·tan(fov/2)) · screenHeight
world  *= max(1, minPx / pixels)                       minPx = config.beamFlareMinPx（默认 6）
alpha   = beamAlpha · lamp.alpha · (0.35+0.65·facing³) · distanceFade(d)
```

- **面向相机**：`right = view × up`、`up = right × view`，屏幕竖直轴锁世界竖直 → 灯晕不滚。
- **距离**：`d > beamDistance` 直接不画；从 `0.7·beamDistance` 起按 `(1−t)²` 淡出。
- **最小像素尺寸**：投影后不足 `minPx` 就等比放大世界尺寸——远端站台"灯缩成没有"就是这么修的。
- **深度**：加色（`SRC_ALPHA, ONE`）、开深度测试、关深度写、关 cull；每个四边形沿视线朝相机拉最多 0.9 格，避免与灯壳 z-fighting。
- **一次 draw**：所有灯写进同一个 `BufferBuilder`，每帧一次 `BufferRenderer.drawWithGlobalProgram`。
- 与旧光带相同的两条硬规则：影子/光影二选一 pass（`ShaderPackProbe`）、`matrixStack` 已含相机平移所以顶点用相机相对坐标。

### 3.1 贴图：程序化生成，不用 RTE 的任何资源

`tools/gen-light-flare.js`（新）生成 `v1.20.1/src/main/resources/assets/mtrlte/textures/vehicle/light_flare.png`
（128×128 RGBA，3 865 字节），轮廓：

```
core  = exp(-(r/0.17)²)
halo  = 0.38 · exp(-(r/0.52)²)
edge  = max(0, 1 − r⁴)
alpha = min(1, core+halo) · edge          RGB 纯白（颜色由顶点色给，贴图只给形状）
```

生成器自带自检（解码自己的像素并比对公式）：

```
wrote .../light_flare.png (3865 bytes, 128x128 RGBA)
centre alpha=255, corner alpha=0
  r=0.00 a=255 (expected 255)   r=0.25 a=110 (expected 106)
  r=0.50 a=37  (expected 36)    r=0.75 a=8   (expected 8)
  r=1.00 a=0   (expected 0)     → profile OK
```

PNG 头另行校验（`89 50 4E 47`、IHDR 128×128、depth 8、colour type 6）；叠在深灰底上肉眼确认是
"亮核 + 宽光晕 + 平滑衰减、无硬边"。**没有**引用 `vehicle_light_flare.png` 或任何 RTE 资源（ARR）。

---

## 4. 真实照亮（保留并在默认开）

- `BeamBlockLight` 仍是唯一"照到方块上"的路：把灯束写成 8 double/束的楔形，`BeamLightMixin` 在区块 mesh 烘焙时抬高 block-light nibble。
- 楔形原点从 `TrainLamps.centre/heading/wedgeLength` 来（该端所有灯的均值），所以**照亮的地方就是灯的位置**。
- `BeamRelight` 的闸门由 `cfg.beamBlockLight`（默认 `false`）改为默认 `true`；玩家看到的开关是 **真实照亮**（`dynamicLight`）。
- `RailDynamicLight` 重写：不再反射查第三方引擎，只取 `RailLightState.ownLevel`（本模组的灯束），轨道烘焙因此只跟随我们的车灯。

---

## 5. LDL 移除

删除：`Ldl4Backend.java`、`MtrHeadlightDynamicLights.java`、`fabric.mod.json` 的 `lambdynlights:initializer` entrypoint 与 `recommends`、
`build.gradle` 的 `modCompileOnly files("../libs/lambdynamiclights-api…")`、`LightBackends` 的 LDL 分支（现在是 `BlockBackend`，`available()` 恒真）、
`RailDynamicLight` 的反射查表、`ParameterScreen` 里"需要安装 LambDynamicLights"的置灰逻辑与 lang key。

验收命令（1.20.1 的构建路径：`v1.20.1/src` + `common/src` + `v1.20.1/build.gradle`）：

```
$ grep -rn "dev\.lambdaurora\|LambDynLights\|lambdynlights\|Ldl[24]Backend" \
      v1.20.1/src/main/java v1.20.1/src/main/resources common/src/main/java v1.20.1/build.gradle | wc -l
0
```

仍存在的、**1.20.1 不可达**的三处（1.18.2/1.19.x 还在用，不能删）：`common/LightBackend` 接口本身（我们的 `BlockBackend` 实现它）、
`RailDiag.ldl2(...)`（旧模块的日志行，方法名保留）、`HeadlightConfig.ldlRegisterSources`（旧配置字段，保留 round-trip）。
`v1.18.2`、`v1.19.2`、`v1.19.4`、`_legacy-single` 里各自仍保留 `Ldl2Backend`（本次不动）。

---

## 6. 编辑器

- 几何 / 颜色 / 角度 / 亮度的编辑链路不变：`VehicleLightOverrides` 就是渲染端读的那张表（新增 `declared` 标记，
  让"作者写了长度"和"检测出来的默认值"不再混为一谈：声明过的条目用自己长度/颜色，没声明的跟随配置）。
- 3D 预览：`TrainPreview.drawBeams` 里的**线框锥（末端圆环 + 4 根母线）改成"光点 + 方向箭头"**——
  灯位画三轴十字（0.18–0.45 格），光束画一段轴线 + 4 根箭羽，不再暗示锥体。

---

## 7. 离线验证（`tools/lamp-positions.js`）

对真实 `builtin_lights.json` + 114 个内置车型 id 复算同一套变换：

```
built-in train types: 114
  with measured lamp boxes (builtin_lights.json): 95
  falling back to the estimated pair:             19
  types producing fewer than 4 lamps / out of range: 0
```

5 个车型的实际坐标（车头朝 +X，轨面 y=64，`spacing=24`）：

```
a_train_tcl  head[0..3] car-local(lat=−0.912/−0.625/+0.625/+0.912, y=1.133, inset=−2.000)
             -> world(1.920, 65.133, −0.912/−0.625/+0.625/+0.912)
             tail[0..1] lat=∓0.914 -> world(−25.920, 65.130, ∓0.914)
c_train      head lat=∓0.596 y=0.961 inset=−1.440 -> world(1.360, 64.961, ∓0.596)
             tail lat=∓0.689 y=0.930 inset=−1.427 -> world(−25.347, 64.930, ∓0.689)
sp1900       head 4 灯 lat=∓0.699/∓0.531 y=0.668 -> world(−0.080→1.920, 64.668, …)
m_train      head lat=∓0.625 y=1.563 -> world(1.920, 65.563, ∓0.625)
k_train      head lat=∓0.719 y=1.069 -> world(1.920, 65.069, ∓0.719)
             tail lat=∓0.969 y=1.069 -> world(∓0.969)  ← 头尾灯横向宽度不同，说明取的是各自那一端的灯箱
```

朝向敏感性（同一盏灯、只转车身，验证灯在**车体坐标系**里而不是世界固定偏移）：

```
yaw=  0deg -> world(−0.080, 65.133, −0.912) right=( 0.00,  1.00)
yaw= 45deg -> world( 0.588, 65.133, −0.701) right=(−0.71,  0.71)
yaw= 90deg -> world( 0.912, 65.133, −0.080) right=(−1.00,  0.00)
yaw=180deg -> world( 0.080, 65.133,  0.912) right=(−0.00, −1.00)
```

工具与运行时的两处差异是明说的：车长（`spacing`）离线拿不到（`--spacing` 可调，只影响 inset 的钳制），
以及模型→车体那一级用的是内置表自己的约定（z<0 = 车头）。断言项（横向 ≤2.4、灯高 0.30–3.30、每车 ≥4 灯）全部通过。

---

## 8. 仍未做 / 已知限制

1. **没有做全屏屏幕空间光照 pass**（任务明确排除）。RTE 的"地面亮斑"是屏幕空间做的，我们靠 `BeamBlockLight`；
   所以开了 Sodium/Embeddium（替换区块 mesher）或光影时，地面亮斑会缺，日志会说明原因（`beam light: skipped, …`）。
2. **1.18.2 / 1.19.2 / 1.19.4 未移植**：它们仍走 `BeamGlow` + `ProtoBeamCone` 壳层 + `Ldl2Backend`，
   共用 `HeadlightConfig` 因此保留了 `beamCone*` 字段（1.20.1 标 `@Deprecated`、UI 不暴露）。
3. **灯位仍可能不准**：内置表只覆盖 95 个车型，19 个靠尺寸兜底；资源包车型靠组名+几何，公交类（无 head/tail 命名）会落到估算。
   位置不对时用 `/lte` 光束编辑器逐车型覆盖（`declared` 层的优先级最高，覆盖后立即生效）。
4. **侧倾（tilt）未作为独立量参与**：灯的基来自车身弦，正常渲染下等效；若某版本 MTR 在弯道额外做 roll，灯不会跟着滚（只有位置受影响，方向不会浮出车身）。
5. `BeamGlow` / `ProtoBeamCone` / `BeamGeom` 三个类仍是死代码（1.20.1 不调用，1.18/1.19 还在用），
   待其余版本移植后一并删除。
6. **白天看不见灯晕是正常的**：`headlightMode` 默认 `AUTO`（夜晚 / 隧道 / 雨天 / 雷暴才亮），
   `HeadlightSwitch.isLit` 同时闸住灯晕、方块光照和轨道烘焙。要验灯位请夜里看，或 `/mtr_lte headlight always`。
   实机一眼可看的证据是这两行日志：每车型一次的 `[lte] lights: model=… head=… tail=… source=model|default`，
   和每 5 秒一次的 `[lte] flare: trains=… lamps=… quads=… noLamp=…`（`trains>0 lamps=0` 才是灯位没算出来）。

---

## 9. 文件清单

新增
- `common/src/main/java/dev/js/mtrheadlight/TrainLamps.java`
- `v1.20.1/src/main/java/dev/js/mtrheadlight/LightFlare.java`
- `v1.20.1/src/main/resources/assets/mtrlte/textures/vehicle/light_flare.png`
- `tools/gen-light-flare.js`、`tools/lamp-positions.js`
- `docs/light-rework-report.md`（本文）

删除
- `v1.20.1/src/main/java/dev/js/mtrheadlight/ConeVolumeRenderer.java`
- `v1.20.1/src/main/java/dev/js/mtrheadlight/Ldl4Backend.java`
- `v1.20.1/src/main/java/dev/js/mtrheadlight/MtrHeadlightDynamicLights.java`

修改
- `common`：`HeadlightConfig`（beamFlare/beamFlareSize/beamFlareMinPx + Effective/SidingSettings、beamBlockLight 默认开、beamCone `@Deprecated`）、
  `LightController`（灯位来自 `TrainLamps`）、`EndLight`（`ofSegment`）、`VehicleLightOverrides`（`declared` 标记 + `Resolved.ofPublic`）、
  `BuiltinLights`（inset 允许负数）、`RailDynamicLight`（去掉反射查表）、`ProtoBeamCone`（去掉 volume 分支）、`LightBackend`/`RailDiag`（仅注释）
- `v1.20.1`：`RenderHooks`、`LightBackends`、`Commands`、`ConfigWriteTarget`、`ParameterScreen`、`ConfigScreen`、`ConfigCategory`、
  `TrainPreview`、`fabric.mod.json`、`build.gradle`、`lang/en_us.json`、`lang/zh_cn.json`
