# 1.19.2 → 1.18.2 功能移植报告

**结果**：`gradle -p v1.18.2 clean build` → **BUILD SUCCESSFUL**；产物
`v1.18.2/build/libs/lte-1.0.0.jar`（**2 067 217 B / 273 个 class**，与 1.19.2 的
2 067 368 B / 273 个 class 逐项对齐，只差 151 字节的类名/常量池差异）。
v1.18.2 侧源码 **115 文件 / 32 668 行**，资源 **380 文件**，与 v1.19.2 **文件集完全相同**
（`diff` 两个 `find | sort` 列表为空）。

共享 `common/` 只动了 **1 行**（见 §6）。

---

## 1. 搬了什么

| 类别 | 做法 |
|---|---|
| Java 源码 | `v1.19.2/src/main/java` **整树**复制到 `v1.18.2/src/main/java`（115 文件），再按 §3 逐项适配。旧 1.18.2 的 19 个 0.2.2 时代文件全部被新版覆盖（新版的同名文件都是它的超集）；删掉 `SidingDetailScreen.java`（1.19.2 已删的孤儿类）。 |
| 资源 | `v1.19.2/src/main/resources` 整树复制（**380 文件**：blockstates / models / textures / lang / particles / shaders / 7 个 mixin 配置 / `builtin_lights.json` / icon）。 |
| `fabric.mod.json` | 用 1.19.2 那份（`environment: "*"`、`main` 两端入口、7 个 mixin 配置、`lambdynlights` 在 `recommends`），只改两处：`minecraft: ~1.18.2`、`lambdynlights: >=2.1.0`（1.18.2 线只有 LDL 2.1）。 |
| `build.gradle` | MTR 换 **named jar**（`libs/MTR-fabric-1.18.2-3.2.2-hotfix-1-named.jar`）；加 `-AMSG_MIXIN_SOFT_TARGET_NOT_FOUND=disabled`（天津地铁 `ziyue.tjmetro` 目标只在运行期存在）。modmenu 3.2.5 / LDL 2.1.0 / NTE 0.3.3 / ANTE 1.1.0 原样沿用。 |

## 2. MTR named jar：1.18.2 单独产一份

`tools/remap-mtr-named.sh libs/MTR-fabric-1.18.2-3.2.2-hotfix-1.jar
libs/MTR-fabric-1.18.2-3.2.2-hotfix-1-named.jar 1.18.2` → 62 507 215 B 的 named jar
（脚本早就参数化好了 `1.18.2 / YARN 1.18.2+build.4 / YARN_DIR 1_18_2`，无需改动）。

**为什么必须重产**：未 remap 的 jar 里继承来的方法还是 `method_3563` 这类 intermediary 名，
`extends mtr.block.BlockPSDAPGDoorBase` / `@Override render(...)` 一个都解析不了。
重映射后 `javap -p` 确认（本次逐条核对，全部命中）：

```
mtr.block.BlockPlatform#getActualState(BlockView,BlockPos,BlockState)        private
mtr.render.RenderPIDS#getSchedules(T,BlockPos,Map<Long,String>)              public
  + RailwayData.getStation(Set<Station>,DataCache,BlockPos)  static           public
mtr.render.RenderPSDAPGDoor#render(BlockEntity,float,MatrixStack,VCP,int,int) public
mtr.render.RenderSignalBase#getNodePos(BlockView,BlockPos,Direction)         private static
mtr.render.RenderSignalBase#getOccupiedAspect(BlockPos,float)                private
mtr.client.ClientCache#getResource(String,Supplier<NativeImage>,..)          private
mtr.client.ClientCache#getRouteMap / #getDirectionArrow                      public
mtr.client.RouteMapGenerator#generateRouteMap/generateColorStrip/generateDirectionArrow  public static
mtr.client.TrainClientRegistry#reset()V / #register(String,TrainProperties)  public static
mtr.data.SavedRailBase#isCloseToSavedRail(BlockPos,int,int,int)              public
mtr.data.RailwayData#simulateTrains()V                                       public
mtr.data.TrainServer#simulateTrain(World,float,Depot,DataCache,…)Z           public
```

MTR 侧代码与 1.19.2 **逐字节同一份**（同版本 3.2.2-hotfix-1，只是 MC 类型换名），所以这一层
没有第二种适配。

## 3. 1.18.2 特有的卡点（全表）

按「编译器先报 → 我改了 → 验证方式」列。凡是 1.19.2 已经解决过的（`Registries` / `SimpleOption` /
`itemgroup.v1` / `DamageType` / `InputSupplier` / `LimbAnimator` / `Tooltip` / `RotationAxis` /
`setPlaceholder` / `getPlayerFacing` / `isReplaceable`），搬过来的 1.19.2 代码里**已经是对的**，
1.18.2 不需要再改一遍 —— 这也是为什么最终只剩 13 个编译错误。

| # | 1.19.2 的写法 | 1.18.2 的现实（javap 证据） | 本次处理 | 命中 |
|---|---|---|---|---|
| 1 | `Text.literal(..)` / `Text.translatable(..)` / `Text.empty()` | `net.minecraft.text.Text` 只有 `of(String)`；`literal/translatable/empty` 是 1.19 才加进接口的 | `new LiteralText(..)` / `new TranslatableText(..)` / `LiteralText.EMPTY`，脚本统一改写（`temp/textify.js`）并自动补 import | **145 处 / 22 文件** |
| 2 | `text.getContent() instanceof TranslatableTextContent` | 1.18.2 没有 `TextContent`/`TranslatableTextContent` 这层拆分 | `text instanceof TranslatableText translatable` → `translatable.getKey()` | `TrainNames` |
| 3 | `fabric-client-command-api-v2`（`ClientCommandRegistrationCallback`） | 1.18.2 只有 v1：`ClientCommandManager.DISPATCHER`，**没有注册回调**，`FabricClientCommandSource#sendFeedback(Text)` 单参 | 直接 `DISPATCHER.register(..)`；import 换 `client.command.v1` | `Commands`（607 行，23 处 sendFeedback） |
| 4 | `fabric-command-api-v2`（handler 三参 `dispatcher, registryAccess, environment`） | v1 的 handler 是 `(dispatcher, dedicated)` | lambda 改两参 | `LteBindCommand` |
| 5 | `BufferRenderer.drawWithShader(buf.end())` / `drawWithGlobalProgram(...)` | **1.18.2 没有 `BuiltBuffer`**：`BufferBuilder#end()` 返回 `void`，`BufferRenderer` 只收 `draw(BufferBuilder)` | `buf.end(); BufferRenderer.draw(buf);` | `clientgui/DrawContext`×2、`GlowCompat`×1 |
| 6 | `this.clearAndInit()` | 1.18.2 `Screen` 没有 `clearAndInit()`（1.19.4 才加）；`init(MinecraftClient,int,int)` 是 `public final` 且自己 `clearChildren()` | `this.init(this.client, this.width, this.height)` | **7 处 / 6 屏幕** |
| 7 | `DrawableHelper.enableScissor/disableScissor` | 1.18.2 的 `DrawableHelper` **完全没有 scissor** | 直接 `RenderSystem.enableScissor`，按 `getScaleFactor()` + **`getFramebufferHeight()`** 自己换算（与 1.19.4 实现逐行等价，含 `Math.max(0,..)` 夹取） | `clientgui/DrawContext` |
| 8 | `ResourceManager#getResource(id)` → `Optional<Resource>` | 1.18.2 返回 `Resource` 本体（找不到抛 `IOException`） | `containsResource(id)` 先探 + 直接取 `Resource` | `TrainNames`、`TrainPreview` |
| 9 | `options.getViewDistance().getValue()` | 1.18.2 `GameOptions#getViewDistance()` 直接返回 `int` | 收进 `McCompat.viewDistanceChunks()` | `McCompat`、`ConnectorNodeHighlight` |
| 10 | `options.getParticles().getValue()` | 1.18.2 是 `public ParticlesMode particles` **普通字段**（没有 `getParticles()`） | 收进 `McCompat.particlesMode()` | `RainSpray` |

**核对过但不需要改**（1.18.2 与 1.19.2 同形，javap 双向确认）：`MatrixStack.Entry#getPositionMatrix()`、
`VertexConsumer#vertex(Matrix4f,…)`、`VertexFormat.DrawMode`、`GameRenderer#getPositionColorShader/
getPositionTexColorShader`、`DrawableHelper#fill(MatrixStack,…)`（public static）、
`TextRenderer#drawWithShadow` 三重载、`Screen#renderTooltip(MatrixStack,Text,int,int)`（public）、
`ClickableWidget` 的 `x/y` 字段与 protected `setFocused`、`Immediate#draw(RenderLayer)`、
`KeyBinding` 构造器、`Registry.ITEM/BLOCK/BLOCK_ENTITY_TYPE/PARTICLE_TYPE`、
`FabricItemGroupBuilder`（`net.fabricmc.fabric.api.client.itemgroup`）、`FabricParticleTypes`、
`ParticleManager#getDebugString()`、`ResourcePack#openRoot(String)`、`DamageSource(String)`、
`LivingEntity#limbDistance` 字段、`net.minecraft.util.registry.Registry`。

## 4. mixin 目标逐条核对（不启动游戏，javap + refmap）

**MC 侧（`remap=true`，refmap 里已生成 intermediary 名）**

| mixin | 1.18.2 目标 | 结论 |
|---|---|---|
| `beamlight/BeamLightMixin` | `WorldRenderer#getLightmapCoordinates(BlockRenderView,BlockState,BlockPos)I` | ✅ 存在（1.18.2 是 **public static**，1.19.2 是 private static；`@Inject` 不受影响），refmap → `class_761;method_23793` |
| `rain/WorldRendererRainMixin` | `renderWeather(LightmapTextureManager,FDDD)V`（private） | ✅ 存在，refmap → `method_22714` |
| `rain/…` 第二条 | `tickRainSplashing(Camera)V`（1.18.2 是 public） | ✅ 存在，refmap → `method_22713` |
| `collision/MixinEntityMove` | `Entity#move(MovementType,Vec3d)` | ✅ 存在，`method_5784` |
| `ante`/`nte` 亮度 mixin | `World#getLightLevel(LightType,BlockPos)I` | ✅ 存在，`method_8314` |

**MTR 侧（`remap=false`，按 named jar 核对）**：见 §2 的清单，还额外确认了三条
`@At(INVOKE)` 的真实调用点在 1.18.2 的 MTR 字节码里都在 ——
`RailwayData.getStation`（PIDS 站台归属）、`RenderPSDAPGDoor.render` 里的 `String.format`（×7，厦门贴图换名）、
`ClientCache.getResource` 里的 `CompletableFuture.supplyAsync`（线路图作用域）。

**7 个 mixin 配置的类名 / 9 个入口点 / `lte.refmap.json`** 全部在 jar 内 ——
`bash temp/mixincheck-1182.sh` → **无 MISSING**。

## 5. 功能面：与 1.19.2 完全一致，没有新增降级

- **车灯 / 光带**：`BeamGlow` / `BeamGeom` / `BeamBlockLight` / `BeamRelight` / `BeamLightMixin` —— 全量。
- **光柱**：`ProtoBeamCone` 加法壳层光柱 —— 全量。
- **水雾**：`RainSpray` / `SprayParticle` / `SprayType`（自注册 `mtrlte:spray`）+ `WorldRendererRainMixin` —— 全量。
- **碰撞**：`CollisionSystem` / `CollisionImpact` / `MobCollision` / `CarGeometry` / `ServerCollision` /
  `MixinEntityMove` —— 全量。
- **连接器**：`SignalLinkItem` / `SignalLinkStore` / `SignalLinkNet` / `SignalLinkClient` /
  `ProjectorConnectorItem` / `PidsConnectorItem` / `ConnectorNodeHighlight` / `/ltebind` —— 全量。
- **界面**：`ConfigScreen` / `ParameterScreen`（1 447 行）/ `SidingScreen` / `SidingMapScreen` /
  `ColorPickerScreen` / `ResetConfirmScreen` / `CategoryScreen` / `BeamListScreen` / `BeamEditScreen` /
  `BeamVehicleScreen` / `BeamEditor3DScreen`（1 854 行）/ `PackImportScreen` / `TrainPreview` —— 全量，
  走 `clientgui/{DrawContext,Matrix4f,Vector3f,Quaternionf,Widgets}` 垫片，**没有降级**（1.18.2 的
  `MatrixStack`/`Quaternion` 接口与 1.19.2 逐字节相同，JOML 垫片原样复用）。
- **厦门方块**：`XiamenPsdBlocks` / `PsdAPGItem` / `RenderPsdDoor` / `RenderPsdApgDoor` /
  `XiamenClient` / 玻璃 / 端头 / 灯 / 线路图换名 —— **全部编入**（靠 §2 的 named jar）。
- **服务端**：`LteServer` / `ServerCollision` / `ServerTrackbed` / 握手 / 三个 `mtrserver` mixin —— 全量。

沿用 1.19.2 的既有降级（**不是本次新增**）：

| 项 | 1.18.2 表现 |
|---|---|
| 体积光锥 `ConeVolumeRenderer` | 仍是 stub（`ready()` 恒 false），`ProtoBeamCone` 退回加法壳层光柱 |
| 方速（fangsu） | 3 个 mixin + `FangsuTrafficLightMode` 不带；`FangsuSync` 恒「非方速」 |
| 动态光源 | LDL 2.1 的 `Ldl2Backend`（点光近似）；没装 LDL 时 `NoopBackend` 兜底 |

**1.18.2 额外没有新增任何「编不过所以不编」的缺口** —— 115 个源文件一个不少。

## 6. 共享 `common/` 的唯一改动

`common/DebugProbe.java:530`（我就是被这个 2 错之一卡住的）：

```diff
-            player.sendMessage(Text.literal(message), false);
+            player.sendMessage(Text.of(message), false);
```

`Text.of(String)` 在 1.18.2 / 1.19.2 / 1.19.4 / 1.20.1 四份 merged jar 里**都存在且返回 `Text`**
（已逐份 `javap` 确认），所以这一行对另外三个版本无影响；`common/` 其余 55 个文件未改。

## 7. 验收抽查

```
unzip -l v1.18.2/build/libs/lte-1.0.0.jar | grep -c '\.class$'   -> 273
BeamGlow / ProtoBeamCone / BeamGeom / RainSpray / SprayParticle / SprayType
collision.CollisionSystem / CollisionImpact / TrainImpactDamageSource / signal.SignalLinkItem / SignalLinkStore
xiamen.XiamenPsdBlocks(+Door/Glass/GlassEnd/ApgDoor/ApgEnd/Top BE) / xiamen.PsdAPGItem / RenderPsdDoor / RenderPsdApgDoor
BeamEditor3DScreen / BeamEditScreen / BeamListScreen / BeamVehicleScreen / CategoryScreen /
PackImportScreen / TrainPreview / ParameterScreen / ConfigScreen / CustomPack / BeamPackWriter / TrainNames
clientgui.DrawContext / Matrix4f / Quaternionf / Vector3f / Widgets
mixin.collision.MixinEntityMove / mixin.beamlight.BeamLightMixin / mixin.mtr.* / mixin.rain.* / mixin.mtrserver.*
```

- `gradle -p v1.18.2 clean build` → **BUILD SUCCESSFUL in 14s**（8 tasks）。
- `bash temp/mixincheck-1182.sh` → 7 个配置无 MISSING；9 个入口点齐全；`lte.refmap.json` 在 jar 内。
- 与 1.19.2 相比 java 差异 **28 个文件**（22 个是 §3-1 的文本工厂改写，6 个是真正的 API 适配：
  `Commands` / `LteBindCommand` / `TrainNames` / `TrainPreview` / `RainSpray` / `ConnectorNodeHighlight` /
  `McCompat` / `GlowCompat` / `clientgui.DrawContext`）。

## 8. 边界

- **没有** 修改 `v1.19.2` / `v1.19.4` / `v1.20.1` 的任何文件（纯只读参考），没有提交 git。
- 新增 `libs/MTR-fabric-1.18.2-3.2.2-hotfix-1-named.jar`（`tools/remap-mtr-named.sh` 产出）。
- 新增 `temp/mixincheck-1182.sh`（1.18.2 版自检脚本）；一次性改写脚本放在仓库外的
  `D:/MC-MTR-JS/temp/textify.js`（没有入库）。
- 没有启动游戏：所有 mixin 目标与 MTR 方法签名都是 `javap` 静态核对的。
