# RTE 1.0.0-beta.4：行为与架构规格（外部观察，非源码）

> 对象：`rte-fabric-1.0.0-beta.4-1.20.1.jar`（Rollingcats Transit Expansion，**All-Rights-Reserved**）
> 目的：理解"它做到了什么、在哪一层做的、数据长什么样"，用于 LTE（MTR3 / 1.20.1）的独立设计与取舍。
> 方法：jar 结构 + 类/字段/常量级静态观察（临时目录，未入库）+ 隔离实例 `temp/rte-probe` 实跑日志。
>
> **本文不含任何可直接编译的原实现**：不贴方法体、不贴 GLSL 文本、不给完整数据结构定义。
> 只描述行为、数据形状、阶段顺序与我们自己的实现建议。文中出现的类名/字段名仅用于指认结构。
> 依 ARR 许可：读行为、做功能对标可以；抄代码/抄 shader/抄资源/拆包复用不行。

环境基线（实跑证实）：Minecraft 1.20.1 + Fabric Loader 0.19.3，`mtr 4.0.5`、`architectury 9.2.14`、`fabric-api 0.92.12`、`rte 1.0.0-beta.4`，共 61 个 mod 条目。

---

## 0. 一句话总览

RTE 不是"另一个渲染器"，而是**挂在 MTR 4 车辆渲染链上的一层附加渲染**：

```
资源包 / 模型 / 脚本  ──►  注册表与缓存（灯、轨道碰撞、模型）
                              │
宿主每帧回调（MTR 渲染车辆、原版渲染世界）──► 收集 Job（本帧要画什么）
                              │
              ┌───────────────┴────────────────┐
      世界空间 billboard 光斑          屏幕空间光照 pass
      （LevelRenderer TAIL 提交）      （renderLevel 之后、GUI 之前）
```

碰撞则是另一条独立管线：**几何生成（轨道段 / 车体）→ 查询（chunk 桶 / 距离筛）→ 注入原版实体移动求解 + 服务端判定豁免**。

---

## 1. 总览架构

```
                      ┌──────────────── 资源阶段（pack reload） ────────────────┐
assets/<ns>/rails/*.json ────────────► RailModelRegistry ──┐
assets/<ns>/eyecandies/*.json ───────► EyeCandyRegistry ───┤
assets/<ns>/mtr_custom_resources.json ► ScriptedCustomTrains┤
  (MTR3 旧格式，MTR 自带转换器)                            ├─► 全局注册表
assets/<ns>/rte_vehicle_lights.json ─► VehicleLightRegistry┤   (按车型 id 索引)
模型 LIGHT 部件（RenderStage）───────► ModelLampFinder ─────┤
旧版 OBJ lamp 部件 ──────────────────► LegacyLampReader ───┤
脚本模型 rendertype_beacon_beam 面片 ─► ScriptedLamps ─────┘
                                                              │
宿主渲染回调（每帧）                                          │
RenderVehicles.render HEAD ─► 清空本帧 Job/状态缓存            │
RenderVehicles 每节车 ──────► queue(车, 车架位姿, 侧倾角) ◄────┘
                               └─ 灯状态机 + 暗度 → Job 列表
LevelRenderer TAIL ─────────► 画 billboard 光斑（glow 类型，世界坐标批）
                             └─ 顺带收集 ≤12 个"最近光锥" Spot
GameRenderer(renderLevel 后) ► 屏幕空间光照 pass（场景色拷贝 + 深度）
```

接缝一览（谁调谁）：

| 接缝 | 位置 | 作用 |
|---|---|---|
| `RenderVehicles.render` HEAD/RETURN | 车辆渲染帧边界 | 清空/提交本帧状态、丢弃死亡脚本上下文 |
| `RenderVehicles.getStoredMatrixTransformations` 重定向 | 每节车车身 | 在这里 `queue` 灯（此时才拿得到车架位姿与车型资源） |
| `LevelRenderer.renderLevel` TAIL | 世界层末尾 | 提交 billboard 光斑、碰撞预览线框、调试覆盖 |
| `GameRenderer.renderWorld`（renderLevel 之后） | 世界后、GUI 前 | 跑全屏光照 pass |
| `CustomResourceLoader.reload` HEAD/TAIL | MTR 资源重载 | 前置清缓存、后置跑 RTE 自己的 5 个阶段 |
| `Entity.collide`（原版移动求解） | 实体移动 | 追加轨道段 / 车体形状 |
| `ServerGamePacketListenerImpl` | 服务端移动校验 | 站车顶不算"异常位移" |

---

## 2. 模块一：车灯

### 2.1 灯的数据从哪来（四级优先链）

配置总开关 `vehicleLights`，另有 `vehicleLightsAuto`（无显式配置时是否自动推导）。取灯顺序：

1. **资源包 JSON**：`assets/<任意命名空间>/rte_vehicle_lights.json`。**文件名固定、位置在命名空间根**，不是目录扫描。多个命名空间叠加，后读的只是"更多条目"，匹配靠模式串而不是覆盖。
2. **模型自带灯位**（`vehicleLightsAuto` 开启时）：读 MTR 车模的"部件"里渲染阶段为 `LIGHT` / `ALWAYS_ON_LIGHT` 的部件，按部件的出现条件推断它是"前进方向头灯"还是"反向头灯"，再把几何顶点聚成灯团。
3. **旧版资源包 lamp 部件**：从 MTR3 风格的 `mtr_custom_resources.json` 里读 `stage: LIGHT*` 且 `render_condition` 含 FORWARDS/BACKWARDS 的部件，按 OBJ 的 `group` 名取顶点聚类。
4. **脚本模型灯面**：脚本自定义列车的网格里，材质 shader 名为 `rendertype_beacon_beam` 的面片被视为灯面，取其顶点聚类。
5. **兜底估算**：以上都拿不到时，按 `VehicleResource` 的 length/width 在四角摆 2×2 组头灯/尾灯（高度 1.0–1.15 格），保证"有车就有灯"。

同一节车如果模型推导返回空，会退到下一级；每级结果都带缓存与"失败后 N 秒再试"的抑制，避免每帧重扫模型。

**灯光定义文件的行为规格**（下表是它的数据形状，不是要照抄的格式）：

- 一个文件一个 `lights` 数组；每个元素 = "一组车型模式 + 一个灯"。
- 车型模式支持精确 id 和 `前缀*` 通配；比较时对 id 做一次规范化（车厂/编组后缀归一）。
- `mirrorX: true` 时注册表自动追加一条 x 取反的镜像条目（左右灯写一条即可）。
- 颜色缺省按灯类型给：头灯暖白 `#fff3dc`、尾灯/刹车红 `#ff2a1a`、门灯琥珀 `#ffb020`。
- 结果按车型 id 缓存（含"空结果"也缓存），reload 时整体清空。

### 2.2 注册表结构

| 结构 | 内容 |
|---|---|
| `Entry` | `(车型模式列表, 灯)` |
| `Light` | 类型、RGB、亮度、位置 (x,y,z)、朝向轴 (ax,ay,az)、光斑宽/高、光束直径/长度、闪烁 on/off 毫秒 |
| `Type` | `HEADLIGHT` / `TAILLIGHT` / `BRAKE` / `DOOR` / `RUNNING` / `ALWAYS`（未知值降级为 `ALWAYS`） |

关键约定：

- **`z >= 0` 即"前端"**（`atFrontEnd()`），灯自己不带"这是第几节车"，由渲染时的编组位置决定亮不亮。
- 灯位坐标是**车体局部坐标**，且 y/z 轴和 MTR 模型空间相反（它内部统一做一次 y 取反、z 取反）。
- `beamDiameter` / `beamLength` 在 beta.4 **只被解析、没有被渲染路径消费**——光锥范围是渲染端写死的常量（头灯 48 格、尾灯 6 格），不是配置值。这是个明确的可改进点。

### 2.3 六类灯的状态机

对每节车、每盏灯：

```
front  = (light.z >= 0)
leading  = front ? (未反向 且 本车是第 0 节) : (反向 且 本车是最后一节)
trailing = front ? (反向 且 本车是第 0 节)  : (未反向 且 本车是最后一节)
onRoute  = vehicle.getIsOnRoute() || speed > 1e-6
braking  = 线路内 且 (停住 或 最近一次减速后 400ms 内)
doorsOpen= 车门开度 > 0
```

| 类型 | 亮条件 |
|---|---|
| HEADLIGHT | onRoute 且 leading |
| TAILLIGHT | onRoute 且 trailing |
| BRAKE | onRoute 且 trailing 且 braking |
| DOOR | doorsOpen |
| RUNNING | onRoute |
| ALWAYS | 恒亮 |

再叠加闪烁（`now % (on+off) < on`，任一为 0 则不闪）与环境暗度。**亮/暗不看时间点，看"这盏灯在不在运行方向的前端/后端"**——同一节车调头后灯会自动换端，这是它比"按车型 id 表写死头尾"更稳的地方。

"刹车"不是读 MTR 的制动手柄，而是**速度下降检测**：本帧速度比上帧低 → 记一个 400ms 的刹车保持窗口；速度为 0 且在线路上也算刹车。好处是与 MTR 内部状态解耦，坏处是断电/上坡限速也会亮刹车灯。

### 2.4 与车体位置/朝向的绑定

- 位姿来源：MTR 每节车的"绝对位置与朝向"（位置 + yaw + pitch + 打包光照值），由渲染链在进入车身绘制前捕获，存进一个一次性帧状态对象。
- 局部→世界：先平移到车心，再 `yaw+π`、再 `pitch+π`；若本帧有侧倾角，再绕 **y=1 高度**的横轴滚转（与 MTR 车身视觉共用同一个轴心，灯不会浮出车顶）。
- 局部坐标进入渲染前统一做一次 `(x, -y, -z)`，与 MTR 模型空间对齐。
- 车型资源（用于读长宽、模型部件）也在同一帧状态里带下来，避免二次查表。

### 2.5 每帧怎么收集"可见灯"

```
beginFrame()           清空 Job 列表（每 512 帧顺手清一次过期刹车状态）
  ↓ 每节车
queue(车, 车型资源, 侧倾角)
    ├ 关掉总开关 / vanilla 模式 / 阴影 pass / 无位姿 → 直接返回
    ├ 取该车型的灯列表（配置 → 自动推导），为空则返回
    ├ 逐灯跑状态机 + 闪烁 → active 列表
    ├ 取车架矩阵（含侧倾）→ Job(矩阵, active, 光斑系数, 暗度)
    └ 无 active 则不入队
  ↓
render()               清 Spot，取本帧投影/视图，收集 Spot（≤12，按到相机距离升序），
                       然后一次性画完所有 flare 并清空 Job
renderLighting()       若 Spot 非空 → 跑屏幕空间 pass
```

上限与裁剪：**光锥全局最多 12 盏**（按距相机距离裁剪）；光斑没有距离裁剪，靠视锥/深度测试自然剔除。车型 id → 灯列表、车型 id → 自动推导结果都有缓存，重载时清空。

### 2.6 亮灭、亮度与"暗度"

- 环境暗度 = `1 - max(方块光照/15 × 0.6, 天空光照/15 × 昼夜因子)`，昼夜因子由世界时间插值并把黄昏段压平。
- 光斑强度系数 = `(0.6 + 0.4 × 暗度) × (开光影 ? 0.8 : 1)`；白天不会全灭（只压到 0.6），夜里最亮。
- 光锥强度 = `基础强度 × 0.6 × 暗度`，**小于 0.01 直接不生成**——这是"白天没有光锥"的真正原因，不是靠开关。
- 光斑颜色 alpha = `brightness × 系数`，因此 `brightness` 是"灯的档位"，不是"开关"。

### 2.7 渲染：两条完全不同的路径

**(a) 世界空间光斑（billboard）**

- 一张 128×128 的光斑贴图（RTE 自带，中间亮、边缘衰减）。
- 渲染类型是**半透明 + 只写颜色（不写深度）、不参与排序上传**，视觉上等效加色高光。
- 每个灯画一个**面向相机的四边形**：法向 = 灯朝向轴，按 `视线·轴` 算正对度，`正对度³` 作为眩光强度；越正对越大、越偏越淡。
- 光斑会从灯位沿视线方向"往相机拉"一段（最多 0.9 格、随距离增大），避免贴面深度冲突；拉近的同时按比例缩小。
- 尺寸 = 灯自身 `flareWidth/Height` × `(0.45 + 1.4 × 正对度³) × 缩小系数`；正对度 < 0.02 直接跳过（背面不画）。
- 提交用自定义 RenderType：半透明、按实体坐标烘焙、不参与排序上传、固定纹理；在世界层末尾一次性 flush。

**(b) 屏幕空间光照（Spot pass）**

这是一条**全屏后处理**，与几何光锥方案完全不同层：

1. 拿主帧缓冲的深度纹理，把**场景颜色**拷一份到自建纹理（因为要边读边写）。
2. 全屏三角形，逐像素从深度反投影出视空间位置；用屏幕空间导数叉乘求法线，朝向翻转以保证法线朝相机。
3. 对每盏 Spot（位置、方向、外/内锥角余弦、范围、颜色）做两件事：
   - **表面项**：像素到灯的距离、锥内衰减（内外锥之间 smoothstep）、距离窗（四次方窗 × 1/(1+0.04d²)），再乘 `max(0, N·L)` → 得到"地面/车身上的光斑"。
   - **体积项**：把"相机到该像素"的视线上落在灯球内的那一段（球与视线的弦）切成 8 步，每步判断是否在锥内、算距离衰减与**各向异性相位函数**（前向散射），累加成空气里的光柱。
4. 合成：`lit = scene + (1 - exp(-added × 1.2)) × (1 - 0.5 × scene)` —— 高光滚降式加色，避免过曝成白块。
5. GL 状态全保存/恢复（program、两个纹理单元、读写 FBO、VAO、混合/深度/剔除/深度写、视口）；**任一异常只置一次失败标志，之后永久跳过**，不会每帧刷错误。

### 2.8 与光影（Iris / OptiFine）的兼容处理

- 反射探测：OptiFine 与 Iris API 二选一；探测不到就用空实现（不影响主流程）。
- **阴影 pass 内不收集灯**（否则灯会在阴影贴图里再亮一次、产生错误的投影）。
- 开光影时：光斑系数 ×0.8；并且当"光影 GPU 路径"开启时，RTE 把自己的世界内容**提前**到区块层之后、半透明层之前画（让光影的 composite 能吃到它），同时把"世界层末尾再画一次"的逻辑抑制掉（同帧只画一次）。
- 光照 pass 会尝试取 Iris 的深度纹理，但 beta.4 里这条路径**实际恒回退到原版主帧缓冲深度**（取到即返回 -1 的分支写法导致永远走 fallback）。所以它的"光影兼容"实质是：**不依赖光影的 gbuffer，只依赖深度 + 自拷贝颜色**。
- 着色器编译失败 / GL 调用异常 → 一次性降级，日志里只留一条 `[VehicleLights] Lighting pass disabled: ...`。

### 2.9 观察到的边界（对我们有用）

- `beamDiameter`/`beamLength` 解析后未使用；光锥尺寸是常量。
- 光斑不做距离/数量裁剪，远景车队全在批里（靠 GPU 深度剔除）。
- 只有"头灯/尾灯"会生成 Spot；`BRAKE`/`DOOR`/`RUNNING` 只出光斑，不出光锥。
- 自动推导对"灯部件不是 LIGHT 阶段"的老资源包无效，只能靠旧格式 lamp 部件或兜底估算。
- 刹车靠速度差，无"电阻制动/再生"等语义。

### 2.10 对 LTE 可借鉴 / 不可照搬

**可借鉴（行为层）**

- 四级优先链：显式配置 → 模型 LIGHT 部件 → 旧格式 lamp 部件 → 兜底估算。我们已有"内置灯位表 + beam3D JSON"，缺的是**从模型 LIGHT 部件自动发现灯位**这一级；这是覆盖面从 95/114 车型走向"任意资源包"的关键。
- 用 `PartCondition`（FORWARDS/BACKWARDS）推断"这盏灯属于哪个运行方向"，把"头尾"从数据表问题变成**编组几何问题**；配合"第 0 节 / 最后一节"判定，调头自动换端。
- 灯状态机把"亮不亮"拆成 `onRoute × leading/trailing × braking × doorsOpen × blink` 五个正交因子，比单一 `enabled` 好测。
- 屏幕空间光斑（表面池）+ 屏内体积散射（空气柱）**合成一次**，用一条 pass 同时拿到"地面亮斑"和"雾里光柱"，比几何光锥便宜且没有锥体穿插问题。
- 环境暗度门控光锥强度（而不是开关光锥），白天"看不见"是自然结果，玩家不会觉得功能坏了。
- 一次性失败降级（shader 编译失败只关这一路，不崩游戏）。

**不可照搬（ARR）**

- 它的 GLSL 文本、uniform 布局、8 步/`1.2` 曝光系数/相位函数参数组合 —— 这是它的美术调参，照抄等于复制实现。
- `vehicle_light_flare.png` 贴图与它自带的 RenderType 名字/状态组合。
- `Emitter/Job/BrakeState/Spot` 这套内部类划分与字段命名（可以理解"要有个 Job 列表"，但结构自己定）。
- 它内置的 `libs/`（GraalVM/Truffle、imgui 等 61MB 的三方内容）——不要以任何形式复用或打包。

---

## 3. 模块二：碰撞

### 3.1 三条独立的数据线

| 线 | 对象 | 作用域 | 谁在用 |
|---|---|---|---|
| 轨道段碰撞 | 一段轨道按弧长采样出的若干 AABB | 双端（客户端 + 服务端） | 实体移动求解、轨道预览、调试线框 |
| 车体碰撞 | 一节车按局部盒采样出的世界 AABB | 仅客户端玩家 | 站在车上/被车挡、车顶随车移动 |
| 服务端判定 | "玩家是否站在车上"的粗判 | 仅服务端 | 抑制原版"浮空/异常位移"判定 |

注意：**没有"碰撞体"实体、没有注册任何 block/entity 碰撞箱**，全部是在原版 `Entity.collide` 的返回值上"追加形状"。

### 3.2 轨道段碰撞（`Rail` 级）

- 数据来源：MTR 的 Rail 对象 + RTE 给 Rail 挂的扩展数据（宽度/高度/段长/间距/上下偏移/左右偏移 + 两个自定义配置键）。
- 生成算法（行为层）：
  1. 沿轨道**弧长**按 `spacing` 取采样点（默认 0.5），最后一节强制补端点；
  2. 每个采样点求切线（前后各取一点求差，退化时按 z 轴），用切线法线做**横向列偏移**（`columns`，最多 32 列，默认一列 0）；
  3. 每个 (采样点 × 列) 生成一个盒：横向偏移、竖向偏移、宽、高、段长；盒的 XZ 尺寸按切线与法线投影做"轴向包围"（近似 OBB 的 AABB）；
  4. 若给了**显式布局**（`distance,side,y,w,h,len` 分号分隔，最多 8192 条），则跳过均匀采样，完全按布局放盒（用于站台边缘、道岔、异形段）。
- 上限与去重：单段轨道最多 8192 盒；同一"物理轨道"（两端点坐标对，顺序无关）只生成一次（防止 MTR 里同一条轨有多个 Rail 对象）。
- 缓存：按世界弱引用缓存；失效条件 = 全局 revision 变化 **或** 每 20 tick 校验的"源签名"变化（签名覆盖长度/参数/节点偏移/坡度/超高/贝塞尔引用/路径模式等，几乎把影响几何的字段全算进去）。
- 查询：盒按 **16×16 区块** 分桶（跨区块的盒在多个桶里重复登记），查询时只扫查询 AABB 覆盖的区块，用 identity set 去重，返回 `VoxelShape` 列表。

### 3.3 车体碰撞（`VehicleCar` 级，仅客户端）

- 默认形状是**"箱形车厢"**：顶板（厚 0.25）、前后端墙、左右侧墙（**车门打开时两侧不放墙**，整侧放行——上下车不会被挡）；高度默认 4 格（配置 1–8），宽/长取车数据。
- 每节车可用脚本覆盖：`height`、`layout`（`x,y,z,w,h,len` 6 字段，最多 256 盒）、`enabled`；覆盖按 `(车辆 id, 节号)` 存，100 tick 清一次已消失车辆。
- 采样成世界形状：把每个局部盒按 0.5 格细分成格子，每格上下两点变换到世界，生成"水平包围 + 竖直跨度 + pitch 补偿"的 AABB。这样弯道/坡道上不会出现"整节车一个大盒"的误挡。
- 预筛：先按车辆到查询中心的距离（`长/2, 宽/2` + 查询半径 + 2）与竖直区间筛车，再按 yaw 把查询盒 8 个角反变换到车体局部，得到局部区间，只对落在区间内的格子生成盒。
- 站车顶：每 tick 找"脚在盒顶 ±0.15 且水平在盒内（0.3 余量）"的车 → 记录 `(车, 节, 当时位姿)`；下一 tick 把玩家在"旧车体局部坐标"里的位置用"新车体位姿"变换回世界，并同步 yaw 增量（身体/头部/上一 tick 插值一起改）；超过 8 格则放弃；腾空或离车即清除。

### 3.4 预览

- 选中一段轨道时：用同一套生成器算盒，画青色线框（半透明 0.85），带"轨道引用 + 参数指纹"缓存，参数不变不重算。
- 调试模式：以相机为中心 128 格查询所有轨道段碰撞形状，画线框（跳过空形状）。

### 3.5 服务端与 MTR4 的关系

- 轨道段碰撞在**双端**都生效（服务端也走同一套 rail 数据 + 同一套生成器），因此服务端能独立判"人卡在轨道结构里"。
- 服务端另有一条粗判：遍历该世界 Simulator 的所有 Siding/Vehicle，用"每节车所有转向架中心点的均值"当车心，`水平距离 ≤ 长/2+1.5`、`车心底 -1 ~ +9` 视为"站在车上"；命中则让"移动校验"返回"未异常"（防止站在车顶被踢/被拉回）。
- **与 MTR4 自身碰撞的关系**：MTR 4.0.5 的 jar 里没有任何 `*Collision*` 类，它的"车体碰撞"只有门区上下车的交互盒；RTE 没有替换它，而是**两道并行**：
  1. 自己的车体墙/顶盒（玩家在车外时挡人）；
  2. 把 MTR 的门区盒**压扁成 0.125 格高的薄片**（`RenderVehiclesMixin` 里的重定向），避免"站在门口被顶起来"。
  也就是说：**轨道段碰撞是 RTE 独有；车体碰撞与 MTR 门区盒是"互补 + 主动削平"，不是重复实现**。

### 3.6 对 LTE 可借鉴 / 不可照搬

**可借鉴**

- 轨道段碰撞用"弧长采样 + 横向列 + 可选显式布局"三件套：均匀采样解决弯道，列解决多股/宽结构，显式布局解决站台边缘与道岔；比"整段一个大 OBB"稳，比"逐体素"便宜。
- 失效判定用**源签名**而不是"每 tick 全量重建"：把影响几何的参数（长度、偏移、坡度、超高、路径模式）打进一个哈希，变了才重建。我们的 OBB 分段碰撞可以直接借这个思路。
- 盒按区块分桶、跨区块重复登记 + identity 去重：查询是 O(覆盖区块) 而不是 O(全量)。
- 车体碰撞"局部盒 → 0.5 格采样 → 世界 AABB"能把弯道/pitch 的误挡压下去，而且不需要真正的 OBB-求交。
- 站车顶用"局部坐标搬运 + yaw 增量同步"，一行位移就够，不用改实体 riding 状态。
- 开门整侧放行（而不是删门那一小段盒）：乘客不会卡在门框。

**不可照搬**

- `rte_collision_columns` / `rte_collision_layout` 这两个键名与它的取值格式（同名字段会让资源包作者以为两边格式通用，且属于照搬它的数据契约）。
- 它的默认参数表（spacing 0.5 / width 3.0 / height 0.25 / length 0.75 / y -0.25 / 8192 / 32 列）——这是它调出来的手感，不是规格。
- `TrainCollisionManager` 的盒布局与 0.5 采样步长、`STAND_TOLERANCE=0.15` 这类阈值组合。
- 它把 MTR 门区盒压扁的具体注入点（我们打 MTR3，类名/方法名完全不同；且这种"改宿主盒"的做法要自己评估）。

---

## 4. 模块三：资源 / 脚本层

### 4.1 它扩展了 MTR4 的哪些阶段

MTR4 的资源重载入口是 `CustomResourceLoader.reload`（车辆/站牌/轨道/物件/电梯 + 旧格式转换）。RTE 在**首尾各插一刀**：

**HEAD（重载前）**：清空预加载预算、异步模型加载队列、预热队列、磁盘网格缓存指纹；把当前 ResourceManager 记到自己的工具类里。

**TAIL（重载后）**：
1. 扫**所有命名空间**的 `mtr_custom_resources.json` 与 `mtr_custom_resources_pending_migration.json`，用 MTR 自带的旧格式转换器转成 MTR4 资源，再通过 MTR 的注册入口（`registerVehicle` 等）追加进 MTR 的缓存；
   - 顺手做三件兼容修补：`base_type` → `base_train_type` 别名；`bve_sound_base_id` 转小写；**"只有脚本没有模型"的列车**自动补一个空 OBJ + 按 `base_train_type` 里的 `train_<长>_<宽>` 生成 model_properties（这就是"脚本车身"能挂在 MTR 原生车上的原因）。
2. 调 `RTEClient.reloadResources` 跑自己的 5 个阶段（见下）。
3. 调度车辆预热（`VehicleWarmup`）与网格磁盘缓存清理。

### 4.2 RTE 自己的 5 个阶段（实测日志逐字对应）

| 阶段 | 读什么 | 做什么 | 实测 |
|---|---|---|---|
| `rails` | `assets/<ns>/rails/*.json` | 轨道外观/几何注册表（轨道模型、路面标线） | 3–18 ms |
| `eyeCandies` | `assets/<ns>/eyecandies/*.json` | 静态装饰物注册表（OBJ + 贴图 + group） | 13–20 ms |
| `scriptResources` | 资源包字体（MTR 的 Noto Sans/Serif） | 只做字体可用性探测与缓存清空（beta.4 实质是空壳） | 0–1 ms |
| `trainScripts` | `assets/<ns>/mtr_custom_resources.json` 里的 `script_files`/`script_texts` | 脚本列车渲染器注册；解析脚本里的车身/灯/车门描述 | 29–287 ms（最大头） |
| `builtinTrains` | — | **beta.4 里是空实现**（`reload` 空、`renderIfBuiltin` 恒 false），耗时几乎只有同段里的 `VehicleLightRegistry.reload` | 0–4 ms |

阶段后还有一句 `Released N cached raw models after reload`（释放重载期缓存的裸网格），以及"有加载错误就弹错误屏"。

### 4.3 重载防护

- **重入锁**：正在重载时再进来的请求直接丢弃。
- **去抖**：资源包签名（各包 id 串起来）+ 5 秒窗口内重复调用直接返回（`force=true` 时跳过，用于手动"重载客户端资源"）。
- **GL 状态**：重载全程 `GlStateTracker.capture()/restore()`，避免重载期间改状态把主渲染搞坏。
- **每阶段计时**：一条 info 日志给出 5 个阶段的毫秒数——这是它给服主/作者排查"哪个包慢"的主要手段（也正好是我们做同类功能时最该抄的**运维习惯**，不是代码）。
- **错误隔离**：单个包解析失败只 warn + 记进 loadingErrorList，不影响其它包。

### 4.4 对 LTE 可借鉴 / 不可照搬

**可借鉴**

- "宿主 reload 首尾各插一刀"这个模式：HEAD 清缓存，TAIL 跑自己的阶段并调度预热。MTR3 侧对应的是它自己的资源重载入口，我们只需找到"重载完成"的时点，不必改宿主代码。
- 阶段化 + 每阶段计时 + 单包失败隔离：我们现在的 beam3D / 灯位表 / 内置表如果按阶段打点，玩家报"某个包加载不出来"时能直接定位。
- "只有脚本没有模型"的车补一个占位车身：脚本列车在宿主眼里必须是一节有长度/宽度的车，占位模型 + 派生尺寸是最省的兼容方式。
- 旧格式 JSON 的**小修补**（字段别名、id 小写）放在转换前做，而不是在消费端到处兜底。

**不可照搬**

- 它那套对 MTR 内部字段的 `Accessor/Invoker` 清单（`VEHICLES_CACHE`、`SIGNS`、`registerVehicle`…）——这是它的私有兼容层，照抄等于把它对 MTR4 内部结构的假设搬进我们的代码，且 **MTR3 的类名/方法名完全不同，物理上也不适用**。
- `rte:*` 命名空间下的占位模型路径、`legacy_script_body/` 约定、阶段名（这些是它的对外契约）。
- 它的 5 个阶段顺序（我们不必有"builtinTrains"这种空壳阶段；顺序应由依赖决定，而不是照排）。

---

## 5. 模块四：兼容 mixin 在干什么（一句话级）

`rte.mixins.json` 有 ~110 个客户端 mixin + ~35 个通用 mixin，注入器统一 `defaultRequire: 0`（**目标方法找不到就静默跳过**，这是它敢一次兼容多个 MTR 小版本的底气）。

| 组 | 典型项 | 一句话 |
|---|---|---|
| **Legacy\***（旧资源包兼容） | `LegacyVehicleResourceMixin`、`LegacyDoorwayMixin`、`LegacyPlugDoorFixMixin`、`LegacyObjSegmentReversalMixin`、`LegacyFloorMixin`、`LegacyCabReverseMixin`、`LegacyDoorOverlayMixin`、`LegacyDefaultTextureMixin`、`LegacyRailLoaderMixin` | 在 MTR 的旧格式转换/模型加载过程中改 JSON 与顶点：补门框、翻转段落、修塞拉门 X 偏移、楼层高度、司机室反向、缺贴图兜底 |
| **Jcm\***（JCM 脚本 mod） | `JcmMtrContentResourceManagerMixin`、`JcmMatricesLegacyMixin` | 若装了 JCM：在它的资源解析里**摘掉**没有 `script_id` 的列车的 `script_files/script_texts/script_input`（"旧式内联脚本归 RTE，JCM 别抢"），并让 `getVehicleScriptEntryId` 优先走 RTE；矩阵工具做兼容。没装则目标类不存在、静默跳过 |
| **网格/批处理优化** | `MtrRawMeshDedupeMixin`、`MtrBatchManagerInstancingMixin`、`MtrVertexArrayReleaseMixin`、`MtrVertexArrayAccessor` 等 | 面数超阈值就**跳过 MTR 的去重**（太慢）；对 MTR 的批管理器做实例化绘制；接管 VAO 释放避免显存泄漏 |
| **渲染接缝** | `RenderVehiclesMixin`、`RenderRailsMixin`、`LevelRendererMixin`、`GameRendererMixin`、`MainRendererMixin`、`FrustumSafetyMixin`、`RenderRailsOcclusionMixin` | 车/轨/世界的绘制前后插钩子；对遮挡剔除做"安全阀"（防止错误剔除导致整列车消失） |
| **模型管线** | `DynamicVehicleModelMixin/LampMixin`、`VehicleModelAsyncLoadMixin`、`ModelBakeryMixin`、`NativeImageDownscaleMixin`、`PackCompatibilityMixin` | 异步建模型/预加载、贴图降采样到上限、放宽包的 pack_format 校验 |
| **输入/GUI** | `MouseHandlerMixin`、`KeyboardHandlerMixin`、`MinecraftPickRailMixin`、`MinecraftVehicleUseMixin`、`GuiMixin` | 给 ImGui 编辑界面让路（吞掉点击/按键）、自己实现"选轨"与"右键用车" |
| **诊断** | `CatenaryDiagMixin`、`QueueDiagMixin`、`ShaderDiagMixin`、`AttribDiagMixin`、`OscillationMixin` | 只在诊断开关下打点；`CatenaryDiagMixin` 的目标是**别的 mod**（`top/mcmtr/...`），实测日志明确报 `target ... was not found` 后跳过——证明 `require=0` 的软失败确实生效 |

对我们最有用的一条结论：**它把"兼容"实现成"一堆可失败的注入点 + 一个能力探测层（CompatArbiter / ShadersModHandler）"，而不是分支判断散落各处**。我们用 Mixin 时同样应给每个注入点设 `require = 0` 或至少单独 try，并集中一个"谁拥有这个域"的仲裁器（例如"网格优化"域只允许一方生效）。

---

## 6. 关键配置 / 数据字段表

### 6.1 `assets/<ns>/rte_vehicle_lights.json`

| 字段 | 类型 | 含义 / 默认 |
|---|---|---|
| `lights` | 数组 | 必需；每个元素一组车型 + 一盏灯 |
| `vehicles` / `vehicle` | 字符串或数组 | 车型 id；`前缀*` 通配；缺省则整条忽略 |
| `type` | 字符串 | `HEADLIGHT`/`TAILLIGHT`/`BRAKE`/`DOOR`/`RUNNING`/`ALWAYS`；未知 → `ALWAYS` |
| `pos` | `[x,y,z]` | 车体局部位置，默认 `[0,1,0]`；`z>=0` 视为前端 |
| `axis` | `[x,y,z]` | 灯朝向，默认沿 z（符号跟 `pos.z`） |
| `color` | `"#RRGGBB"` | 缺省按类型：头灯 `#fff3dc`，尾/刹 `#ff2a1a`，门 `#ffb020` |
| `brightness` | 0–1 | 亮度乘子，默认 1（0 = 不出光斑） |
| `flareSize` | 数 | 光斑基准尺寸，头灯默认 1.1，其它 0.8 |
| `flareWidth` / `flareHeight` | 数 | 覆盖 `flareSize`（椭圆光斑） |
| `beamDiameter` / `beamLength` | 数 | **beta.4 解析但未使用** |
| `blink` | `[onMs, offMs]` | 闪烁周期；任一 ≤0 不闪 |
| `mirrorX` | bool | true 时自动追加 x 取反条目 |

### 6.2 轨道碰撞（挂在 Rail 的扩展数据/自定义配置上）

| 键 / 字段 | 含义 |
|---|---|
| `rte_collision_columns` | 横向列偏移，逗号分隔（≤32，默认 `0`） |
| `rte_collision_layout` | 显式盒布局：`distance,side,y,w,h,len` 以 `;` 分隔（≤8192） |
| width / height / length | 单个盒的宽/高/段长（默认 3.0 / 0.25 / 0.75） |
| spacing | 弧长采样间距（默认 0.5，下限 0.01） |
| yOffset / sideOffset | 竖向/横向整体偏移（默认 -0.25 / 0） |
| collisionEnabled | 该段是否参与碰撞 |

### 6.3 车体碰撞（脚本可覆盖）

| 字段 | 含义 |
|---|---|
| `height` | 车厢总高（1–8，默认 4） |
| `layout` | `x,y,z,w,h,len` 以 `;` 分隔（≤256 盒，覆盖默认箱形） |
| `enabled` | 是否屏蔽该节车碰撞 |

### 6.4 客户端配置（`config/rte.json` 相关项，括号内为实测值）

| 键 | 含义 |
|---|---|
| `vehicleLights` (true) | 车灯总开关 |
| `vehicleLightsAuto` (true) | 无显式灯配置时，是否从模型/旧格式/脚本推导 |
| `trainCollision` (false) | 车体碰撞总开关（默认关） |
| `trainCollisionHeight` (4) | 默认车厢高 |
| `shaderPackGpuRendering` (true) | 开光影时是否走"提前绘制"路径 |
| `shaderCompatMode` (false) | 光影兼容的额外保守模式 |
| `instancedVehicleRendering` (true) | 车辆实例化渲染 |
| `maxTextureSize` (2048) | 车辆贴图降采样上限 |

---

## 7. 隔离实例实跑记录（结论不是猜的）

命令（隔离目录，不碰用户实例）：

```
GAMEDIR="D:/MC-MTR-JS/temp/rte-probe" node D:/MC-MTR-JS/temp/launch-mc.js
```

启动日志关键行（本次运行，`temp/rte-run.log`）：

```
Loading Minecraft 1.20.1 with Fabric Loader 0.19.3
Loading 61 mods: ... architectury 9.2.14 ... mtr 4.0.5 ... rte 1.0.0-beta.4
[main/WARN]: @Mixin target top/mcmtr/mod/render/RenderCatenaryModel was not found
             rte.mixins.json:CatenaryDiagMixin from mod rte            ← require=0 软失败生效
[Render thread/INFO]: Loaded 423 vehicles and completed door movement validation
[Render thread/INFO]: Loaded 142 signs / Loaded 3 rails / Loaded 1 objects / Loaded 1 lifts
[Render thread/INFO]: [RTE] Reloading client resources (2 packs)
[Render thread/INFO]: [ModelPrefetch] parsed 10/10 models on 10 threads in 73 ms
[Render thread/INFO]: [ScriptedCustomTrains] namespaces=60 officialStacks=3
                      pendingStacks=2 loadedTrainIds=1 aliasCount=15
[Render thread/INFO]: [RTE] Reload phases: rails=13 ms eyeCandies=20 ms
                      scriptResources=1 ms trainScripts=287 ms builtinTrains=4 ms
[Render thread/INFO]: [RTE] Released 13 cached raw models after reload
[Render thread/INFO]: Sound engine started          ← 标题界面资源就绪
[Render thread/INFO]: Stopping!                     ← 空闲后正常退出，无崩溃
```

同日更早一次运行（同一实例）数值不同但阶段顺序一致：

```
[RTE] Reload phases: rails=18 ms eyeCandies=17 ms scriptResources=1 ms
                     trainScripts=164 ms builtinTrains=2 ms
```

要点：

1. 能稳定到标题界面（音效子系统启动、无致命错误、无 mixin 崩溃）。
2. `Reload phases` 五个阶段与第 4 章表格一一对应，顺序固定，`trainScripts` 是唯一大头（164–287 ms）。
3. 唯一与 RTE 相关的告警是 `CatenaryDiagMixin` 找不到目标（那个 mod 没装），符合"默认 require=0、缺目标即跳过"的设计。
4. 本次实例里没有资源包提供 `rte_vehicle_lights.json`，因此日志**没有** `[VehicleLights] Loaded N light definitions` 行；该行只在真的解析到文件时才打印（`files > 0` 条件），可作为"灯配置是否被读到"的判据。

---

## 8. 我们要实现同等效果时的独立做法（算法层）

1. **灯位来源分层，带置信度**：显式 JSON（作者意图，最高）> 模型 LIGHT 部件（自动发现）> 内置灯位表（已知车型）> 尺寸兜底。每层产出"候选灯 + 置信度 + 来源标记"，面板里能显示"这盏灯是猜的还是配的"。
2. **用"部件条件"推断灯的方向角色，而不是猜坐标**：模型部件的 `FORWARDS`/`BACKWARDS` 条件 → 该灯在"正向运行时是头灯"；再结合编组第几节判定当前亮头灯还是尾灯。调头、重联都自动正确。
3. **灯状态用正交因子相乘**：`运行中 × 前端/后端 × 刹车 × 门开 × 闪烁 × 暗度`，每个因子单独可测、可配置、可日志。
4. **刹车用"速度下降 + 保持窗口"**：记录上一 tick 速度，下降即开窗（300–500ms），停住也算；不依赖宿主内部制动手柄，跨版本稳。
5. **屏幕空间表面光照**：深度反投影 → 视空间位置 → 导数求法线 → 逐灯锥内外 smoothstep + 距离窗 + `N·L`，最后用 `1-exp(-k·x)` 滚降合成。这样"地面光斑"不依赖几何，弯道/坡道不会穿帮。
6. **屏内体积散射**：沿"相机→像素"视线求与灯球的弦，固定步数（8–12）积分，锥内才累加，配一个前向散射相位函数；步数用抖动打散避免条带。
7. **billboard 光斑只做"亮点"**：面向相机、按 `视线·灯轴` 的幂次给强度、沿视线拉近一点点避免 z-fighting；背面（点积 ≤ 0）不画。它便宜且能表达"远处一盏灯"。
8. **暗度门控而不是开关**：`暗度 = 1 - max(方块光×0.6, 天空光×昼夜)`，光锥强度乘暗度、光斑保留 60% 底噪；白天是"看不见"而非"关掉了"。
9. **轨道段碰撞三件套**：弧长采样（弯道友好）+ 横向列（多股/宽结构）+ 显式布局（站台/道岔）；盒按 16×16 区块分桶，跨区块重复登记；缓存用"源参数签名"失效，而不是每 tick 重建。
10. **车体碰撞采样化 + 站车顶搬运**：局部盒 → 0.5 格采样 → 世界 AABB（避免真 OBB 求交）；开门整侧放行；站车顶用"旧车体局部坐标 → 新车体位姿"搬玩家并同步 yaw 增量。
11. **宿主资源重载首尾插桩**：HEAD 清自己的缓存，TAIL 按阶段跑自己的注册表并打点计时；单包失败隔离 + 一次性错误汇总；有"手动重载"入口。
12. **能力探测 + 一次性降级**：光影/GL 能力/第三方兼容统一走一个仲裁层；每路渲染（光斑、光照 pass、动态光源）各自独立失败、独立关闭，任一失败不拖垮其它路。

---

## 9. 照亮到底由谁贡献（反编译复核，2026-11-14）

本节是**反编译证据**（`D:/MC-MTR-JS/temp/rte-x/cfr` + jar 常量池扫描 + 官方 intermediary 映射表），
不是外部观察。目标只有一个：回答"它那些看起来像被车灯照亮的地面/墙，到底是**真方块光照**，还是
**屏后处理假亮**"，以及我们该保留哪些本地光照机制。

**一句话结论：RTE 一行光照数据都没往世界里写。它的"照亮"= 一次读深度+读场景色的全屏后处理；
它唯一的世界几何是每盏灯一张加色 billboard（有深度测试）。**

### 9.1 证据一：全模组没有任何"写光照"的调用

| 类 / 方法 | 行为 | 结论 |
|---|---|---|
| `mixin/LevelRendererMixin` 三个 `rte$setSectionDirty*`（对应 `setSectionDirty` / 三参 / 四参重载） | 只把区块重建事件转给 `RailRenderDispatcher.registerLightUpdate`，让**它自己的轨道网格**重建一次 | 是"区块变了我要重建我的网格"，不是"我要改区块的光"。没有任何光照数据被写 |
| `mixin/PositionAndRotationLightMixin` 覆写 MTR 的 `PositionAndRotation.getLight(Vector)` | 用 `LightSampler.packed(world, x, y+1, z)` 换掉原返回值 | 只决定**车体顶点自己的 lightmap 采样点**（往上抬一格，避免车体埋在轨道里变黑）。是读 |
| `util/LightSampler` | 调 `Level.getBrightness(BLOCK/SKY, pos)`；被遮挡时在 7 个邻居偏移里取最大值；最后用 `LightTexture.pack`（`method_23687`）打包成整数 | `pack` 只是把两个 0–15 拼成一个 int，**不写任何缓冲**。纯读 |
| 全 jar 扫描 | `setBlockState`(`method_8501`) 只出现在轨道节点放置的网络包 / 切片笔刷物品里；`ChunkSection` 状态、`LightmapTextureManager`、`method_33157`（方块光照 section 通知）**零命中** | 没有 chunk relight、没有 lightmap 写入、没有动态光源 |

对照：我们的 `BeamBlockLight` / `BeamRelight` / `RailRelightScheduler` 走的是**完全相反**的路
——把 0–15 的方块光写进区块顶点 lightmap，再触发 section 重建。RTE 没有这一层。

### 9.2 证据二："照亮"是一次屏幕空间后处理，而且它真的给材质加光

调用链：`mixin/GameRendererMixin.rte$renderVehicleLighting`（注入点 = `renderLevel` 返回之后的
`At.Shift.AFTER`）→ `VehicleLightRenderer.renderLighting()` → `VehicleLightPass.render(spots, projection, view)`。

`VehicleLightPass` 的行为（类 / 方法 / 行为，逐条）：

1. `render(...)`：从主帧缓冲取 `getDepthAttachment()`（`Framebuffer.method_30278`）；用
   `glCopyTexSubImage2D` 把**场景颜色**拷进自建纹理（因为要边读边写）；绑定 12 个 spot 的
   uniform（位置/方向/内外锥余弦/颜色/范围）；`glDrawArrays(GL_TRIANGLES, 0, 3)` 画一个全屏三角形。
2. 片元程序 `main()`：`texture(SceneDepth)` 取深度 → `InverseProjection`/`InverseView` 反投影出
   **视空间位置** → 用 `dFdx × dFdy` 叉乘出**法线**（朝相机翻转）→ 逐灯累加两项：
   - **表面项** `surface`：`LightColor * cone(smoothstep(cosOuter, cosInner)) * 距离窗
     ((1-r⁴)²/(1+0.04d²)) * max(dot(N, L), 0)`；
   - **体积项** `inscatter`：把"相机→该像素"的视线上落在灯球内的弦切 8 步，每步判锥内 + 距离窗 +
     各向异性相位函数。
3. 合成：`added = scene * surface * 9.0 + surface * 0.035 + inscatter * 0.35`，再
   `lit = scene + (1 - exp(-added * 1.2)) * (1 - scene * 0.5)`，**直接写出 `fragColor`**（混合关闭）——
   是一次颜色**替换**，不是叠加。

所以"地面/墙被照亮"是真的：表面项乘的是 `scene`（已经画好的地面像素颜色，即材质 albedo），
`N·L` 的法线来自深度导数。它**不需要**任何方块光照，也不会出现 0–15 的阶梯和"整条铁轨发光"。
它的上限也很清楚：只有一层深度、只有正面法线，所以没有自阴影，被别的物体挡住的表面拿不到光。

### 9.3 证据三：光锥是"深度遮挡"，不是无遮蔽叠加

同一份片元程序：天空像素（`depth >= 0.999999`）直接跳过表面项；体积项的终点被
`end = min(along + halfChord, viewDistance)` 夹在**场景深度**上。也就是说墙、站台、车体会把光柱
截断在它们身上——这就是"光雾看起来在实体后面"的原因，全程没有几何锥、没有深度拷贝纹理之外的
遮挡机制，也**没有**读 stencil/shadow map。附带的 `gfx/shader/OffsetLightRenderType.offsetBeaconBeam`
（beacon-beam 式几何光，`POLYGON_OFFSET_LAYERING`、默认 LEQUAL）在 beta.4 的类里**没有任何调用点**，
是没接线的死代码——说明连它家的"几何光"方案也是深度测试的。

### 9.4 顺便把灯晕（billboard）也钉死

`VehicleLightRenderer.drawFlare` + `VehicleLightRenderType.glow`，解码 RenderType 的每个 shard：

| shard | 实测值 | 含义 |
|---|---|---|
| 顶点格式 | `POSITION_COLOR_TEXTURE` | 位置+UV+颜色，无 lightmap、无法线 |
| 程序 | `POSITION_COLOR_TEXTURE_PROGRAM`（原版 `position_color_tex`） | 不是自写 shader |
| 贴图 | `TextureStateShard(tex, blur=true, **mipmap=false**)` | **显式关掉 mipmap** |
| 透明 | `LIGHTNING_TRANSPARENCY` | `SRC_ALPHA, ONE`，即加色 |
| 写掩码 | `COLOR_MASK` | 只写颜色，不写深度 |
| 剔除 | `DISABLE_CULLING` | 双面 |
| 深度测试 | **未声明 → 默认 LEQUAL（开）** | 会遮挡 |

尺寸/透明度：`sizeScale = (0.45 + 1.4·glare)·(1 - pull)`，`alpha = brightness·factor·(0.25 + 0.45·glare)`
（正对相机也只有基础值的 **70%**，永远不会到 1）；`pull = min(0.9, 0.5·距离)`；灯位 z = ±(半长 + **0.02**)，
即灯位在车体端面**外面 2 cm**。

### 9.5 我们该保留 / 删除哪些本地光照机制

1. **`BeamBlockLight`（真实方块光照）：保留，但从"主力"降为"可选增强"**，默认关、按侧线可开。
   理由：RTE 用一条后处理就拿到了更好的结果——跟着材质变、无区块重建、无光照阶梯、无 Sodium 冲突、
   白天自然变淡；而方块光照的独有价值只剩"光是世界状态，远处/延迟渲染也一致"，代价却正是
   "整段铁轨发亮 + 每帧重建 section"。保留它是因为它在**没有我们的后处理**（光影接管、pass 被跳过）
   时仍然是唯一能给地面上色的东西。
2. **下一步真正值得投的是给 `ScatterPass` 补 surface 项**，不是继续调 `BeamBlockLight`。同一个
   pass 已经拿得到深度和场景色，缺的只是 `N·L` 那三项（锥 smoothstep + 距离窗 + `1-exp` 滚降）。
   补上之后"照亮地面"不再依赖方块光照，`beamBlockLight` 才能真正默认关。
3. **该删除/降级的**：`railLight`（把灯烘进 ANTE/NTE 轨道顶点，v23 已默认关）连同 `railRelight*`
   一起标记 deprecated；surface 项上线后 `BeamRelight` + `RailRelightScheduler` 的 section 重建链路
   整条可以下线（先留一个版本的开关再删）。
4. **灯晕三处对齐 RTE**（本版本已做前两条）：① 深度比较改 `GL_ALWAYS`（它的灯位在端面外 2 cm 所以
   能吃 LEQUAL，我们的灯位在端面内 0.08 + inset，斜视时半个四边形埋在车头网格里）；② 尺寸下限从
   6 px 提到 `max(8, 屏高×1.2%)`、默认尺寸 0.9 → 1.4；③ 贴图理想上应像它一样**关 mipmap**——我们
   现在走 `TextureManager` 无法直接声明，如果诊断行显示"尺寸/透明度都正常但仍看不见"，这就是下一个
   嫌疑（128×128 的核只占半径 8%，mip 平均后有效 alpha 会掉到 ~0.1）。

---

### 附：与 LTE 现状的差距（只列缺口，不评价优劣）

| 能力 | LTE 现状 | RTE beta.4 的做法 | 缺口性质 |
|---|---|---|---|
| 灯位来源 | 内置表 + beam3D JSON | 再加"模型 LIGHT 部件自动发现 / 旧格式 lamp 部件 / 脚本灯面" | 覆盖面 |
| 头尾判定 | 按表 + 方向 | 编组位置 × 部件条件推断 | 正确性（重联/调头） |
| 光斑/光柱 | 几何光锥（自写 shader） | 屏幕空间表面 + 屏内体积 | 表现层，两套可共存 |
| 刹车灯 | — | 速度差 + 400ms 窗口 | 功能缺失 |
| 轨道段碰撞 | 无 | 弧长采样 + 列 + 显式布局 + chunk 桶 | 功能缺失 |
| 车体碰撞 | 分段 OBB（外部碰撞/推挤/伤害） | 局部盒采样 + 站车顶搬运 + 服务端豁免 | 我们更全，缺"站车顶" |
| 资源阶段计时 | 无统一打点 | 5 阶段毫秒日志 | 运维 |
