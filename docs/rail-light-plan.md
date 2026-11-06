# 轨道照明方案 A（mixin 进 ANTE）实施计划

> **【已废弃 · 2026-11】本文件是历史决策记录，不是当前实现说明。**
> “光影下轨道不亮”的根因已查明并修复：本模组自己的 mixin 插件用 `Class.forName` 提前装载了 ANTE 的类，
> 使其它模组的 mixin 无法注入；改为资源路径探测后，光影下烘焙光正常显示。
> 因此文中描述的兜底方案 B+（`RailGlow.java` 自绘光带及其 `railGlow*` 配置、界面开关）已**整体删除**，
> `RailRelightScheduler` 中的 `glowFallback` 跳过分支也一并移除（光影下照常重建）。
> `railLightShaderBoost` 保留但默认改为 `false`。下文的配置表、文件清单与验证步骤均已过时。

> 现状：B+（自绘光带）观感不合格——位置会压进轨道模型、宽度只能猜、平涂像塑料片。`railGlow` 默认已改为 `false`。
>
> **2026-11 再次变更**：`RailGlow` 以「能力保留、默认关闭」的形式恢复（`railGlow=false`，
> 光影内外都不会自动启用；参数 `railGlowWidth/Alpha/Lift/Ahead/Step`），
> `RailRelightScheduler` 与之完全独立（不再有 `glowFallback` 跳过分支）。
> 重建调度改为**按车速自适应间隔 + 五级优先级队列**，预算 `every=3 × budget=2 ≈ 13.3 次/s`，
> 目标：两列车 25 格/s 时滞后均值 ≤3 格、峰值 ≤6 格（仿真值 2.3 / 3.8 格）。
> 界面已精简为「总开关 + 侧线配置 + 立即重载」，其余参数只在 `config/mtr-headlight.json`。

## 目标
让 **ANTE/NTE 的轨道模型本身**的顶点光照吃到我们的车灯，形状 100% 来自模型，不再有贴片。

## 已知事实（都是反编译确认过的）
- `fabric.cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk#rebuildBuffer(net.minecraft.class_1937)`：
  重建网格时调用 `Level.getBrightness(LightLayer, BlockPos)`（`class_1937.method_8314`），再用 `LightTexture.pack(block, sky)` 烘进顶点。
- `RailRenderDispatcher#registerLightUpdate(int,int,int,int)` 是 public，触发指定范围内轨道 chunk 重建。
- `RailModelProperties` 暴露 `rawModel / uploadedModel / boundingBox / repeatInterval / yOffset`。
- ANTE 版本固定 1.1.0+1.20.1（已停更），可以锁死 mixin 目标。

## 实现
1. **gradle 加 mixin**
   - `build.gradle`：`loom { mixin { defaultRefmapName = "mtr-headlight.refmap.json" } }`
   - `src/main/resources/mtr_headlight.mixins.json`（package `dev.js.mtrheadlight.mixin`）
   - `fabric.mod.json` 加 `"mixins": ["mtr_headlight.mixins.json"]`
   - `libs/` 放 ANTE jar 作 `compileOnly`（只为引用 `RailRenderDispatcher`）

2. **范围标记 mixin**
   - `MixinMeshBuildingRailChunk`：`@Inject(method = "rebuildBuffer", at = @At("HEAD"))` 置 `RailBakeScope.enter()`；
     `@Inject(at = @At("RETURN"))` 退出（用 try/finally 语义，注意异常路径也要退）。
   - `MixinLevelBrightness`：mix `World.getBrightness(LightLayer, BlockPos)` @RETURN，若 `RailBakeScope.active()` 且我们的动态光更强，则 `setReturnValue(max(原值, 动态光))`。
     —— 动态光取自 `LightController` 现有的光束线段（点-线段距离 → 衰减 → luminance）。

3. **重建节流**
   - 每 4 tick（可配 `railRelightTicks`）对每列可见车调用 `RailRenderDispatcher.registerLightUpdate(x, y, z, 8)`，
     只对“自上次重建移动超过 2 格”的车触发，避免原地怠速时空转。
   - 预算：每次最多 N 列（可配 `railRelightMaxTrains`，默认 4）。

4. **配置**
   - `railLight`（默认 true，A 方案生效后）
   - `railRelightTicks`（默认 4）
   - `railRelightRadius`（默认 8；现已废弃，请求覆盖整个 32×32 chunk，见下节）
   - `railRelightMaxTrains`（默认 4）

## 风险 / 验收
- 亮度刷新 4~5Hz：车快时轨道上的亮区会“跳”，这是烘焙网格的物理限制。
- 重建开销：需要实测 FPS；若掉帧就把 `railRelightTicks` 调到 8~10，或只对玩家前方 32 格内的车触发。
- 内存：ANTE 的 `rebuildBuffer` 内部会 `close()` 旧的 `Model`/`VertArrays`，不会泄漏；我们只用线程局部标记，不持有对象。
- 验收：① 单轨/双轨+轨枕/坡道/弯道 光照贴合模型 ② 无塑料片 ③ FPS 与未启用时相差 < 3 ④ `/externallights` 里可关

## 回退
`railGlow` / `railLight` 都默认关闭即可完全回到当前可用状态；B+ 的 `RailGlow.java` 保留但不启用。

## 实现注记（1.20.1，已落地）
- 1.20.1 没有 `World#getBrightness(LightLayer, BlockPos)`：ANTE 实际调用的是
  `Level#getLightLevel(LightType, BlockPos)`，而它是 `BlockRenderView` 的 **default 方法**。
  Mixin 0.8.7 不支持往接口 default 方法里 `@Inject`（只支持 `@Overwrite`），因此
  `BrightnessMixin` 改为在 `MeshBuildingRailChunk#rebuildBuffer` 里 `@Redirect` 那两处
  `World.getLightLevel` 调用：只放行 `LightType.BLOCK`，SKY 原样返回。作用域反而更窄。
- `RailBakeScopeMixin` 仍按要求在 `rebuildBuffer` 的 HEAD/RETURN 维护 ThreadLocal 标记，
  `RailRelightHooks` 每 tick 清理一次，覆盖异常路径残留。
- 动态光优先走 `LambDynLights#getDynamicLightLevel(BlockPos)`（反射缓存 MethodHandle，
  因为 LDL 是 intermediary 名），失败时退回 `RailLightState` 里本模组光束的快照。
- 刷新触发点取列车中点 + 前后两端（`registerLightUpdate`），只对自上次触发移动 >2 格的
  列车、每 `railRelightTicks` tick、每次最多 `railRelightMaxTrains` 列。

## 实现注记（2026-11，四版本+NTE，修正版）
### registerLightUpdate 的真实签名（关键坑）
ANTE 1.1.0 与 NTE 0.3.3 的实现与 NTE 自带调用点（`LevelRendererMixin#setSectionDirty`）证明：

    registerLightUpdate(int x, int ySectionFrom, int ySectionTo, int z)   // 全是 section 坐标

- 四个参数都以 **16 格 section** 为单位（`block >> 4`），第 4 个参数是 **z**，不是 radius。
- 内部 `chunkIdFromSectPos(x, z)` + `containsYSection(yFrom, yTo)`：一个 rail chunk 覆盖
  32×32 格，所以一个位置一次调用即可覆盖半径 16 格内。
- 早期实现按 `(x, y, z, radius)` 调用，结果标记到无关 chunk，日志无报错但**完全看不到效果**。

### 触发方式
定时轮询（`railRelightTicks`，默认 4 tick）+ **脏检查**（见下节）：玩家脚下 chunk + 每列可见列车的
**车灯光束**进队列。队列和签名缓存都以 **32×32 的 rail chunk** 为键（`chunkIdFromSectPos` 把
section 的 x/z 各右移一位），所以一个 chunk 里的四个 section 只算一次请求。

每列车的请求是两处：

- 光束**起点**所在 chunk（`front + forward * headOffset`）；
- 光束**终点 + 提前量**所在 chunk（`headOffset + headLength` 再往前 `clamp(4, 24, |speed|*8)` 格）。

两者大多数时候落在同一个 chunk（光束 11 格 < chunk 32 格），去重后不产生额外调用；只有车头贴近
chunk 边界时才会真正多出一次请求 —— 那正是过去"车头前方一格是黑的、过一会儿才亮"的断带来源。

提前量用 `Train#getSpeed()`（MTR 里是**格/tick**，`railProgress += speed * delta`）乘 8 tick，
即"一个轮询 + 一次重建机会"内列车能跑的距离，所以车速越快提前量越大，且**不需要记住上一帧位置**。
每列车唯一保留的状态是"光束起点上一次在哪个 chunk"（`Track`），用于清退。

### 清退（列车开走后旧位置尽快熄灭）
- 光束起点换 chunk 时，把**旧 chunk** 再入队一次：重建时那一段已经没有光束，于是变暗。
- 列车 `isRemoved`、被静音、超出 `maxDistance`、或连续 `PRUNE_TICKS`(20 tick) 没被看到 → 入队其
  旧 chunk 并忘记这列车。所以"开走后还亮着"最多持续一个预算周期 + 一帧，而不是永久残留。
- 传送/同步造成的跨多个 chunk 跳跃只清退最后一处 chunk（无法知道中间路径，已知限制）。

### 脏检查 + 硬预算（2026-11，修 65 次/秒的重建风暴；2026-11 二改，让路给地形加载）
旧版只按时间节流，**从不比较光照**：每 4 tick 无条件把玩家和每列车前/中/后标记为脏，实测
`rebuilds` 每秒 +65，而 ANTE 每次重建一个 32×32 轨道网格（模型采样 + 顶点烘焙 + GPU 重传），
绝大多数重建算出来的顶点与上一次完全相同。

现在的流程：
1. 轮询时只把目标 chunk 放进队列（去重，上限 24 条；溢出时丢**最旧**那条，保住车头前方的请求）。
2. **每 `railRelightEveryTicks`（默认 3）tick** 才允许一次 `registerLightUpdate`（默认
   `railRelightBudget=1` 次）：
   - 在 **32×32 chunk** 上按 4 格步长采 8×8 个点取 `RailDynamicLight.level()`，得到签名（`byte[64]`）；
   - 与"当前屏幕上那份几何是用什么光照烘出来的"（缓存里的签名）比较，只有当某点变化
     ≥ `railLightDelta`（默认 1）时才调 `registerLightUpdate`；否则记一次 `skipped`（**不占预算**）。
3. 玩家静止（没离开所在方块）+ 附近没有任何车在跟踪 + 队列空 → 轮询退到 16 tick 一次：
   只做一次玩家 chunk 的采样，光照没变就一次宿主调用都没有。
4. 世界切换 / 配置关闭 / `railLightUnderShaders=false` 且开着光影时清空队列与缓存。

**为什么不再有 per-chunk 冷却**：预算已经变成"每 N tick 一次"的全局节流，而同一个 chunk 一轮询
最多入队一次（去重），所以每 chunk 天然 ≤ `20/railRelightTicks` 次/秒。再叠一层冷却只会让车头
那个 chunk 在冷却期被丢弃、等下一次轮询才能重新入队，实测把跟随速率砍半。

签名是**位置相关**的（不是单一最大值）：最大值在列车于同一 chunk 内移动时几乎不变，若只用
max 会让亮区卡在原地不动 —— 那正是用户反馈的"亮区短暂停留"。

估算（独立仿真 `Sim.java`，一条直线、一张共享重建槽位；旧 = 每 tick 1 次 + 轮询 8 + 8×8 采样，
新 = 每 3 tick 1 次 + 轮询 4 + chunk 8×8/4 格采样 + 前后两次请求）：

| 场景 | 旧 rebuilds/s | 新 rebuilds/s | 旧滞后(格, 均值/峰值) | 新滞后(格, 均值/峰值) |
|---|---|---|---|---|
| 静止（车停着、玩家不动） | 0.02 | 0.02 | 0 / 0 | 0 / 0 |
| 1 列车 10 格/秒 | 3.7 | 5.9 | 1.8 / 4.0 | 1.0 / 2.5 |
| 1 列车 25 格/秒 | 4.8 | 6.6 | 4.3 / 8.8 | 2.9 / 10 |
| 1 列车 30 格/秒 | 4.8 | 6.6 | 4.3 / 10 | 3.7 / 12 |
| 2 列车 25 格/秒 | 9.6 | 6.7 | 4.3 / 10 | 8.2 / 25 |
| 4 列车 25 格/秒 | 19.1 | 6.7 | 4.4 / 10 | 15.6 / 40 |

旧版单列车是"每 8 tick 一次轮询"限住的 3.7–4.8 次/秒；列车越多它线性上涨（每列一份），
新版是**全局硬上限** 20/3 ≈ 6.7 次/秒，与列车数量无关 —— 地形加载时被抢走的帧数因此从"随车数增长"
变成常数。单列车时新版比旧版多约 40% 调用，换来的是滞后从 4.3 格降到 2.9 格；嫌加载慢就把
`railRelightEveryTicks` 调到 4（5.0 次/秒，滞后均值 5.1 格）或 6（3.3 次/秒）。

4 列以上同时高速经过时预算是真不够（滞后 15 格以上），`starved` 计数会持续 > 0 —— 这是 ANTE
每帧 1 个 chunk 的硬限制，只能靠降低同时跟踪的车数（`railRelightMaxTrains`）或接受降级。

### Iris / MakeUp 光影下轨道光照消失：查证结论
代码层能确认的链路（ANTE 1.1.0 反编译，`libs/MTR-ANTE-1.1.0+1.20.1.jar`）：

1. `ClientConfig#getRailRenderLevel()` 返回 `canInstance() ? 3 : 2`；
   `ShadersModHandler#canInstance()` = `canUseCustomShader() && supportVertexAttribDivisor`，
   而 `canUseCustomShader()` = `!isShaderPackInUse() && !isGL4ES`。
   **开光影 ⇒ railRenderLevel 3→2 ⇒ ANTE 从 `InstancedRailChunk` 退到
   `MeshBuildingRailChunk`**（`RailRenderDispatcher#drawRails` 里 `isInstanced = level==3`，
   切换时 `clearRail()` 全量重建）。
2. `ShaderManager#setupShaderBatchState`：`canUseCustomShader()==false` 时不再用 ANTE 自带的
   `rendertype_entity_cutout_modelmat`，而是 `RenderLayer#startDrawing()` +
   `RenderSystem.getShader()` —— 即**光影包自己的 gbuffers 程序**（Iris 的 `gbuffers_entities`）。
   `BlazeRenderType` 用的是 `RenderLayer.getEntityCutout(tex)` 的 phases，所以 program phase
   存在，`startDrawing()` 会真的 `setShader`。
3. 光照值在两条路径里都写进顶点 UV2（`LightTexture.pack(block, sky)`）：
   `MeshBuildingRailChunk#rebuildBuffer` → `RawMesh#appendTransformed` 的
   `newVertex.light = light`，映射表 `UV_LIGHTMAP`（location 4，`glVertexAttribIPointer`
   SHORT×2，和 vanilla `POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL` 完全一致）。
4. Iris 侧：`ShaderAttributeInputs` 构造函数按 `"UV2"` 置 `light=true`，于是
   `VanillaTransformer` 把 legacy(`#version 120`) 包的 `gl_MultiTexCoord1/2` 改写成
   `iris_UV2`（属性 4）。MakeUp-UltraFast 的 `gbuffers_entities` 走
   `common/solid_blocks_vertex.glsl` → `src/light_vertex.glsl`，
   `lmcoord` 就是 UV2，`candle_color` 由 `lmcoord.x` 算 —— **包是读这个通道的**。

实测日志（`logs/latest.log`，23:07:30 切到 MakeUp，23:07:36 关掉）显示这 6 秒里
`calls` 从 125653 涨到 136002、`hits` 从 24186 涨到 27027：**光影开着的时候动态光确实还在被
烘进顶点**。

结论：**数据通路在 Iris 下是完整的，我们这边没有"光没写进去"的 bug**；是否显示由包的
deferred 流程决定。因此没有写任何"猜一个 shader hack"的补丁，只做了两件事：
- `ShaderPackProbe`（反射 Iris `IrisApi#isShaderPackInUse` / OptiFine），
  **状态变化时打一行**日志（不是每帧），说明当前走的是哪条路径；
- 配置项 `railLightUnderShaders`（默认 true）：确认包吃掉烘焙光时可以一键停掉整条重建链，
  而不必手改 JSON。

待实机确认（无法离线判定）：在 MakeUp 下轨道是**整体恒亮**（UV2 被当成常量 240）还是
**整体偏暗**（属性没绑上）。前者说明包对该 draw 的 lightmap 取样走了常量分支，后者说明
属性/程序不匹配；两者的修法都在 Iris/包一侧（给 ANTE 的几何配 `gbuffers_entities` 的
program override），不在本模组能改的范围。

> **2026-11 更新：已实测定论 → 见本节末尾「光影下轨道光照：确定性结论与 B2 兜底」。**
> 三个包全部看不到 ⇒ 走的是"属性没读到"那条分支；已改为光影下自绘光带（`railGlowUnderShaders`）。

### RailBakeScope 已删除
`@Redirect` 只匹配 `rebuildBuffer` 内的调用点，进入时必然在烘焙中，ThreadLocal 标记不再需要。

## 2026-11 追加：光影亮度、玩家光源、方速兼容

### 1. MakeUp 到底怎么用 UV2（反编译 + 包源码实证）
之前只查到"包读 `lmcoord.x`"，这轮把两个分量都追到了底：

- Iris 1.7.6 `net/irisshaders/iris/pipeline/transform/transformer/VanillaTransformer#transform`：
  ```java
  if (parameters.inputs.hasLight()) {
      root.replaceReferenceExpressions(t, "gl_MultiTexCoord1", "vec4(iris_UV2, 0.0, 1.0)");
      tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "in ivec2 iris_UV2;");
  } else {
      root.replaceReferenceExpressions(t, "gl_MultiTexCoord1", "vec4(240.0, 240.0, 0.0, 1.0)");
  }
  ```
  → 包（`#version 120` 老式写法）看到的 `gl_MultiTexCoord1` 就是我们的 UV2，**两个分量原样透传，
  没有 clamp、没有互换、没有丢弃**。
- `MakeUp-UltraFast-9.3c/shaders/src/basiccoords_vertex.glsl`：
  `lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy * 1.0323886639676114;`（两分量同一个变换）
- `shaders/src/light_vertex.glsl`：
  ```glsl
  vec2 illumination = lmcoord;
  illumination.y = (max(illumination.y, 0.065) - 0.065) * 1.0695;
  visible_sky  = clamp(illumination.y, 0.0, 1.0);      // ← 只由 sky 分量决定
  candle_color = CANDLE_BASELIGHT * (pow(illumination.x, 1.5) + sixth_pow(illumination.x * 1.17));
  ...
  #ifdef CAVEENTITY_V                                 // gbuffers_entities 定义了这个宏
      candle_cave_strength = (direct_light_strength * .5) + .5;
      candle_cave_strength = mix(candle_cave_strength, 1.0, visible_sky);
      candle_color *= candle_cave_strength;
  #endif
  ```
  以及 `omni_light = mix(omni_color_min, omni_color, visible_sky)`，`omni_color_min` 的亮度
  正好是 `AVOID_DARK_LEVEL = 0.030`（`lib/config.glsl`，"Minimal omni light intensity in caves"）。

**结论修正**：block 分量没有被吃掉（`candle_color` 就是它算的），但**只有 block 没有 sky 的
轨道会被打两折**：`visible_sky = 0` 时 `candle * 0.5`，并且整个环境项塌到 0.03 的"洞穴底噪"。
按 `BLOCKLIGHT_TEMP=1` 的 `CANDLE_BASELIGHT ≈ (0.275,0.174,0.09)` 算，block=12 时
`candle ≈ 0.30 → ×0.5 = 0.15`，而 vanilla 无光影时 block 12 的光照贴图差不多是 0.8 的量级：
**同一块轨道在 MakeUp 下暗 5 倍左右，看起来就是"光照没生效"**。隧道 / 高架下没有天空的轨道
永远拿不到那另一半。

修法（`railLightShaderBoost`，默认 true）：开光影时把抬高的 block 值同时写进 sky 分量，
即让 ANTE 的 `LightTexture.pack(block, max(sky, block))` 成立 —— 我们只在 `resolve` 里返回
更高的 sky 值，打包仍然由 ANTE 自己做，没有新增属性。只在"我们真的抬高了 block"的顶点上生效，
没光影时不生效（否则会伪造 vanilla 天空光）。开关变化时 `RailRelightScheduler.invalidate()`
清签名，下一轮全量重烘一次。

另外还有一个纯配置层面的发现：`candle` 对 block 是**超线性**的
（`x^1.5 + (1.17x)^6`），block 15 的 candle 是 0.79，block 12 只有 0.30。所以光影下觉得不够亮时，
先把 `headLuminance` 提到 15（默认 12），比任何 shader hack 都管用。

### 2. 玩家手持 / 放置光源（LambDynamicLights 4.4.0）
`RailDynamicLight` 里有两处真 bug，两处都让"LDL 装了但等于没装"：

1. `MethodHandles.lookup().unreflect(method).asType((BlockPos)double)` —— `unreflect` 出来的是
   `(LambDynLights,BlockPos)double`，`asType` **不能丢参数**，会抛
   `WrongMethodTypeException: cannot convert MethodHandle(Api,Object)double to (Object)double`
   （用 `D:\MC-MTR-JS\temp\HandleProbe.java` 复现过），异常被 `catch (Throwable)` 吞掉后
   `levelHandle = null`。→ 改成 `unreflect(m).bindTo(instance).asType(...)`。
2. `if (!Double.isNaN(lambda)) return lambda;` —— LDL 正常返回 0（附近没有光源）时会把
   自车灯光束的 fallback 一起盖掉。→ 改成两者取 max。

签名已用 `javap` 对着**实装的 jar** 核对：`lambdynamiclights-4.4.0+1.20.1.jar` 里
`public static LambDynLights get()` 与 `public double getDynamicLightLevel(class_2338)` 都在，
返回值是 0–15（它自己的 `getLightmapWithDynamicLight(double,int)` 里 `(int)(level * 16.0)`）。
`lambdynamiclights-api-4.4.0+1.20.1.jar` 只有 `api` 包、没有任何引擎类，查不到 level，所以仍走
主 jar 的反射。

调用点没变：只在轨道烘焙（`resolve`）里查；`RailRelightScheduler#sample` 的脏检查本来就在用
同一个方法。

### 3. 方速（fangsu 1.3.2）兼容
`fangsu.mixins.json` 里跟轨道有关的是 `NteBakedRailMixin` / `NteRailLayersMixin`
（`LevelRendererMixin` 只是 `renderLevel` 里提交自己的 draw scheduler，`RailGeometryMixin` /
`RailAngleMixin` 改的是 MTR 的 `Rail` 数据类）。反编译后逐条对照：

| 方速注入点 | 我们 | 冲突 |
|---|---|---|
| `RailRenderDispatcher#getModelKeyForRender`（HEAD/RETURN，cancellable） | 不碰 | 无 |
| `RailRenderDispatcher#registerRail/addRail/removeRail/clearRail`（HEAD/RETURN） | 不碰 | 无 |
| `BakedRail#<init>`（`@ModifyArg` 包 `ArrayList.add`，给分层模型加倾斜） | 不碰 | 无 |
| — | `World#getLightLevel` redirect in `MeshBuildingRailChunk/InstancedRailChunk#rebuildBuffer` | 方速没有同点注入 |
| — | `RailRenderDispatcher#registerLightUpdate` 调用方 | 方速不调用也不改 |

方速**从不**注入 `rebuildBuffer`、`getLightLevel`、`registerLightUpdate`，所以没有"同一方法两个
`@Redirect`"这种必须靠 priority 仲裁的情况，不需要降级或跳过。反过来它和我们是**互补**的：
方速的"多模型图层"是在 `Layers#add` 里用反射拿
`InstancedRailChunk`/`MeshBuildingRailChunk` 的 `(Long,String)` 构造器自己 new chunk、塞进
`railChunkMap` / `railChunkList`（`com/fangsu/customItem/NteRailMultiModelBridge.java:273-283,
360-370`）。因为我们的 mixin 打在**类**上，方速建出来的那些 chunk 一样吃到 redirect；而
`registerLightUpdate` 是遍历 `railChunkMap` 的**所有 modelKey**，所以它们也会被一起标脏重建。

我们的 mixin 配置加了 `"priority": 1500`（默认 1000），只为了将来方速若真的插到同一方法时，
我们的 redirect 稳定地套在外层，不改任何现有行为；`FangsuCompat` 在启动时打一行日志
（仅一次，不在 tick / render 里）便于看支持日志。它只按 mod id 探测，不引用方速任何类名。

### 4. 方速手动道岔（Turnout）：不支持，保持现状
结论：**玩家手动标记生成的道岔（`BlockTurnout` / `BlockEntityTurnout`）不会被车灯和
LambDynamicLights 动态光点亮，在不修改方速的前提下做不到；本模组不为其写任何 mixin。**

#### 4.1 反编译证据（fangsu 1.3.2, build20261102090023）

道岔是**方块实体自渲染**，不在 ANTE 的轨道 chunk 烘焙链里。逐层指令如下（`javap -p -c`，1.20.1 的
类名是 intermediary，`class_4587`=`MatrixStack`、`class_4597`=`VertexConsumerProvider`、
`class_765`=`LightmapTextureManager`）：

1. `com/fangsu/blocks/client/TurnoutBlockEntityRenderer#render(BaseObjBlockEntity,float,class_4587,class_4597,int,int)`
   第一条实际业务指令就是把 **light 原样转发**给父类：

   ```
   0: aload_0
   1: aload_1              // entity
   2: fload_2              // partialTick
   3: aload_3              // matrices
   4: aload         4      // buffers
   6: iload         5      // light  ← 未改动
   8: iload         6      // overlay
  10: invokespecial #32    // BaseBlockEntityRender.render:(Lcom/fangsu/blockEntities/BaseObjBlockEntity;F
                          //   Lnet/minecraft/class_4587;Lnet/minecraft/class_4597;II)V
   ```

   该方法剩下的部分只在“手持道岔工具且无 GUI”时画一个准星图标，颜色/光照是写死的
   `ldc #162 // int 15728880`（= `0xF000F0` 全亮），与模型无关：

   ```
   44: return                          // 不是手持工具 → 直接返回（图标也不画）
   95: invokestatic class_1921.method_23578 ...
   47: ldc #162  // int 15728880
   49: invokeinterface class_4588.method_22916:(I)  // VertexConsumer.light(0xF000F0)
   ```

2. `com/fangsu/blockEntities/client/BaseBlockEntityRender#render` 才是画 OBJ 的地方：

   ```
   12: aload_1
   13: getfield #63   // BaseObjBlockEntity.fullLight:Z
   16: ifeq 29
   19: bipush 15 / 21: bipush 15
   23: invokestatic #69  // class_765.method_23687(II)I   ← LightmapTextureManager.pack(15,15)
   26: goto 31
   29: iload 5                                                ← 否则用 vanilla 传进来的 light
   31: istore 8
   ...
   // 光值作为第三个参数进入方速自己的 draw call 队列：
  316: aload 9 / 318: iload 8 / 320: invokevirtual EyeCandyDrawCalls.commit:(DrawScheduler;Matrix4f;I)V
   或
  333: aload 4 / 335: aload 9 / 337: iload 8 / 339: invokevirtual EyeCandyDrawCalls.renderDirect:(VertexConsumerProvider;Matrix4f;I)V
   ```

   注意**这条链上一次 `getLightLevel` 都没有**：对
   `TurnoutBlockEntityRenderer / BaseBlockEntityRender / BlockEntityTurnout / ModelCluster / RawModel / RawMesh`
   六个类做 `grep -c "getLightLevel\|method_8314"`，结果全是 0。它也不引用
   `MeshBuildingRailChunk` / `InstancedRailChunk`（整包 `grep -rl` 只有
   `com/fangsu/customItem/NteRailMultiModelBridge$RenderAccess` 一个类引用它们）。
   也就是说：**道岔的光照不来自世界光照查询，只来自 vanilla 传给 BER 的那一个打包 int。**

3. `BlockEntityTurnout` 不会打开 `fullLight`（`BaseObjBlockEntity` 构造器里
   `iconst_0 putfield fullLight:Z`，`BlockEntityTurnout` 自己从不写这个字段），所以走的是
   `iload 5` 分支 —— 即 **BER 所在方块位置的 vanilla 打包光照**。

4. 这个 int 一路原样落到顶点属性，整块道岔模型共用一个值：

   ```
   RawModel.writeBlazeBuffer(...)：
     MaterialProp.attrState.lightmapUV != null ? lightmapUV : iload_3   ← 用传入的 light
   RawMesh.writeBlazeBuffer(..., int light, int overlay)
     → FaceList.addUnsortedMesh(mesh, matrix, light, overlay)
        → FaceList$MeshCall.<init>: putfield light:I
   FaceList$MeshCall.write(VertexConsumer)：
     304: getfield #29   // light:I
     307: invokeinterface #137 // class_4588.method_22916:(I)   ← 每个顶点都写这同一个 int
   ```

   `assets/fangsu/turnout/builtin/rail.mtl` 只有 `newmtl/Kd/map_Kd`，没有光照覆盖，所以
   `lightmapUV` 为 null，确认走“传入的 light”。

#### 4.2 后果（用户能看到的现象）
- 道岔的 OBJ（`assets/fangsu/turnout/builtin/mtr_default.obj` / `mtr_siding.obj`，含岔区钢轨、
  尖轨、辙叉、轨枕细节）**吃的是世界方块光**：在旁边放火把/灯笼它会亮，这是 vanilla 行为。
- 车灯和 LambDynamicLights 的动态光**不会**进这个值：LDL 4.4.0 的 mixin 只改区块光照数据
  （`ArrayLightDataCacheMixin` / `LightDataAccessMixin` / `AoCalculatorMixin`），不碰
  `getLightLevel`，也不碰 BER 的 light 参数；我们的车灯只 redirect 了 ANTE `rebuildBuffer` 里的
  `getLightLevel`。所以道岔那块 OBJ 永远停在环境亮度。
- 观感上就是：车灯扫过时两侧轨道亮起来，到道岔这一小段（岔区 OBJ）保持暗。

#### 4.3 为什么一开始不做（已被 4.4 取代，保留作为风险评估记录）

> **2026-11-05 更新：作者授权后已实现，见 4.4。** 下面两条否决理由针对的是"直接改签名/反射改字段"
> 这一类做法；4.4 的注入点与降级手段（`require=0` + 配置 plugin + 无编译期依赖）把"加载期硬崩"
> 变成了"静默跳过"，因此结论改变。

| 方案 | 为什么曾经否决 | 现在的处理 |
|---|---|---|
| `@ModifyVariable(index=5)` 改 `TurnoutBlockEntityRenderer#render` 的 light 参数，用 `RailLightInject.resolve` 抬高 block 分量 | 方速处于快速更新期：一旦改签名/改继承/改渲染路径，mixin 注入失败是 **加载期硬崩**（`InvalidInjectionException`）。 | 实际不采用 `@ModifyVariable`：它只给到 int，拿不到方块坐标；而且裸 index 有"对上了但改错变量"的隐患，改用 `@ModifyArgs` 钉死整个调用描述符（4.4）。 |
| 反射改 `BaseObjBlockEntity.fullLight` 字段 / 反射调它的 draw call | 同样是内部实现，字段随时改名；而且 `fullLight` 是**全亮**语义（`pack(15,15)`），改它只会让道岔整块惨白，依然不是"被车灯照亮"。 | 仍然不做，理由不变。 |

#### 4.4 已实现：`FangsuTurnoutRenderMixin`（2026-11-05，作者授权）

**注入点**（`javap -p -c` 于 fangsu 1.3.2 build20261102090023 确认）：
`TurnoutBlockEntityRenderer#render` 的第一条业务指令就是把 6 个参数原样转发父类：

```
0: aload_0 / 1: aload_1 / 2: fload_2 / 3: aload_3 / 4: aload 4 / 6: iload 5 / 8: iload 6
10: invokespecial BaseBlockEntityRender.render:(Lcom/fangsu/blockEntities/BaseObjBlockEntity;F
        Lnet/minecraft/class_4587;Lnet/minecraft/class_4597;II)V
```

所以 `@ModifyArgs` 打在**这条 `invokespecial` 上**，改第 4 号实参（也就是 light）。之后
`BaseBlockEntityRender#render` 里 `iload 5 → istore 8 → EyeCandyDrawCalls.commit/renderDirect` 用的
就是这个值（`fullLight` 为真时会被 `pack(15,15)` 覆盖，但 `BlockEntityTurnout` 从不写 `fullLight`，
所以走的就是我们改的那条）。

为什么不用 `@Inject` 抓参数：Mixin 要求 handler 参数类型与目标参数**完全相等**
（`Injector.checkCoerce` 只允许带 `@Coerce` 且 handler 是**子类**的窄化转换），所以抓 arg0 就必须在
源码里写 `com.fangsu.blockEntities.BaseObjBlockEntity` —— 正是禁止的编译期依赖。`@ModifyArgs` 的
handler 只吃 `Args`（mixin 自己的类），`args.get(0)` 拿方块实体（`Object`）、`args.get(4)` 拿 light，
一个方速类名都不出现。

**防御实现**（全部落地）：
- 目标用字符串 `@Mixin(targets = "com.fangsu.blocks.client.TurnoutBlockEntityRenderer")`；
- `@At target` 描述符里的 MC 类型写成 **intermediary**（`class_4587` / `class_4597`）：方速是生产
  jar，它自己的字节码就是这些名字，而我们的 refmap 对这份 mixin 没有任何条目（`unzip -p` 确认），
  所以按字面匹配，同时也钉死了调用形状（不会出现"对上了但改错变量"）；
- `require = 0`（另外配置里 `injectors.defaultRequire = 0`）；方法体 `try/catch(Throwable)`；
- 独立配置 `mtr_headlight.fangsu.mixins.json`：`required:false`、包 `mixin.fangsu`、
  plugin `FangsuMixinPlugin`（`Class.forName(..., false, loader)` 探测，先本类加载器再 context
  loader，全部 try/catch；宿主不在时**不打任何日志**）；
- 取值复用 `RailLightInject.resolveQuiet(world, BLOCK/SKY, pos)`（= vanilla 世界光与
  `RailDynamicLight.level` 的最大值），再用 `LightmapTextureManager.pack` 重新打包；`resolve` 本身
  保留计数版，新加的 `resolveQuiet` 不碰 `RailDiag` 计数（否则每帧每道岔都会推着它每 5 秒打一行）；
- 开关复用现有 `railLight` / `enabled` 配置，没有新增配置项。

**离线验证**（本仓库 `temp/verify/MixinPointCheck.java`，用 ASM 读我们编出来的 class + 真实方速 jar）：
`@Mixin.targets` / `@ModifyArgs.method` / `@At.target` 解析后，在方速的 `render` 里**恰好命中 1 条**
`INVOKESPECIAL`、是该方法第一条方法调用、6 个实参、`arg[4] = I`、handler 描述符 `(LArgs;)V` 通过
`ModifyArgsInjector.verifyTarget` 的形状要求。`PluginProbe` 另测了 plugin：无方速 → 两个目标都
`false` 且无异常无日志；有方速 → 都 `true`。

**只能实机验证的部分**：车灯扫过道岔时 OBJ 是否真的变亮（`renderDirect` 每帧重发顶点，理论上
逐帧生效、不需要像 ANTE 那样重建；`commit` 走 `DrawScheduler` 的路径未逐帧验证）、
以及 FPS 影响（每可见道岔每帧 2 次 `getLightLevel` + 1 次 LDL 查询）。

#### 4.5 已覆盖的部分（不要误判）
道岔之外，方速对**轨道本体**的多模型图层（`NteRailLayersMixin` / `NteBakedRailMixin` +
`NteRailMultiModelBridge` 自建 `InstancedRailChunk`/`MeshBuildingRailChunk`）仍然吃我们的
redirect —— 见上面第 3 节：我们的 mixin 打在类上，`registerLightUpdate` 遍历所有 modelKey。
所以"轨道亮了、道岔岔区 OBJ 没亮"是预期结果，不是回归。


### 宿主可选
- `mtr_headlight.ante.mixins.json`（1.20.1，包 `mixin.ante`，plugin `AnteMixinPlugin`）
- `mtr_headlight.fangsu.mixins.json`（1.20.1，包 `mixin.fangsu`，plugin `FangsuMixinPlugin`）
- `mtr_headlight.nte.mixins.json`（1.18.2/1.19.2/1.19.4，包 `mixin.nte`，plugin `NteMixinPlugin`）
- 都是 `required:false`；plugin 用 `Class.forName` 探测宿主，宿主不在就不应用、也不打日志。
- 每个 jar 只带该 MC 版本真实存在的那一份：NTE 无 1.20.1 构建，ANTE 无 1.18/1.19 构建。
  方速那份是 1.20.1 专有（`@Mixin(targets=…)` 指向的类不在编译类路径上，靠
  `-AMSG_MIXIN_SOFT_TARGET_NOT_FOUND=disabled` 关掉 Mixin AP 的"soft target 找不到"报错）。
- 宿主引用全部走反射 + `try/catch(Throwable)`：`RailRelightDispatcher`、`HostProbe` 里
  只有字符串常量，jar 中无 ANTE/NTE 硬类引用。

### 临时诊断
`RailDiag` 每 5 秒检查一次计数，**只有计数变化时才打一行**
`[mtr-headlight] rail bake: calls=N hits=N rebuilds=N skipped=N`：稳态（没东西重建）完全静音。
`skipped` 是被脏检查拦下来的重建请求，`skipped` 涨得快、`rebuilds` 不涨就是修好了。

`AnteMixinPlugin` 在类加载时每份 mixin 打一行 `ante mixin … -> …`，只出现在启动阶段。

---

## 2026-11 光影下轨道光照：确定性结论与 B2 兜底

### 1. 现象收敛

用户装了 **三个光影包**（MakeUp-UltraFast-9.3c、Bloop-1.7.0、CyanideShaders-v1.1，Iris 1.7.6）
逐一测试，**全部**看不到轨道被车灯照亮。这不是某个包的写法问题。

### 2. Iris 侧：UV2 的投递是"有条件"的

`iris-1.7.6` 反编译，`net/irisshaders/iris/pipeline/transform/transformer/VanillaTransformer.java`：

```java
if (parameters.inputs.hasLight()) {
    root.replaceReferenceExpressions(t, "gl_MultiTexCoord1", "vec4(iris_UV2, 0.0, 1.0)");
    tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "in ivec2 iris_UV2;");
} else {
    root.replaceReferenceExpressions(t, "gl_MultiTexCoord1", "vec4(240.0, 240.0, 0.0, 1.0)");
}
```

两条分支都不是我们想要的结果：

- `hasLight()` 由 **这次 draw 的 VertexFormat** 决定（`ShaderAttributeInputs` 按属性语义探测），
  而不是由 shader 里写了什么决定 → 对 ANTE 光影模式下那种"自建 VAO + 包程序"的 draw，
  属性是否被程序读到不在包的控制范围内；
- 不匹配时统一注入常量 `240`：语义是"全亮"，而实测是"全暗" ⇒ **实际发生的是属性没被读到**，
  常量分支也没生效（否则轨道会惨白）。

### 3. 包侧：三家的实体光照模型都会把 block=15 画得很亮

**MakeUp UltraFast 9.3c**（`shaders/gbuffers_entities.fsh` → `common/solid_blocks_fragment.glsl`，
光照在 gbuffers 顶点阶段内联算完；`deferred` 只做 AO/云，`composite` 只做雾/体积光，不重算实体光照）：

```glsl
// src/light_vertex.glsl
candle_color = CANDLE_BASELIGHT * (pow(illumination.x, 1.5) + sixth_pow(illumination.x * 1.17));
...
#if defined CAVEENTITY_V                      // gbuffers_entities 定义了这个宏
    candle_cave_strength = mix((direct_light_strength * .5) + .5, 1.0, visible_sky);
    candle_color *= candle_cave_strength;
#endif
omni_light = mix(omni_color_min, omni_color, visible_sky);   // 无天空 ⇒ 塌到 AVOID_DARK_LEVEL=0.03
```

block=15（`illumination.x ≈ 0.97`）时 `candle ≈ 0.7`，是肉眼可见的量级。

**Bloop 1.7.0**：

```glsl
// program/gbuffers/solid/gbuffers_med.vsh   —— 按 0..240 的字节值直接读，证明 UV2 本来就是这个量纲
const float lightmapMin = 8.0;
const float lightmapMax = 255.0;
vec2 lmNorm = clamp((gl_MultiTexCoord1.xy - lightmapMin) / (lightmapMax - lightmapMin), 0.0, 1.0);

// program/lighting.fsh（deferred 光照阶段）
lightmap.x = lightmap.x * lightmap.x * lightmap.x;
vec3 torchLighting = CalculateTorch(lightmap, normal, fragpos, ao);

// lib/light.glsl
vec3 torch = vec3(3.000, 1.3, 0.42);
float lightFactor = lm * lm * lm + lm * 0.025;
torch = clamp(torch * lightFactor, 0.0, 4.0) * 1.5;   // lm=1 → (4.5, 1.95, 0.63)
```

block=15 时这条式子会给出强烈的暖光。实测没有 ⇒ 这个值没到光源阶段。

**CyanideShaders 1.1**：整包**没有 `gbuffers_entities`**（只有 `gbuffers_textured/clouds/skytextured` +
`final`），实体回落到 `program/overworld/gbuffer/textured.glsl`：

```glsl
vec3 light = (0.85 - blindness) * texture2D(lightmap, coord1).rgb;   // coord1 = 顶点 lightmap UV
vec4 col   = color * vec4(light, 1.0) * texture2D(texture, coord0);
```

u=240/255 在 vanilla lightmap 里就是火把亮色，block=15 同样应该是亮的。

**三家光照模型完全不同、三家都"该亮"，却三家都不亮 ⇒ 结论只能是：烘焙进顶点的 lightmap
根本没有被包的程序读到。** 这一层在 ANTE 光影模式的绘制路径（`RenderLayer.startDrawing()` +
包程序 + ANTE 自建 VAO）与 Iris 的属性绑定之间，**不修改 ANTE/Iris 的 jar 无法修复**
（改包 shader 也只是换一种同样读不到的方式）。这就是 B1 被否掉的依据。

### 4. 兜底 B2：光影下改用自绘光带（已实现）

配置（`config/mtr-headlight.json`，旧配置会自动迁移到新默认值）：

| 键 | 默认 | 说明 |
|---|---|---|
| `railGlowUnderShaders` | **true** | 光影激活且 `railLight` 开时，自绘光带接管；无光影仍走烘焙光 |
| `railGlowWidth` | **1.2** | 旧默认 0.85 太窄，被轨道模型夹住 |
| `railGlowAlpha` | **0.12** | ≤0.15，加性混合，低 alpha 不出塑料感；上限 0.20 |
| `railGlowLift` | **0.07** | 抬到轨面以上（硬下限 0.06），不再被轨道模型压住 |
| `railGlowAhead` | 14.0 | 只画车头前方这一段（光影下不再画车身下方） |
| `railGlowStep` | 1.5 | 沿曲线采样步长 |

实现要点：

- `RailGlow.render(context, shadersPass)`：**同一帧只会走其中一条**——
  `underShaderFallback(cfg) == true` 时由 `AFTER_TRANSLUCENT` 渲染（`shadersPass=true`），
  否则只有手动开 `railGlow` 才在 `AFTER_ENTITIES` 渲染。两者互斥 ⇒ 不会双重叠加。
- 用 `WorldRenderEvents.AFTER_TRANSLUCENT` 而不是 `LAST`：Fabric 的 `LAST` 注入在
  `WorldRenderer.render` 的 `renderChunkDebugInfo` 之前，仍在 Iris 的 gbuffer 阶段内；
  `AFTER_TRANSLUCENT` 是"最后一个仍会被 Iris 合成/泛光/色调映射处理"的点。
- 顶点使用**相机相对坐标**（世界坐标 − `camera.getPos()`），矩阵取 `context.matrixStack()`
  的 pose；matrixStack 为 null 时整帧跳过（宁可不画，也不画到世界原点）。
- 光影下把光谱衰减改成 `fade²`（鼻端最亮、向前平滑消失），不做"车身下方一条亮带"。
- `RailRelightScheduler`：`glowFallback` 成立时**不再排重建**（`pending` 清空后直接 return），
  也不再在切换光影时调 `clearAll()`——反正烘焙结果没人看得见，省掉 4~10 Hz 的 32×32 网格重建。

### 5. 只能实机确认的部分

1. 三个包下光带是否可见、位置是否贴合（`railGlowLift` / `railGlowWidth` 可现场微调）。
2. **deferred 重算光照的包（Bloop）**可能把落在 gbuffer 里的这条带按环境光压暗；
   MakeUp（光照已在 gbuffers 内联算完）与 Cyanide（无光照阶段）不受影响。
   若 Bloop 下偏暗，先把 `railGlowAlpha` 提到 0.15，再考虑换包。
3. 光影切换瞬间是否有一帧跳变（`clearAll` 已被跳过，理论上没有）。

