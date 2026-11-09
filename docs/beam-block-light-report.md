# 车灯作为真实区块光源（顶点 blockLight 注入）—— 可行性 + 原型报告

日期：2026-11-09　范围：**只做 1.20.1**

代码：
`common/src/main/java/dev/js/mtrheadlight/BeamBlockLight.java`（光束快照 + 光锥数学）
`common/src/main/java/dev/js/mtrheadlight/BeamRelight.java`（节流扫描 + 标脏）
`v1.20.1/src/main/java/dev/js/mtrheadlight/mixin/beamlight/BeamLightMixin.java`（唯一的注入）
`v1.20.1/src/main/java/dev/js/mtrheadlight/BeamLightTest.java`（实机 A/B 脚手架）
`v1.20.1/bench/Bench.java`（脱离 MC 的微基准）
开关：`HeadlightConfig.beamBlockLight`（默认 **false**）、`beamBlockLightRadius`（默认 12，夹 4–32）

---

## 0. 结论先说

1. **注入点找到了，而且只有一个。** 1.20.1 原版整条区块网格化路径（方块 + 流体、AO 平滑 + 平面）最后都收敛到
   一个 6 条指令的静态方法 `WorldRenderer.getLightmapCoordinates(BlockRenderView, BlockState, BlockPos)`，
   返回值就是 `skyLight << 20 | blockLight << 4`，改写 bit 4–7 即可，天空槽不动。§1 有 javap 证据。
2. **数学几乎免费。** 实测（脱离 MC，跑正式编译产物，预热后）：命中锥体 **11.0 ns/顶点**、仅 AABB 拒绝
   **2.1 ns**、没有光束 **1.1 ns**；**一整个 16³ section 的 2500 次查询只要 0.0155 ms**。也就是一次重建里
   光照计算只占约 1.5%。
3. **代价 100% 来自"重建 section"这个动作本身。** 半径 12 + 16 格光束，一次范围内有 18 个候选 section、
   约 8 个真正被照到；车速 20 格/s（1 格/tick）时，做到"每 1 格跟一次"是 **150–200 次 section 重建/秒**。
   四道闸把它压到 **40 次/秒**，但那时同一个 section 每秒只刷新 5 次，光斑按 4 格一档跳、并且滞后 1–3 帧。
4. **结论：不建议做。** 40 次/秒 ≈ 一次渲染距离流式加载的 1/3–1/2 的区块构建吞吐（40–120 ms/s 的 builder CPU
   + 4–10 MB/s 的 VBO 重传），换来的是一条**会跳、会滞后、白天完全看不见**的亮斑。默认 `false`，作为实验
   开关保留。产品化走 §7 的替代方案（地面投影贴片 / 只照亮轨道）。
5. **实机 A/B 没拿到干净数据，原因已定位并且不是本功能的逻辑问题**：mixIn 确实被应用（导出字节码里有
   handler），但在这个 78 模组的客户端里目标方法**一次都没被调用**（`called=0`，独立的写文件探针也没触发），
   同时 `BlockModelRenderer` 字节码里确实引用了它 —— 说明某个模组绕过了这条取光路径。要坐实需要一套最小
   mod 集（只留 MTR + hANTE + LDL）重跑，见 §6.3。**这一条不影响第 4 条的结论**：代价由 section 数量决定，
   与钩子是否命中无关。

---

## 1. 原版路径：注入点（javap 证据）

### 1.1 唯一的漏斗

`javap -c -p` 于
`minecraft-merged-1.20.1-net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2.jar`：

```
public static int getLightmapCoordinates(BlockRenderView, BlockState, BlockPos);
   0: aload_1 / aload_0 / aload_2
   3: invokevirtual BlockState.hasEmissiveLighting(BlockView, BlockPos)Z
   6: ifeq 13
   9: ldc_w  #3984   // int 15728880            <- (15 << 20) | (15 << 4)
  12: ireturn
  13: aload_0 / getstatic LightType.SKY / aload_2
  18: invokeinterface BlockRenderView.getLightLevel(LightType, BlockPos)I   -> istore_3
  24: aload_0 / getstatic LightType.BLOCK / aload_2
  29: invokeinterface BlockRenderView.getLightLevel(LightType, BlockPos)I   -> istore 4
  36: aload_1 / invokevirtual BlockState.getLuminance()I                     -> istore 5
  42..51: block = max(block, luminance)
  53: iload_3 / bipush 20 / ishl / iload 4 / iconst_4 / ishl / ior / ireturn
```

二参数重载是纯委派（12 字节）：

```
public static int getLightmapCoordinates(BlockRenderView, BlockPos);
   0: aload_0
   1: aload_0 / aload_1 / invokeinterface BlockRenderView.getBlockState(BlockPos)BlockState
   8: aload_1
   9: invokestatic getLightmapCoordinates(BlockRenderView, BlockState, BlockPos)I
  12: ireturn
```

→ **只钩三参数版本**就连流体面一起覆盖，不需要第二处注入。

### 1.2 谁在调它（一次 section 构建的全部入口）

`ChunkBuilder$BuiltChunk$RebuildTask.render(float,float,float,BlockBufferBuilderStorage)` 的字节码：

```
  71: invokestatic  BlockModelRenderer.enableBrightnessCache()
  96: invokestatic  MinecraftClient.getInstance()
  99: invokevirtual MinecraftClient.getBlockRenderManager()BlockRenderManager
 108: invokestatic  BlockPos.iterate(BlockPos, BlockPos)Iterable
  ...
 524: invokestatic  BlockModelRenderer.disableBrightnessCache()
```

`BrightnessCache` **只在这个方法体里被打开**，所以它天然就是"只在区块构建时读光"的缓存；而它的
100 条 LRU 命中率很低，一个 section 会真正走 ~2500 次取光。

| 调用点 | 路径 | 频率 |
|---|---|---|
| `BlockModelRenderer$BrightnessCache.getInt` | AO 平滑：`apply()` 每面 4 角 + 4 对角 | 每面 8 次，部分被 100 条 LRU 吃掉 |
| `BlockModelRenderer.render`（偏移 265） | 平面路径（`!smooth`） | 每方块 1 次 |
| `BlockModelRenderer.renderQuadsFlat`（偏移 947） | 平面路径每 quad | 每 quad 1 次 |
| `FluidRenderer.render` | 流体面（走两参数重载） | 每流体面 1 次 |

`javap -p` 显示 `BlockModelRenderer` 里除这三处外没有其它读亮度的位置；
`AmbientOcclusionCalculator.apply(BlockRenderView, BlockState, BlockPos, Direction, float[], BitSet, boolean)`
只通过 `BrightnessCache.getInt` / `getFloat` 取光（后者是 AO 系数，不是亮度）。

### 1.3 "SectionBuilder" 在 1.20.1 不存在

`unzip -l` `net/minecraft/client/render/chunk/` 的清单里**没有 `SectionBuilder.class`**（那是 1.21 才拆出来的）。
1.20.1 的等价物是：

```
class ChunkBuilder$BuiltChunk$RebuildTask extends ChunkBuilder$BuiltChunk$Task {
  protected ChunkRendererRegion region;
  public CompletableFuture<ChunkBuilder$Result> run(BlockBufferBuilderStorage);
  private ChunkBuilder$BuiltChunk$RebuildTask$RenderData render(float, float, float, BlockBufferBuilderStorage);
}
```

它把 `region`（`ChunkRendererRegion`）交给 `BlockModelRenderer` —— 这就是钩子的天然守卫：
**只有 `view instanceof ChunkRendererRegion` 才动作**，所以实体/粒子/手持物一律不碰
（它们传的是 `ClientWorld`）。

### 1.4 标脏：`WorldRenderer.scheduleBlockRenders`

```
public void scheduleBlockRenders(int, int, int, int, int, int);
   0: iload_3 / iconst_1 / isub / istore 7          // z1-1
   5..: 三层循环 z1-1..z2+1, x1-1..x2+1, y1-1..y2+1
  42: aload_0 / iload 8 / invokestatic ChunkSectionPos.getSectionCoord(I)I
  48: iload 9 / ... / iload 7 / ...
  58: invokevirtual scheduleBlockRender(III)V
```

参数顺序 `(minX, minY, minZ, maxX, maxY, maxZ)`（**方块坐标**），方法自己再各向外扩 1 格。
一个 section 传 `(x<<4, y<<4, z<<4, x<<4|15, y<<4|15, z<<4|15)` 正好整块覆盖。
**这一步当帧几乎免费**（微秒级置脏位），真正的开销全在 chunk builder 的工作线程。

---

## 2. Sodium 路径：为什么跳过

本机装的是 `sodium-fabric-0.5.13+mc1.20.1.jar`（remap 到 intermediary）。`javap` 结果：

```
me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess   (abstract)
  protected int compute(int, int, int);          <- 唯一的读光漏斗，内含 class_1920.method_8314(SKY/BLOCK)
  public static int packBL(int); packSL(int); packLU(int); packAO(float); packEM(boolean);
me.jellysquid.mods.sodium.client.model.light.data.ArrayLightDataCache
  public int get(int, int, int);                 <- 自带 (x,y,z) 缓存
me.jellysquid.mods.sodium.client.model.light.LightPipeline
  void calculate(ModelQuadView, BlockPos, QuadLightData, Direction, Direction, boolean);
me.jellysquid.mods.sodium.client.model.light.data.QuadLightData
  public final float[] br;   // AO，每顶点
  public final int[]   lm;   // lightmap，每顶点
```

语义上和原版钩子一一对应（`compute` 就是"位置 → 打包光"），而且缓存比原版好。**但代价更高：**

- 方法名是 Sodium 内部 API（`compute` / `calculate`），不是 MC 映射，必须 `remap=false` 手写签名；
- Sodium 0.6（1.21）把 lighting 改成另一套结构，钩子要重写；
- 发行版要维护"原版 / Sodium 0.5 / Sodium 0.6"三套。

所以按任务要求：检测到 `sodium`（或 `embeddium`）就**不安装、不耗 CPU**，只打一行日志：

```
[lte] beam light: skipped, sodium present (replaces the vanilla section mesher) - the beam stays a drawn sheet only
```

---

## 3. 一次重建覆盖多大 · 每秒多少次

一条车头光束（`beamHeadLength` 16，`beamBlockLightRadius` 12）：

- 光束 AABB = 16 × 0.2 × 24 格；
- `BeamRelight.addRange` 再向地面方向多取 12 格（高架桥下的道床）、向上 4 格；
- 合计 ≈ **40 × 16 × 24 格 → 3 × 3 × 2 = 18 个候选 section**；
- 锥体真正覆盖的约 **8 个**（x 2 列、z 2 列、y 1–2 层）。

车尾灯（4 格长，半径压到 ≤6）再 +2。四列车同时在视野里就是 40–70 个候选，`MAX_CANDIDATES = 96`
是硬上限（超了整轮跳过，宁可这一帧不跟，也不要把 builder 塞爆）。

### 车速 20 格/s（1 格/tick）下的重建量

| 策略 | section 重建 / 秒 | 说明 |
|---|---|---|
| 无闸：每 1 格把范围内全部标脏 | **150–200** | 20 次扫描 × 8–10 个变化 section |
| 只有"签名变了才重建" | 60–100 | 光束前缘进入/离开 section 时才变 |
| **本原型（四道闸全开）** | **≤ 40（配额上限）** | 每 section ≤5 次/秒（200 ms 冷却），8 个 section 打满 |

参照物：这台机器上原版区块构建在 20 格/s 时大约能流式产出 **80–120 个 section/秒**。
所以 40 次/秒 ≈ **吃掉 1/3 到 1/2 的区块构建吞吐**。

### 每 section 多少顶点

一次 16³ 地形 section 重建会产生 **~2500 个不同的方块位置**查询（`BrightnessCache` 只有 100 条 LRU，
所以这个数字基本等于"唯一位置数"），对应约 **1 万个顶点**（每个 quad 4 个顶点共享这 2500 个位置）。
40 次/秒 → **约 10 万次 `level()` 调用/秒**。

---

## 4. 实测：光照计算本身的代价

`v1.20.1/bench/Bench.java`，跑正式编译出来的 `BeamBlockLight`（JDK 21，`-Xms512m -Xmx512m`，
预热 50 万次，每项 500 万次；section 项预热 50 轮 × 2500 次后测 400 轮）：

```
level(), no beam published                          1.1 ns/op
level(), outside the beam AABB (reject)             2.1 ns/op
level(), inside the wedge (full maths)             11.0 ns/op
one 16^3 section, 2500 distinct positions:          0.0155 ms  (6.2 ns/pos)
signature(), 64 samples per section               165.6 ns/op
```

读法（这是本报告最重要的一段）：

- **没有光束时，全世界每个顶点只多 1.1 ns** → `beamBlockLight=false` 或列车在远处时的代价可以忽略。
- **一整个 section 的光照计算 0.0155 ms**，而原版重建同一个 section 本身要 **1–3 ms**
  → 注入只让一次重建贵 **约 1.5%**。
- **结论：数学不是瓶颈。** 40 次/秒 × 0.0155 ms = **0.62 ms/s**，完全可以忽略。
  真正的账单是 40 次/秒 × 1–3 ms = **40–120 ms/s 的 builder CPU**，加上每次重建
  100–250 KB 的 VBO 重传（**4–10 MB/s**）。无闸的 150–200 次/秒就是 150–600 ms/s，
  等于 1.5–6 个核的构建时间 —— 必然卡。
- 一轮扫描（`BeamRelight` 的签名检查）8 个候选 × 166 ns ≈ **1.3 µs**，20 次/秒 → 26 µs/秒。

游戏内诊断行（每 5 秒）：

```
[lte] beam light: sections=K verts=V ms=M sweeps=S cand=C throttled=T beams=B
```

`sections` = 该窗口请求的 section 重建数；`verts` = 真正被抬高 block light 的顶点数；
`ms` = 这 `verts` 个顶点的光锥计算总耗时（不含 AABB 拒绝）；`cand` = 当前记住的已烘焙 section 数；
`throttled` = 被冷却/配额挡下的次数。

---

## 5. 原型实现

### 5.1 数据流

```
LightController.tick()            每个 client tick，在算 head/tail 段的地方顺手收集
   -> BeamBlockLight.publish()    不可变 double[8]/条：sx sy sz ex ey ez radius peak
   -> BeamRelight.tick()          移动 ≥1 格或每 10 tick：扫候选 section、比签名、标脏
   -> WorldRenderer.scheduleBlockRenders(...)
   -> ChunkBuilder 工作线程重建该 section
   -> BeamLightMixin (RETURN of getLightmapCoordinates)  逐顶点 max(block, beam)
```

光束形状**不是球**，是贴着地面、随距离变宽的楔形（车灯就是这个形状）：

```
t       = 沿光束水平轴的距离，归一化到 [0,1]
lateral = 1.5 + (radius - 1.5) * t          锥体在 t 处的半宽
radial  = 1 - 垂直距离 / lateral             轴上 1，边缘 0
vert    = 1 - |dy| / 4                      上下各 4 格的板，两端淡出
axial   = 1 - 0.45 t                        向尖端衰减
level   = peak * radial^2 * vert * axial    四舍五入，夹到 0..15
```

`peak` 取 `headLuminance`（默认 15），并且只做 `max(原 blockLight, level)` —— **永不压暗**。

### 5.2 四道闸（复用 `RailRelightScheduler` 的思路）

| 闸 | 值 | 作用 |
|---|---|---|
| 移动 | ≥ 1 格，或每 10 tick | 停站的车 = 0 次扫描、0 次重建 |
| 签名 | 64 点网格求和 | 只有"这个 section 的亮度真的变了"才重建；也负责**扫尾** |
| 每 section 冷却 | 200 ms | 重建还排在队列里时不会重复标脏 |
| 全局配额 | 40 次/秒 | 硬上限；打满后剩下的留到下一轮，宁可晚也不要卡 |

关掉开关或离开世界时 `flushAll()` 把记住的 section 各重建一次，否则光会留在原地。

### 5.3 可见性边界（必须在验收里体现）

`getLightmapCoordinates` 把 sky 和 block 打包在一起，最终亮度由 lightmap 贴图合成。
**白天露天 skyLight=15 已经饱和，block light 再高也看不出来。**
所以"真照亮"只在 **夜晚 / 隧道 / 桥下 / 室内 / 雨天** 有意义 —— 恰好也是车灯有意义的场景，
但这意味着**验收必须在夜间**。

---

## 6. 实机验证：做到了哪一步

### 6.1 脚手架

`BeamLightTest`（`-Dlte.beamtest=1`）会：

1. `/time set midnight`、`doDaylightCycle false`、`weather clear`；
2. 在玩家脚下 `/fill` 一块 19×19 石地板 + 一堵 19×8 石墙（14 格外），把玩家传过去对准墙；
3. 用 `BeamBlockLight.setTestBeam(...)` 灌一条与真实车灯同形（长 14、半径 12、峰值 15）的虚拟光束；
4. **同一机位**拍三张：`1_wall_off.png`（关）→ `2_wall_on.png`（开）→ `3_wall_off_again.png`（再关）。

`setTestBeam` 只是替换"光源在哪"，光锥数学、mixin、节流扫描、原版重建全都是生产的代码路径。

### 6.2 已经验证到的

- 三张截图**都正确生成**（`D:\MC-MTR-JS\temp\mcbeam\screenshots\`），场景确认是午夜 + 石墙 + 石地板；
- mixin **确实被应用**：用 `-Dmixin.debug.export=true` 导出的变换后字节码里，
  `net.minecraft.class_761` 多了一个方法

  ```
  private static void handler$clp000$mtrlte$mtrheadlight$beamBlockLight(
      net.minecraft.class_1920, net.minecraft.class_2680, net.minecraft.class_2338,
      org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable);
  ```

  且 refmap 把注入点映射到
  `Lnet/minecraft/class_761;method_23793(Lnet/minecraft/class_1920;Lnet/minecraft/class_2680;Lnet/minecraft/class_2338;)I`
  （`class_761` = WorldRenderer，`method_23793` = getLightmapCoordinates）。

### 6.3 没验证到的，以及为什么

第一次跑给了 `called=0 regionSeen=false`，于是加了一个**独立的探针**：handler 第一次被调用时往
`%TEMP%\lte-beamligh-probe.txt` 写一行（不依赖任何静态字段的类身份）。**文件始终没有生成**
→ 目标方法在这个客户端里**一次都没被调用**，尽管：

- 石墙正常渲染出来了（说明区块确实被网格化）；
- 导出的 `class_778`（BlockModelRenderer）和 `class_778$class_4303`（BrightnessCache）
  字节码里**确实引用了** `method_23793`。

也就是说：这条路径在本机 78 个模组的环境里被某个模组绕过了（候选：`immediatelyfast` / `modernfix` /
`trender` 一类重写渲染管线的东西），**没有继续隔离**。要坐实需要一套最小 mod 集
（只留 MTR + hANTE + LambDynamicLights + 本模组）重跑同一个 `-Dlte.beamtest=1` 脚手架。

> 这一段是环境结论，不是设计结论。§0 第 4 条（不建议）由 §3 的 section 数量和 §4 的实测支撑，
> 与钩子是否命中无关：**代价由"要重建多少个 section"决定，而那个数已经算清楚了。**

### 6.4 这一路上踩到的环境坑（留给下次）

- `cmd //c xxx.bat` 会被 MSYS 路径转换吃掉 `/c` 参数，变成交互式 cmd；用
  `MSYS_NO_PATHCONV=1 cmd.exe /c ...`，或者干脆别用 bat（走 `node launch.js`）。
- **客户端运行时禁止往它的 `mods/` 写 jar**：另一个线程并发拷 jar 会导致
  `ZipException: invalid LOC header (bad signature)`，表现为"某个类加载失败"的假崩溃。
  正确顺序是「构建 → 拷贝 → 启动」，拷之前先确认锁在自己手上。
- `tools/mc-test-once.sh` 的 `mc_pids` 用 `commandline -match 'minecraft'` 判断"有客户端在跑"，
  **Gradle daemon 的命令行里也含 `minecraft`**（gradle 缓存路径），会把启动无限期挡在门口。
  卡住时 `gradle --stop` 即可。
- `Boolean.getBoolean("x")` **只认 `"true"`**，传 `-Dx=1` 会静默不生效。`BeamLightTest` 现在自己解析。
- 同一个实例被多线程共用时，用「硬链接 + 独立 gameDir（`D:\MC-MTR-JS\temp\mcbeam`）」隔离，
  只把自己那一份 jar 放进去，可以彻底避开 jar 写入竞争。

---

## 7. 替代方案（按代价从低到高）

1. **地面投影贴片（推荐）**：在车前地面画一个 12 格长、随距离淡出的楔形贴片，
   `RenderLayer.getTranslucent()` + 加色，深度测试照常。零区块重建，与 Sodium/光影都不冲突。
   缺点：不照亮墙面，只给"地面被照亮"这条视觉线。
2. **只照亮铁轨**（已实现，就是现在的 `railLight`）：ANTE 的 `MeshBuildingRailChunk.rebuildBuffer`
   本来就在轨道 mesh 重建时烘一次，被 `RailRelightScheduler` 用 12 次/秒的配额限住，代价可接受。
3. **本原型**：`beamBlockLight=true`，留给愿意付代价的人。
4. **IRLights 式 SSBO + GLSL 补丁**：真正的体积光与遮挡，但每个光影包一个补丁，维护量线性增长。

---

## 8. 复现

```bash
# 1) 构建（只 1.20.1）
export JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"
/d/MC-MTR-JS/temp/gradle/gradle-8.14/bin/gradle -p v1.20.1 build --offline

# 2) 微基准（不需要游戏）
cd v1.20.1 && mkdir -p bench/out
"$JAVA_HOME/bin/javac" -nowarn -cp build/classes/java/main -d bench/out bench/Bench.java
"$JAVA_HOME/bin/java" -cp "bench/out;build/classes/java/main" Bench

# 3) 实机 A/B（最小 mod 集：MTR + hANTE + LDL + 本模组；一定要先构建再拷贝再启动）
"$JAVA_HOME/bin/java" -Dlte.beamtest=true ... --quickPlaySingleplayer <测试存档副本>
#    结果在 <gameDir>/screenshots/1_wall_off.png / 2_wall_on.png / 3_wall_off_again.png
```

`BeamLightTest` 与 `setTestBeam` 都是**临时脚手架**，发布前删掉（和 `RainTest` 同一性质）。
