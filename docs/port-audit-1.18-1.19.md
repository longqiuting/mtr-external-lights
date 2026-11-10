# LTE 移植清单：1.18.2 / 1.19.2 / 1.19.4

只读调研。没有改动任何源码、没有构建、没有启动游戏。所有结论来自对本仓库源码的静态扫描，以及对 `libs/` 下四个 MTR jar、Gradle 缓存里四份 Minecraft merged jar、四份 fabric-api 重映射产物的 `javap` 对比。

> 行数统计口径：**工作区当前状态**（含未提交改动，例如 `xiamen/XiamenStripPainter.java` 正在进行的换乘站标记绘制）。`common/` 42 个文件 + `v1.20.1/` 116 个文件 = 158 个文件 / 46 228 行，与 `find … -exec cat {} + | wc -l` 一致。

---

## 0. 基线事实（先读这一段）

| 项 | 1.18.2 | 1.19.2 | 1.19.4 | 1.20.1 |
|---|---|---|---|---|
| MTR（`build.gradle`） | `MTR-fabric-1.18.2-3.2.2-hotfix-1.jar` | `MTR-fabric-1.19.2-3.2.2-hotfix-1.jar` | `MTR-fabric-1.19.4-3.2.2-hotfix-1.jar` | `MTR-fabric-1.20.1-3.6.4.2-named.jar` |
| MTR 版本 | **3.2.2-hotfix-1** | **3.2.2-hotfix-1** | **3.2.2-hotfix-1** | **3.6.4.2** |
| fabric-api | 0.77.0+1.18.2 | 0.77.0+1.19.2 | 0.87.2+1.19.4 | 0.92.12+1.20.1 |
| LambDynamicLights | 2.1.0+1.17 | 2.2.0+1.19.2 | 2.3.1+1.19.4 | 4.4.0（behaviour API） |
| fabric.mod.json `environment` | `client` | `client` | `client` | `*`（含 `main` 入口） |
| NTE / ANTE | NTE 0.3.3 + ANTE 1.1.0 | 同左 | 同左 | ANTE 1.1.0 |
| 源码文件数 / 行数 | 19 / 5 424 | 19 / 5 420 | 19 / 5 420 | 116 / 32 618 |

1.20.1 的完整功能面 = `common/` 42 个文件（13 610 行）+ `v1.20.1/` 116 个文件（32 618 行）= **158 个文件 / 46 228 行**。

三个旧版本各自只有 19 个文件，是 0.2.2 时代的骨架：屏幕外壳（`ConfigScreen`/`ParameterScreen`/`SidingScreen`/`SidingMapScreen`/`ColorPickerScreen`/`ResetConfirmScreen`）、`Ldl2Backend`、`LightBackends`、`GlowCompat`、`KeyHooks`、`McCompat`、`ConfigWriteTarget`、`SidingInfo`、`SidingDetailScreen`、`mixin/nte/NteBrightness*`。**这三个版本已经证明了一件事：MatrixStack 版屏幕外壳、LDL 2.x 后端、逐版本 `GlowCompat` 都是能跑通的**，移植不是从零开始。

### 0.1 三条会改变计划的结论

1. **三个旧版本的 MTR API 完全一致。** 同一版本 3.2.2-hotfix-1，只是按各自的 MC 版本重映射。对 43 个我们用到的类逐类 `javap` 后，`diff 118.txt 1192.txt` 与 `diff 118.txt 1194.txt` **均为空**——连 MC 类型都还是同一套 intermediary 名。所以「旧版本之间的 MTR 差异」不存在，真正的差异只有两处：**MTR 3.2.2 → 3.6.4.2**，以及**原版 MC 1.18.2 → 1.20.1**。

2. **`BeamBlockLight` / `BeamRelight` / `ConeVolumeBeam` 不依赖 1.20.1 独有 API。** 任务书里把它们当作 D 类候选，实测不成立：
   - `WorldRenderer.getLightmapCoordinates(BlockRenderView, BlockState, BlockPos)I` 在四份 merged jar 里**都存在且签名相同**（`BeamLightMixin` 的唯一注入点）；
   - `WorldRenderer.scheduleBlockRenders(int,int,int,int,int,int)` 在四份里**都存在且签名相同**（`BeamRelight.mark` 的唯一调度调用）；
   - `ConeVolumeBeam` 连 import 都只有 `Vec3d`。
   三个类都是 A 类。真正 1.20.1 独有、且无法下沉的是 **`ConeVolumeRenderer` 的实现**（`ShaderProgram`/`GlUniform` 的类型名）和 **`Ldl4Backend`**（LDL 4.x 的 behaviour API 只有 1.20.1 有）。

3. **真正的硬阻塞是构建基础设施，不是代码：MTR 3.2.2 的 jar 是 intermediary 名，不能拿来 `extends`。** `tools/remap-mtr-named.sh` 现在只认 1.20.1（`MAP` 路径写死 `1.20.1/.../mappings.tiny`）。厦门门那几个类（`XiamenPsdBlocks extends BlockPSDAPGDoorBase`、`RenderPsdDoor extends RenderPSDAPGDoor`）在旧版本上**必然编译失败**，除非先给每个版本生成一份 named 重映射的 MTR 3.2.2 jar。证据：3.2.2 的 `mtr.render.RenderPSDAPGDoor` javap 出来是
   ```
   public boolean shouldRenderOffScreen(T);
   public boolean method_3563(MC);          <-- rendersOutsideBoundingBox 还是 intermediary 名
   public void method_3569(MC, float, MC, MC, int, int);
   ```
   而 3.6.4.2 的同一类是 `rendersOutsideBoundingBox(BlockEntity)`。子类 `@Override` 根本无法解析。

---

## 1. 文件级差异归类

四类定义：

- **A 直接复用** —— 没有版本敏感的 API，原样放进 `common/` 即可。
- **B 小改适配** —— 逻辑不动，但碰到了跨版本会变的 API，必须逐版本核对；改动点已逐条列出。**其中一部分核对结论是「一致、零改动」**（例如 `Item.useOnBlock` 的 `ActionResult` 返回、Identifier 频道联网、`PersistentState`+NBT 存储、`NativeImage`/`TextureManager`），这些仍留在 B 是因为它们所在的 **Fabric 模块版本**在四个版本间不同（networking-api-v1 1.0.24→1.3.15、resource-loader-v0 0.5.3→0.11.12），一旦编译器报错就知道去哪儿看——不是「预计要改」。
- **C 需重写** —— 渲染管线、屏幕外壳、mixin 绑定等版本敏感面，需要按版本另写一份实现。
- **D 仅 1.20.1** —— 依赖 1.20.1 独有生态（LDL 4.x、方速 1.20.1 字节码），旧版本不做。

统计（行数取 1.20.1/common 现状）：

| 归类 | 文件数 | 行数 | 占比 |
|---|---|---|---|
| A 直接复用 | 74 | 21 861 | 47.3 % |
| B 小改适配 | 54 | 13 341 | 28.9 % |
| C 需重写 | 22 | 10 146 | 22.0 % |
| D 仅 1.20.1 | 8 | 880 | 1.9 % |
| 合计 | 158 | 46 228 | 100 % |

| 文件 | 行数 | 归类 | 理由 / 卡点（具体类名·方法名） |
|---|---|---|---|
| `common/BeamBlockLight.java` | 297 | A | no imports at all; pure block-light math over int[] offsets |
| `common/BeamGeom.java` | 88 | A | ClientWorld + Vec3d only; geometry tables |
| `common/BeamGlow.java` | 631 | A | ClientData/TrainClient + WorldRenderContext; WorldRenderContext members are identical in rendering-v1 1.11.0 … 3.0.10 (only projectionMatrix() differs, unused here) |
| `common/ClearDiag.java` | 57 | A | no imports |
| `common/ConeVolumeBeam.java` | 48 | A | Vec3d only |
| `common/EndLight.java` | 50 | A | Vec3d only |
| `common/HeadlightConfig.java` | 2336 | A | FabricLoader only; Gson config model |
| `common/LteLog.java` | 20 | A | no imports |
| `common/LightBackend.java` | 50 | A | interface over Vec3d |
| `common/ParticleCull.java` | 178 | A | Identifier only |
| `common/RailDiag.java` | 202 | A | no imports |
| `common/RailDynamicLight.java` | 187 | A | BlockPos only |
| `common/RailLightInject.java` | 122 | A | BlockPos/LightType/World; all four MC jars expose the same World.getLightLevel signature |
| `common/RailLightState.java` | 68 | A | BlockPos only |
| `common/RailMixinFlags.java` | 70 | A | no imports |
| `common/RailRelightDispatcher.java` | 123 | A | no imports; counter/dispatch table |
| `common/RailRelightHooks.java` | 37 | A | no imports |
| `common/ShaderPackProbe.java` | 141 | A | no imports |
| `common/VehicleLightJson.java` | 429 | A | no imports; Gson schema |
| `common/VehicleLightOverrides.java` | 1062 | A | no imports; beam model + JSON |
| `common/TrainPose.java` | 431 | A | mtr.data.Train/mtr.path.PathData + Vec3d; PathData.rail/startingPos/dwellTime identical in 3.2.2 (only 3.6.x adds adcTime/stopWithoutOpeningDoors, unused here) |
| `common/BuiltinLights.java` | 265 | A | TrainClient only; hardcoded lamp table |
| `common/DepartureState.java` | 382 | A | ClientData/TrainClient/MinecraftClient |
| `common/HeadlightSwitch.java` | 87 | A | ClientWorld only |
| `common/LightController.java` | 543 | A | ClientData/TrainClient/TransportMode/Vec3d |
| `common/VehicleLights.java` | 1124 | A | DynamicTrainModel.partsInfo/properties, JonModelTrainRenderer.model (public final, same type in 3.2.2 and 3.6.4.2), ModelTrainBase |
| `common/RailChunkBuildBoost.java` | 214 | A | HostProbe only |
| `common/RailWeather.java` | 404 | A | ClientWorld/Heightmap/LightType; no changed member |
| `common/DebugProbe.java` | 540 | A | ClientData/TrainClient/ParticleTypes/Text |
| `common/RailRelightScheduler.java` | 1028 | A | ClientData.RAILS/TrainClient/BlockPos; ClientData.RAILS is the same field in 3.2.2 |
| `common/MtrHeadlightClient.java` | 87 | A | ClientModInitializer + ClientTickEvents (identical in lifecycle-events-v1 2.1.1 … 2.2.x) |
| `common/ModMenuIntegration.java` | 12 | A | ModMenuIntegration entrypoint; modmenu 3.2.5/4.2.0/6.3.1 all expose ConfigScreenFactory |
| `common/BeamRelight.java` | 433 | A | WorldRenderer.scheduleBlockRenders(int x6) exists verbatim in 1.18.2/1.19.2/1.19.4/1.20.1 (javap on all four merged jars) |
| `common/mixin/HostProbe.java` | 116 | A | no MC imports; Class.forName probes |
| `common/mixin/MixinScan.java` | 54 | A | no imports |
| `common/mixin/AnteMixinPlugin.java` | 65 | A | RailMixinFlags only |
| `common/mixin/NteMixinPlugin.java` | 56 | A | RailMixinFlags only |
| `common/mixin/ante/AnteBrightnessMixin.java` | 42 | A | RailLightInject + World.getLightLevel(BlockPos, LightType) |
| `common/mixin/ante/AnteInstancedBrightnessMixin.java` | 37 | A | same as above |
| `common/mixin/ante/AnteRailChunkBuildMixin.java` | 117 | A | targets ANTE rail chunk by string; RailChunkBuildBoost only |
| `common/mixin/nte/NteRailChunkBuildMixin.java` | 89 | A | targets NTE rail chunk by string; RailChunkBuildBoost only |
| `v1.20.1/ConfigWriteTarget.java` | 1322 | A | "no Minecraft imports on purpose" (own javadoc); 639-line diff vs the 1.18.2 copy is new parameter rows, not API |
| `v1.20.1/SidingInfo.java` | 18 | A | mtr.data.Siding record wrapper |
| `v1.20.1/server/ServerConfig.java` | 42 | A | HeadlightConfig only |
| `v1.20.1/net/ServerAuthority.java` | 108 | A | LteLog only; permission/UUID table |
| `v1.20.1/net/LteChannel.java` | 98 | A | Identifier + PacketByteBuf channel ids |
| `v1.20.1/BeamSnap.java` | 136 | A | Beam only; geometry snap |
| `v1.20.1/signal/SignalSourceCapture.java` | 33 | A | BlockPos/Direction only |
| `v1.20.1/signal/SignalLinks.java` | 141 | A | BlockPos/BlockView/World only |
| `v1.20.1/bind/LteMtrLookup.java` | 475 | A | DataCache/RailwayData/Station/Platform; all identical in 3.2.2 |
| `v1.20.1/bind/PidsStationIndex.java` | 186 | A | ClientData/DataCache/Platform/Station |
| `v1.20.1/server/MtrServerData.java` | 116 | A | RailwayData + World |
| `v1.20.1/mtr/BuiltInTrains.java` | 532 | A | TrainClientRegistry.register(String, TrainProperties), ClientData.SIDINGS |
| `v1.20.1/BeamSession.java` | 586 | A | DynamicTrainModel/TrainClientRegistry/TrainProperties/Siding |
| `v1.20.1/CustomPack.java` | 778 | A | NativeImage/NativeImageBackedTexture(copy NativeImage)/TextureManager.registerTexture(Identifier, AbstractTexture) identical in all four |
| `v1.20.1/RainHush.java` | 364 | A | ClientData/TrainClient/Box/Vec3d |
| `v1.20.1/VehicleLightPacks.java` | 135 | A | ResourceManager/WorldSavePath |
| `v1.20.1/xiamen/IXiamenTextureNamespace.java` | 26 | A | no imports; marker interface |
| `v1.20.1/xiamen/PsdQuads.java` | 172 | A | VertexConsumer + MatrixStack quad emitters; MatrixStack is the same class in all four |
| `v1.20.1/xiamen/XiamenStripPainter.java` | 479 | A | no imports; pixel painter |
| `v1.20.1/xiamen/XiamenTextures.java` | 255 | A | MinecraftClient/ResourceManager/Identifier |
| `v1.20.1/xiamen/XiamenDoorLights.java` | 117 | A | ClientCache/ClientData/RailwayData/Route |
| `v1.20.1/xiamen/XiamenRouteStyle.java` | 222 | A | ClientData/Route |
| `v1.20.1/xiamen/XiamenLogoBlock.java` | 51 | A | AbstractBlock/Block only; the registry call lives in XiamenObjects |
| `v1.20.1/signal/FangsuSignalSync.java` | 257 | A | BlockNode/ClientData/Rail/PathData + BlockEntity/World; no com.fangsu name |
| `v1.20.1/McCompat.java` | 18 | B | SimpleOption only exists from 1.19.2: 1.18.2 must keep options.getViewDistance() (int), 1.19.2+ needs .getValue() |
| `v1.20.1/KeyHooks.java` | 43 | B | 两个 `KeyBinding` 构造器 `(String,int,String)` 与 `(String,InputUtil.Type,int,String)` 在四份 MC jar 里**都存在**；旧版文件用三参形式、1.20.1 用四参，二者在任一版本都能编译。`KeyBindingHelper.registerKeyBinding` 四个版本一致——核对项而非改动项 |
| `v1.20.1/TrainNames.java` | 237 | B | `net.minecraft.text.TranslatableTextContent` 只在 1.19.2+ 存在；1.18.2 只有 `net.minecraft.text.TranslatableText`（两者互斥，已逐版本 javap 确认）。按内容取 key 的那段要分叉 |
| `v1.20.1/SprayType.java` | 143 | B | Registries.ROOT/Registries.PARTICLE_TYPE (1.19.4+) vs net.minecraft.util.registry.Registry.PARTICLE_TYPE (1.18.2/1.19.2); FabricParticleTypes exists in all four |
| `v1.20.1/TabIcons.java` | 70 | B | Registries.ITEM -> net.minecraft.util.registry.Registry.ITEM on 1.18.2/1.19.2 |
| `v1.20.1/signal/LteCoreItems.java` | 138 | B | FabricItemGroup/ItemGroupEvents (item-group-api-v1) ship only from 1.19.4; 1.18.2/1.19.2 must use fabric-item-groups-v0 ItemGroupBuilder + ItemGroupEvents not available |
| `v1.20.1/xiamen/XiamenObjects.java` | 337 | B | same item-group split as LteCoreItems; RegistryKeys.BLOCK etc. -> net.minecraft.util.registry.Registry on 1.18.2/1.19.2; FabricBlockEntityTypeBuilder present in all four |
| `v1.20.1/bind/DisplayPushTicker.java` | 132 | B | Registries.BLOCK_ENTITY_TYPE vs 1.18.2/1.19.2 net.minecraft.util.registry.Registry.BLOCK_ENTITY_TYPE |
| `v1.20.1/collision/CollisionImpact.java` | 1284 | B | DamageType + RegistryEntry<DamageType> are 1.19.4+; 1.18.2/1.19.2 must build a DamageSource directly (no DamageType registry) |
| `v1.20.1/collision/TrainImpactDamageSource.java` | 71 | B | same DamageType/RegistryEntry -> DamageSource fallback; DamageSource#setBypassesArmor differs pre-1.19.4 |
| `v1.20.1/collision/CollisionSystem.java` | 1752 | B | net.minecraft.entity.LimbAnimator is 1.19.4+; 1.18.2/1.19.2 read entity.limbAnimator as Entity's own LimbAnimator class (moved package in 1.19.4) |
| `v1.20.1/signal/ConnectorNodeHighlight.java` | 389 | B | SimpleOption.getValue() on getViewDistance() -> int on 1.18.2 (McCompat already wraps this); VertexConsumerProvider.Immediate.draw(RenderLayer) identical |
| `v1.20.1/signal/FangsuSignalCompat.java` | 122 | B | Registries.BLOCK -> util.registry.Registry.BLOCK on 1.18.2/1.19.2 |
| `v1.20.1/BeamPackWriter.java` | 1026 | B | net.minecraft.resource.InputSupplier is 1.19.4+; 1.18.2/1.19.2 ResourcePack profile needs Supplier<InputStream> |
| `v1.20.1/Commands.java` | 607 | B | fabric-client-command-api-v2 (ClientCommandManager/ClientCommandRegistrationCallback/FabricClientCommandSource) is absent in 1.18.2 - needs client-command-api-v1 on that version |
| `v1.20.1/bind/LteBindCommand.java` | 146 | B | fabric-command-api-v2 CommandRegistrationCallback is absent in 1.18.2 - needs command-api-v1 |
| `v1.20.1/LightBackends.java` | 94 | B | must branch Ldl4Backend (1.20.1) vs Ldl2Backend (1.18-1.19.4); the old trio already ships the Ldl2 branch |
| `v1.20.1/RenderHooks.java` | 112 | B | 1.20.1 body registers SprayType/DebugProbe/RainSpray/ProtoBeamCone; the old trio's copy stops at the beam sheet. Merge, no API break |
| `v1.20.1/signal/SignalLinkItem.java` | 419 | B | mtr.block.BlockSignalLightBase/BlockSignalSemaphoreBase/BlockNode + NBT only; Item.useOnBlock returns ActionResult in all four |
| `v1.20.1/signal/SignalLinkNet.java` | 256 | B | ServerPlayNetworking.play(ServerPlayerEntity, Identifier, PacketByteBuf) - the Identifier-channel API, identical in networking-api-v1 1.0.24 … 1.3.x (payload API is 1.20.5+) |
| `v1.20.1/net/ClientHandshake.java` | 117 | B | ClientPlayNetworking.registerGlobalReceiver(Identifier, PlayChannelHandler) 4-arg handler identical in all four |
| `v1.20.1/net/ServerHandshake.java` | 92 | B | ServerPlayConnectionEvents.JOIN + ServerPlayNetworking, identical |
| `v1.20.1/server/LteServer.java` | 145 | B | ModInitializer + ServerTickEvents.END_SERVER_TICK + ServerPlayConnectionEvents.JOIN; all present in 1.18.2 |
| `v1.20.1/server/ServerCollision.java` | 523 | B | Entity/ServerWorld/Vec3d only; no version-sensitive member |
| `v1.20.1/server/ServerTrackbed.java` | 267 | B | FallingBlockEntity/ItemEntity/Box/Vec3d only |
| `v1.20.1/bind/BindTargets.java` | 161 | B | Block/BlockEntity/BlockPos/Direction/Vec3d |
| `v1.20.1/bind/MtrTiles.java` | 316 | B | BlockEntityUpdateS2CPacket + ServerWorld; identical in all four |
| `v1.20.1/bind/PidsConnectorItem.java` | 315 | B | Item.useOnBlock(ItemUsageContext) ActionResult + NbtCompound |
| `v1.20.1/bind/ProjectorConnectorItem.java` | 394 | B | same as PidsConnectorItem |
| `v1.20.1/bind/PidsLinkStore.java` | 109 | B | PersistentState + NbtCompound; WorldSavePath unchanged |
| `v1.20.1/bind/ProjectorLinkStore.java` | 110 | B | same PersistentState pattern |
| `v1.20.1/bind/PidsScheduleScope.java` | 195 | B | MinecraftServer/ServerWorld/BlockPos |
| `v1.20.1/bind/BeamEditorItem.java` | 68 | B | TypedActionResult<ItemStack> use(...) still exists in 1.20.1 (deprecated) so no signature change |
| `v1.20.1/bind/LteDashboardItem.java` | 71 | B | same as BeamEditorItem |
| `v1.20.1/mtr/BuiltInTrainsInit.java` | 30 | B | ClientModInitializer + ClientTickEvents |
| `v1.20.1/BeamPackScope.java` | 395 | B | ResourcePack/ResourceType; ResourcePackProfile ctor differs 1.19.4+ (needs InputSupplier path) |
| `v1.20.1/RainSpray.java` | 923 | B | `net.minecraft.client.option.ParticlesMode`、`ParticleTypes`、`ParticleEffect` 四份 jar 里都在（已 javap 确认）；粒子注册入口 `SprayType.init()` 由 `RenderHooks` 在 1.20.1 才挂，旧版要确认注册时机——核对项 |
| `v1.20.1/SprayParticle.java` | 339 | B | `SpriteBillboardParticle`/`SpriteProvider`/`ParticleTextureSheet` 四份都在（已 javap 确认）；`ParticleTextureSheet.TERRAIN_SHEET` 名称未变——核对项 |
| `v1.20.1/VehicleLightsJsonClient.java` | 93 | B | `SimpleResourceReloadListener`（fabric-resource-loader-v0 0.5.3→0.11.12）+ `ClientLifecycleEvents` 四个版本都在；`reload(...)` 五个参数的形状需逐版本核对——核对项 |
| `v1.20.1/VehicleLightsJsonServer.java` | 48 | B | ServerLifecycleEvents + FabricLoader |
| `v1.20.1/mixin/mtr/TrainClientRegistryMixin.java` | 106 | B | targets mtr.client.TrainClientRegistry.reset()V and register(String, TrainProperties) - both present in 3.2.2 (javap verified) |
| `v1.20.1/mixin/mtr/RenderSignalBaseMixin.java` | 175 | B | targets private static getNodePos(BlockView, BlockPos, Direction) - present as private static in 3.2.2 too (javap -p) |
| `v1.20.1/mixin/mtr/RenderPIDSStationMixin.java` | 177 | B | @Redirect RenderPIDS.getSchedules - present in 3.2.2 with the same name (BlockPos vs class_2338 in descriptor, but method= is name-only) |
| `v1.20.1/mixin/mtr/RenderPsdDoorTextureMixin.java` | 56 | B | @Redirect RenderPSDAPGDoor.render - present in 3.2.2 |
| `v1.20.1/mixin/mtr/RouteMapArtMixin.java` | 66 | B | RouteMapGenerator.generateRouteMap/generateColorStrip both present in 3.2.2 and both return NativeImage |
| `v1.20.1/mixin/mtrserver/RailwayDataCaptureMixin.java` | 35 | B | RailwayData.simulateTrains()V present in 3.2.2 |
| `v1.20.1/mixin/mtrserver/TrainSimulateCaptureMixin.java` | 47 | B | TrainServer.simulateTrain(...) present in 3.2.2 (same param shape) |
| `v1.20.1/mixin/mtrserver/PidsScheduleScopeMixin.java` | 48 | B | SavedRailBase.isCloseToSavedRail(BlockPos,int,int,int) present in 3.2.2 |
| `v1.20.1/mixin/mtr/MtrMixinPlugin.java` | 68 | B | string probe of mtr.render.RenderSignalBase; no API |
| `v1.20.1/mixin/mtrserver/MtrServerMixinPlugin.java` | 61 | B | string probe; no API |
| `v1.20.1/mixin/collision/MixinEntityMove.java` | 50 | B | Entity.move(MovementType, Vec3d) descriptor identical in all four |
| `v1.20.1/ConfigScreen.java` | 243 | C | extends Screen, render(DrawContext,...) is 1.20.1-only; 1.18-1.19.4 need render(MatrixStack,int,int,float) and drawTextWithShadow on MatrixStack |
| `v1.20.1/ParameterScreen.java` | 1447 | C | same DrawContext rewrite; 1447 lines of widget layout + Tooltip (Tooltip is 1.19.4+) |
| `v1.20.1/SidingScreen.java` | 737 | C | DrawContext + ClickableWidget.render; the old trio already has a MatrixStack twin to start from |
| `v1.20.1/SidingMapScreen.java` | 955 | C | DrawContext + MatrixStack transforms in the map view |
| `v1.20.1/ColorPickerScreen.java` | 310 | C | DrawContext + TextFieldWidget/ButtonWidget render |
| `v1.20.1/ResetConfirmScreen.java` | 90 | C | DrawContext + OrderedText wrapping (Screen.wrapLines) |
| `v1.20.1/CategoryScreen.java` | 234 | C | DrawContext + ButtonWidget |
| `v1.20.1/BeamListScreen.java` | 383 | C | DrawContext + ClickableWidget |
| `v1.20.1/BeamEditScreen.java` | 602 | C | DrawContext + SliderWidget + TextFieldWidget |
| `v1.20.1/BeamVehicleScreen.java` | 390 | C | DrawContext + TextFieldWidget |
| `v1.20.1/BeamEditor3DScreen.java` | 1854 | C | 1854 lines; DrawContext + TrainClientRegistry + live 3D preview; the heaviest single screen |
| `v1.20.1/PackImportScreen.java` | 220 | C | DrawContext + TextFieldWidget |
| `v1.20.1/TrainPreview.java` | 934 | C | DrawContext + RotationAxis (1.19.4+) + VertexConsumer model draw; 1.18/1.19.2 need Quaternion/AxisAngle4f equivalents |
| `v1.20.1/ConeVolumeRenderer.java` | 650 | C | ShaderProgram/GlUniform names are 1.19.4+; on 1.18.2/1.19.2 the same calls exist as net.minecraft.client.render.Shader#getUniform(String)->GlUniform and CoreShaderRegistrationCallback$RegistrationContext#register(Identifier,VertexFormat,Consumer<Shader>). Algorithm (depth blit, ray-march uniforms) is reusable; ~12 call sites change type. Old trio ships a 31-line stub that always answers ready()=false |
| `v1.20.1/GlowCompat.java` | 53 | C | BufferRenderer.draw(BufferBuilder) 1.18.2 / drawWithShader(BuiltBuffer) 1.19.2 / drawWithGlobalProgram(BuiltBuffer) 1.19.4+; net.minecraft.util.math.Matrix4f vs org.joml.Matrix4f; GameRenderer.getPositionColorShader vs *Program. Already solved 3x in the old trio |
| `v1.20.1/xiamen/XiamenPsdBlocks.java` | 347 | C | extends mtr.block.BlockPSDAPGDoorBase/BlockPSDTop/BlockAPGGlass/BlockPSDAPGBase - requires a yarn-named MTR jar per MC version (tools/remap-mtr-named.sh only knows 1.20.1) |
| `v1.20.1/xiamen/PsdAPGItem.java` | 154 | C | extends mtr.block.BlockPSDAPGDoorBase + mtr.block.IBlock; same named-remap prerequisite |
| `v1.20.1/xiamen/RenderPsdDoor.java` | 221 | C | extends mtr.render.RenderPSDAPGDoor; in 3.2.2 the override rendersOutsideBoundingBox is still intermediary method_3563, so the named remap is mandatory |
| `v1.20.1/xiamen/RenderPsdApgDoor.java` | 55 | C | extends mtr.render.RenderPSDAPGDoor; same as above |
| `v1.20.1/xiamen/XiamenClient.java` | 102 | C | BlockEntityRendererRegistry.register + RenderAPGGlass/RenderPSDTop ctors take BlockEntityRenderDispatcher (same in 3.2.2); BlockRenderLayerMap identical |
| `v1.20.1/mixin/beamlight/BeamLightMixin.java` | 111 | C | target WorldRenderer.getLightmapCoordinates(BlockRenderView,BlockState,BlockPos)I exists in all four, but it is static+private and the injector is require=1; needs re-verification per version and a mixin config file per version |
| `v1.20.1/mixin/rain/WorldRendererRainMixin.java` | 54 | C | renderWeather(LightmapTextureManager,FDDD)V and tickRainSplashing(Camera)V are private in all four but renderWeather's internals changed (BufferBuilder/Tessellator), so require=0 + re-check per version |
| `v1.20.1/Ldl4Backend.java` | 134 | D | dev.lambdaurora.lambdynlights.api.behavior.{DynamicLightBehaviorManager,LineLightBehavior} exist only in LDL 4.x, which is 1.20.1-only; old versions use Ldl2Backend |
| `v1.20.1/MtrHeadlightDynamicLights.java` | 21 | D | lambdynlights:initializer entrypoint - LDL 4.x only |
| `v1.20.1/FangsuCompat.java` | 110 | D | mod-id probe is harmless, but the whole class documents com.fangsu.* targets; keep only the probe on old versions |
| `v1.20.1/FangsuTurnoutLight.java` | 98 | D | feeds @ModifyArgs on com.fangsu...TurnoutBlockEntityRenderer#render; that class only exists in the fangsu build for 1.20.1 |
| `v1.20.1/mixin/FangsuMixinPlugin.java` | 118 | D | Class.forName("com.fangsu....") gate for the fangsu mixins |
| `v1.20.1/mixin/fangsu/FangsuTurnoutRenderMixin.java` | 80 | D | @ModifyArgs on TurnoutBlockEntityRenderer#render arg 4; string target, require=0 |
| `v1.20.1/mixin/fangsu/FangsuTrafficLightRenderMixin.java` | 53 | D | targets BaseObjBlock/BlockEntityTrafficLight.whenRendering; fangsu 1.20.1 bytecode |
| `v1.20.1/signal/FangsuTrafficLightMode.java` | 266 | D | walks com.fangsu EnumConfig dispType via reflection; 1.20.1 addon only |
| `common/ProtoBeamCone.java` | 1288 | A | DynamicTrainModel + WorldRenderContext + BlockState/MatrixStack; beam shell geometry only, no changed member |
| `v1.20.1/BeamEditorOpener.java` | 27 | A | MinecraftClient.setScreen only |
| `v1.20.1/collision/CarGeometry.java` | 497 | A | Entity/Box/Vec3d/ServerPlayerEntity |
| `v1.20.1/collision/MobCollision.java` | 286 | A | Entity/LivingEntity/ServerWorld/Vec3d + TrainPose |
| `v1.20.1/collision/RailBed.java` | 360 | A | mtr.data.Rail/RailType + BlockPos/Vec3d; both present in 3.2.2 |
| `v1.20.1/collision/Trackbed.java` | 77 | A | ClientData/ClientWorld |
| `v1.20.1/collision/TrainTickCapture.java` | 80 | A | mtr.data.Train + World |
| `v1.20.1/ConfigCategory.java` | 54 | A | MutableText/Text |
| `v1.20.1/xiamen/XiamenRouteStrip.java` | 221 | A | NativeImage.read/new NativeImage(int,int,boolean)/NativeImageBackedTexture(NativeImage) identical in all four |
| `v1.20.1/collision/CollisionBootstrap.java` | 85 | B | entrypoint wiring: ClientModInitializer + ClientTickEvents + ServerTickEvents; both present in 1.18.2 |
| `v1.20.1/signal/SignalLinkClient.java` | 83 | B | ClientPlayNetworking.registerGlobalReceiver(Identifier, handler) - Identifier-channel networking, identical in networking-api-v1 1.0.24 … 1.3.15 |
| `v1.20.1/signal/SignalLinkStore.java` | 177 | B | PersistentState + NbtCompound/NbtList; WorldSavePath unchanged |

### 1.1 归类后的几点说明

- **`common/` 本身就是 A 类的存在性证明。** 四个 `build.gradle` 的 `sourceSets.main.java.srcDirs` 都写着 `['../common/src/main/java', 'src/main/java']`——那 42 个文件今天就在 1.18.2/1.19.2/1.19.4 上编译通过。所以「把 A 类从 `v1.20.1/` 挪进 `common/`」不是乐观假设，是把已经被三个版本验证过的做法扩大范围。
- **diff 行数不能当移植成本。** 典型反例：`ConfigWriteTarget` 新旧差 639 行，但它是 A 类——它自己的 javadoc 写着「No Minecraft imports on purpose」，差异全是新增参数行（碰撞 16 项、雨雾、光束）。同理 `ParameterScreen` 新旧差 822 行，是 C 类（DrawContext），但旧版已有一份 1 015 行的 MatrixStack 版本可以对着改。
- **6 个屏幕在旧版本已经有 MatrixStack 孪生体**：`ConfigScreen`(旧 25 行，是个跳转桩)、`ParameterScreen`(1 015)、`SidingScreen`(790)、`SidingMapScreen`(997)、`ColorPickerScreen`(324)、`ResetConfirmScreen`(112)。这 6 个的工作是「把 1.20.1 新增的行并进去」，不是从零写。剩下 7 个（`BeamEditor3DScreen`/`BeamEditScreen`/`BeamListScreen`/`BeamVehicleScreen`/`PackImportScreen`/`CategoryScreen`/`TrainPreview`）在旧版本**没有任何对应物**。
- **D 类的 8 个文件不阻塞功能面。** 方速（fangsu）的三个 mixin 都是 `@Mixin(targets = "com.fangsu...")` 字符串目标 + `require = 0` + `FangsuMixinPlugin` 的 `Class.forName` 探针；不装方速时它们本来就不加载。旧版本直接不带这三个 mixin 配置即可，代价是「方速手搭道岔不参与动态光」和「方速交通灯不能走 MTR 信号联动」两条降级——这在 1.20.1 上也是 `require=0` 的降级路径。

---

## 2. MTR 类差异对比

### 2.1 三个旧版本：API 完全一致

对 43 个我们用到的 MTR 类逐类 `javap`，输出逐字节比对：

```
diff 118.txt 1192.txt   ->  空
diff 118.txt 1194.txt   ->  空
```

原因：三个版本都是 MTR **3.2.2-hotfix-1**，MTR 自己的代码没有按 MC 版本分叉，MC 类型在 jar 里一律是 intermediary 名（`class_2338` = `BlockPos`、`class_243` = `Vec3d`…），而 intermediary 名跨版本稳定。**结论：1.18.2 / 1.19.2 / 1.19.4 三个版本可以共用同一份 MTR 侧代码，不需要为 MTR 做三份适配。**

### 2.2 3.2.2 → 3.6.4.2：我们用到的东西全在

下表「✔」= 该方法/字段在**四个版本**都存在且签名形状相同（仅 MC 类型的 yarn/intermediary 名不同）。

| 类 / 成员 | 1.18.2 | 1.19.2 | 1.19.4 | 1.20.1 | 声明处 |
|---|---|---|---|---|---|
| `Train.getSpeed()F` | ✔ | ✔ | ✔ | ✔ | `mtr.data.Train`（**不在 TrainClient**，是父类的 `public final`） |
| `Train.getIsOnRoute()Z` | ✔ | ✔ | ✔ | ✔ | `mtr.data.Train` |
| `Train.getRailProgress()D` | ✔ | ✔ | ✔ | ✔ | `mtr.data.Train` |
| `ClientData.TRAINS` `Set<TrainClient>` | ✔ | ✔ | ✔ | ✔ | `mtr.client.ClientData` |
| `ClientData.RAILS` `Map<BlockPos, Map<BlockPos, Rail>>` | ✔ | ✔ | ✔ | ✔ | 同上 |
| `ClientData.DEPOTS` `Set<Depot>` | ✔ | ✔ | ✔ | ✔ | 同上 |
| `ClientData.PLATFORMS` `Set<Platform>` | ✔ | ✔ | ✔ | ✔ | 同上 |
| `ClientData.SIDINGS` / `STATIONS` / `ROUTES` | ✔ | ✔ | ✔ | ✔ | 同上 |
| `DynamicTrainModel.parts` / `.partsInfo` / `.properties` / `.doorMax` | ✔ | ✔ | ✔ | ✔ | `mtr.client.DynamicTrainModel`；构造器 `(JsonObject, JsonObject, DoorAnimationType)` 也一致 |
| `JonModelTrainRenderer.model` `public final ModelTrainBase` | ✔ | ✔ | ✔ | ✔ | `mtr.render.JonModelTrainRenderer`；`VehicleLights.modelOf()` 唯一的反射面 |
| `ModelTrainBase.doorAnimationType` / `.renderDoorOverlay` | ✔ | ✔ | ✔ | ✔ | `mtr.model.ModelTrainBase` |
| `TrainClientRegistry.register(String, TrainProperties)` / `.reset()` / `.getTrainProperties(String)` | ✔ | ✔ | ✔ | ✔ | `TrainClientRegistryMixin` 的两个注入点都在 |
| `TrainProperties` 全字段 + 全参构造器 | ✔ | ✔ | ✔ | ✔ | `mtr.client.TrainProperties` |
| `ClientCache` 全字段 + `requestLiftFloorText` 等 | ✔ | ✔ | ✔ | ✔ | `mtr.client.ClientCache` |
| `PathData.rail` / `.savedRailBaseId` / `.dwellTime` / `.stopIndex` / `.startingPos` | ✔ | ✔ | ✔ | ✔ | `mtr.path.PathData` |
| `Route` 全字段（`routeType`/`circularState`/`lightRailRouteNumber`/`platformIds`） | ✔ | ✔ | ✔ | ✔ | `mtr.data.Route` |
| `Rail` / `RailType` | ✔ | ✔ | ✔ | ✔ | `mtr.data.Rail(Type)` |
| `RenderSignalBase.getNodePos(BlockView, BlockPos, Direction)` **private static** | ✔ | ✔ | ✔ | ✔ | `javap -p` 确认；`RenderSignalBaseMixin` 的 `@Inject(method="getNodePos")` 可原样用 |
| `RenderSignalBase.getOccupiedAspect(BlockPos, float)` **private** | ✔ | ✔ | ✔ | ✔ | `@ModifyVariable` 目标可原样用 |
| `RenderPIDS.getSchedules(T, BlockPos, Map<Long,String>)` | ✔ | ✔ | ✔ | ✔ | `RenderPIDSStationMixin` 的 `@Redirect` 目标 |
| `RouteMapGenerator.generateRouteMap(...)` / `.generateColorStrip(long)` | ✔ | ✔ | ✔ | ✔ | 两者都返回 `NativeImage`；`RouteMapArtMixin` 两个注入点都在 |
| `RenderPSDAPGDoor.render(T,float,MatrixStack,VertexConsumerProvider,int,int)` | ✔ | ✔ | ✔ | ✔ | `RenderPsdDoorTextureMixin` 的 `@Redirect` 目标；子类方法名是 `render` 而非 `method_*` |
| `BlockPSDAPGDoorBase` / `BlockPSDTop` / `BlockAPGGlass` / `BlockAPGGlassEnd` / `BlockAPGDoor` / `BlockPSDAPGBase` / `BlockPSDAPGGlassBase` / `BlockPSDAPGGlassEndBase` | ✔ | ✔ | ✔ | ✔ | 八个类在 3.2.2 里**都已存在**，厦门方块不需要「等 MTR 升级」 |
| `RenderPSDTop` / `RenderAPGGlass` | ✔ | ✔ | ✔ | ✔ | 构造器取 `BlockEntityRenderDispatcher`，四个版本一致 |
| `IBlock`（`HALF`/`THIRD`/`SIDE`/`getStatePropertySafe`/`getVoxelShapeByDirection`…） | ✔ | ✔ | ✔ | ✔ | |
| `DataCache` 全字段 / `RailwayData.stations/platforms/sidings/routes/depots/lifts/dataCache` | ✔ | ✔ | ✔ | ✔ | |
| `SavedRailBase.isCloseToSavedRail(BlockPos,int,int,int)` | ✔ | ✔ | ✔ | ✔ | `PidsScheduleScopeMixin` 目标 |
| `RailwayData.simulateTrains()V` | ✔ | ✔ | ✔ | ✔ | `RailwayDataCaptureMixin` 目标 |
| `TrainServer.simulateTrain(World,float,Depot,DataCache,…)Z` | ✔ | ✔ | ✔ | ✔ | `TrainSimulateCaptureMixin` 目标 |
| `BlockNode` / `BlockSignalLightBase` / `BlockSignalSemaphoreBase` | ✔ | ✔ | ✔ | ✔ | 信号连接器用 |
| `mtr.mappings.BlockEntityMapper` / `EntityBlockMapper` | ✔ | ✔ | ✔ | ✔ | 厦门 BE 用 |

### 2.3 3.6.4.2 有、3.2.2 没有的成员 —— 我们一个都没用

为避免「漏看」，把 diff 里 3.6.4.2 独有的成员全部回扫了一遍源码，命中数为 **0**：

`PathData.adcTime`、`PathData.stopWithoutOpeningDoors`、`PathData(Rail,long,int,int,BlockPos,BlockPos,int)` 新重载、`TrainClient.getManualNotch()`、`getMaxManualSpeedKmh()`、`getDistanceToNextStop()`、`isInEmergencyBrake()`、`changeManualSpeedNew()`、`getManualNotchPercentageForDisplay()`、`getAdcTimeTicks()`、`Train.getManualNotchAccelerationMultiplier(int)`、`Train.isHoldingOnboardTool(PlayerEntity)`、`Train.ACCELERATION_DECIMAL_PLACES` / `P1..P5` / `B1..B7` / `N` / `EB` / `MAX_POWER_NOTCH`、`Train.enablePredictiveBraking`、`Train.useLegacyManualNotch`、`ClientData.appendRails(MinecraftClient, PacketByteBuf)`、`Platform.setAdcTime` / `setStopWithoutOpeningDoors`、`Depot.clientPathGenerationTotalSegments` / `clientPathGenerationReason` / `clientPathGenerationSiding`、`Depot.PATH_REASON_*`。

方向相反的差异（3.6.4.2 多出来的**字段**）也不影响旧版本：`Depot` 多了几个 `public int`，旧版本不读就没事。

### 2.4 唯一真正的 MTR 侧阻塞：named 重映射

`libs/MTR-fabric-*-3.2.2-hotfix-1.jar` 是**未重映射**的 jar：继承来的 MC 类型和方法全是 intermediary 名。

```
1.18.2 / 3.2.2 的 mtr.render.RenderPSDAPGDoor：
  public boolean shouldRenderOffScreen(T);
  public boolean method_3563(MC);                       // = rendersOutsideBoundingBox
  public void method_3569(MC, float, MC, MC, int, int); // = BlockEntityRenderer.render
1.20.1 / 3.6.4.2-named 的同类：
  public boolean rendersOutsideBoundingBox(BlockEntity);
  public void render(BlockEntity, float, MatrixStack, VertexConsumerProvider, int, int);
```

后果：

- `class XiamenPsdBlocks extends mtr.block.BlockPSDAPGDoorBase` 在旧版本上编译不过——编译器看到的是 `Block`(yarn) 与 `class_2248`(intermediary) 两个无关类型。
- `RenderPsdDoor extends RenderPSDAPGDoor` 里 `@Override render(...)` 也对不上 `method_3569`。

`tools/remap-mtr-named.sh` 已经解决了这个问题，但写死了 1.20.1：

```bash
MCI="$LOOM/minecraftMaven/net/minecraft/minecraft-merged-intermediary/1.20.1-.../....jar"
MAP="$LOOM/1.20.1/net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2/mappings.tiny"
```

**移植第一步不是写 Java，是把 `OUT`/`MCI`/`MAP` 参数化，为 1.18.2 / 1.19.2 / 1.19.4 各产出一份 `MTR-fabric-<ver>-3.2.2-hotfix-1-named.jar`。** 没有这一步，第 1 章的 6 个 xiamen 文件（879 行）无法开始。

---

## 3. Fabric API 差异

我们共引用 16 个 fabric-api 包（14 个 `fabric.api.*` + `net.fabricmc.api` + `net.fabricmc.loader.api`）。下表是「该类在对应版本的 fabric-api 里是否存在」，来自各版本 Loom 缓存里实际参与编译的重映射产物。

| # | 包 / 类 | 1.18.2<br>0.77.0 | 1.19.2<br>0.77.0 | 1.19.4<br>0.87.2 | 1.20.1<br>0.92.12 |
|---|---|---|---|---|---|
| 1 | `blockrenderlayer.v1.BlockRenderLayerMap` | ✔ | ✔ | ✔ | ✔ |
| 2 | `client.command.v2.{ClientCommandManager,ClientCommandRegistrationCallback,FabricClientCommandSource}` | **✗** | ✔ | ✔ | ✔ |
| 3 | `client.event.lifecycle.v1.{ClientTickEvents,ClientLifecycleEvents}` | ✔ | ✔ | ✔ | ✔ |
| 4 | `client.keybinding.v1.KeyBindingHelper` | ✔ | ✔ | ✔ | ✔ |
| 5 | `client.networking.v1.{ClientPlayNetworking,ClientPlayConnectionEvents}` | ✔ | ✔ | ✔ | ✔ |
| 6 | `client.particle.v1.ParticleFactoryRegistry` | ✔ | ✔ | ✔ | ✔ |
| 7 | `client.rendering.v1.CoreShaderRegistrationCallback` | ✔ | ✔ | ✔ | ✔ |
| 8 | `client.rendering.v1.{WorldRenderEvents,WorldRenderContext,BlockEntityRendererRegistry}` | ✔ | ✔ | ✔ | ✔ |
| 9 | `command.v2.CommandRegistrationCallback` | **✗** | ✔ | ✔ | ✔ |
| 10 | `event.lifecycle.v1.{ServerTickEvents,ServerLifecycleEvents}` | ✔ | ✔ | ✔ | ✔ |
| 11 | `itemgroup.v1.{FabricItemGroup,ItemGroupEvents}` | **✗** | **✗** | ✔ | ✔ |
| 12 | `networking.v1.{PacketByteBufs,ServerPlayNetworking,PlayerLookup,ServerPlayConnectionEvents}` | ✔ | ✔ | ✔ | ✔ |
| 13 | `object.builder.v1.block.entity.FabricBlockEntityTypeBuilder` | ✔ | ✔ | ✔ | ✔ |
| 14 | `particle.v1.FabricParticleTypes` | ✔ | ✔ | ✔ | ✔ |
| 15 | `resource.{ResourceManagerHelper,SimpleResourceReloadListener}` | ✔ | ✔ | ✔ | ✔ |
| 16 | `net.fabricmc.api.{ModInitializer,ClientModInitializer,Environment}` / `loader.api.FabricLoader` | ✔ | ✔ | ✔ | ✔ |

模块版本（Loom 缓存实测）：networking-api-v1 = 1.0.24 / 1.2.12 / 1.3.6 / 1.3.15；rendering-v1 = 1.11.0 / 1.13.0 / 2.1.5 / 3.0.10；lifecycle-events-v1 = 2.1.1 / 2.2.4 / 2.2.19 / 2.2.23；object-builder = 2.1.5 / 4.2.2 / 7.1.1 / 11.1.5。

### 3.1 `fabric-networking` 的 payload：**这里不用改**

任务书提到「payload 注册方式在 1.19 前不同」。实测结论相反：**payload 体系（`CustomPayload` + `PayloadTypeRegistry`）在 1.20.1 的 fabric-api 0.92.12 里还不存在**——它在 1.20.5 才落地。0.92.12 的 `fabric-networking-api-v1 1.3.15` 仍然是 Identifier 频道：

```
ServerPlayNetworking / ClientPlayNetworking
  public static boolean canSend(...)
  public static void registerGlobalReceiver(Identifier, PlayChannelHandler)
  public static void play(ServerPlayerEntity, Identifier, PacketByteBuf)
```

四个版本形状一致（1.0.24 → 1.3.15 只是内部实现变化）。所以 `LteChannel` / `ClientHandshake` / `ServerHandshake` / `SignalLinkNet` / `SignalLinkClient` **全部是 B 类，不需要重写成 payload**。唯一要留意的差异是 `ClientPlayConnectionEvents.JOIN` 的 handler 参数在 1.19.4 起多了 `PacketSender` 之外的 `HandshakeState`——我们没用到该参数。

### 3.2 `fabric-rendering-v1` 的 WorldRenderEvents 签名：只有返回值变了

四个版本的 `WorldRenderContext` 成员逐个比对，**只有一处不同**：

```
1.18.2 / 1.19.2:  net.minecraft.util.math.Matrix4f projectionMatrix();
1.19.4 / 1.20.1:  org.joml.Matrix4f              projectionMatrix();
```

其余（`worldRenderer()` / `matrixStack()` / `tickDelta()` / `camera()` / `lightmapTextureManager()` / `consumers()` / `frustum()` / `advancedTranslucency()`）完全一致。我们没有任何地方读 `projectionMatrix()`，所以 `BeamGlow` / `ProtoBeamCone` / `RenderHooks` / `ConnectorNodeHighlight` 的 `WorldRenderEvents.START`/`END` 注册代码零改动。

### 3.3 `fabric-client-command`：1.18.2 没有 v2

这是 `Commands.java`（607 行）唯一需要改的地方：

```
1.18.2: net.fabricmc.fabric.api.client.command.v1.ClientCommandManager
        net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource
        —— 没有 ClientCommandRegistrationCallback，必须直接 ClientCommandManager.DISPATCHER.register(...)
1.19.2+: net.fabricmc.fabric.api.client.command.v2.*（含 ClientCommandRegistrationCallback）
```

同理由：`bind/LteBindCommand.java` 用的 `command.v2.CommandRegistrationCallback` 在 1.18.2 不存在，要退回 `command.v1.CommandRegistrationCallback`（1.18.2 只有 v1，1.19.2 起反过来只有 v2）。

### 3.4 `item-group-api-v1`：1.18.2 / 1.19.2 是另一套

`FabricItemGroup` + `ItemGroupEvents` 只在 1.19.4+ 存在。1.18.2/1.19.2 要走老 API：

```
1.18.2 / 1.19.2: net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder
                 （fabric-item-groups-v0 0.3.16 / 0.3.39）
1.19.4 / 1.20.1: net.fabricmc.fabric.api.itemgroup.v1.{FabricItemGroup,ItemGroupEvents}
```

影响 `signal/LteCoreItems.java`（138 行）与 `xiamen/XiamenObjects.java`（337 行）。

### 3.5 `CoreShaderRegistrationCallback`：接口在，泛型参数变了

四个版本都有，但 `RegistrationContext.register` 的第三个参数：

```
1.18.2 / 1.19.2: register(Identifier, VertexFormat, Consumer<net.minecraft.client.render.Shader>)
1.19.4 / 1.20.1: register(Identifier, VertexFormat, Consumer<net.minecraft.client.gl.ShaderProgram>)
```

对应地，取 uniform 的类也不同：`Shader#getUniform(String) -> GlUniform`（1.18.2/1.19.2，`javap -p` 确认存在）vs `ShaderProgram#getUniform(String) -> GlUniform`（1.19.4+）。`Framebuffer.getDepthAttachment()` 返回 `int` 在四个版本里**都有**，所以深度拷贝那一段逻辑不用重写。

---

## 4. 建议的移植顺序与工作量

工作量按「可复用行数 / 需重写类数」估算，不给分钟数。总盘子 46 228 行。

### 阶段 0 —— 构建基础设施（0 行业务代码，阻塞后续全部）

| 任务 | 为什么必须先做 |
|---|---|
| 参数化 `tools/remap-mtr-named.sh`，为 1.18.2/1.19.2/1.19.4 各产出 `MTR-fabric-<mcver>-3.2.2-hotfix-1-named.jar` | 不产出则 xiamen 的 4 个类无法编译（见 2.4） |
| 三个版本 `fabric.mod.json` 加 `main` 入口（`LteServer`、`VehicleLightsJsonServer`）、`environment` 由 `client` 改 `*` | 服务端碰撞/信号/PIDS 全在 `server/`、`net/`、`bind/` 下 |
| 新增 mixin 配置：`mtrlte.mtr.mixins.json`、`mtrlte.mtrserver.mixins.json`、`mtrlte.beamlight.mixins.json`、`mtrlte.collision.mixins.json`、`mtrlte.rain.mixins.json` | 旧版只有 `nte` / `ante` 两个配置文件 |
| `lambdynlights` 依赖由 `depends` 改 `recommends` | 旧版现在是硬依赖 `>=2.1.0`，1.20.1 是 `recommends >=4.4.0` |

### 阶段 1 —— 把 A 类下沉到 `common/`（最大收益、零风险）

- **74 个文件 / 21 861 行原样复用，0 个类需要重写。**
- 做法：从 `v1.20.1/` 移入 `common/`（`common` 已被四个 sourceSet 共享，且现有 42 个文件已经在四个版本上编译通过，这条路径已被验证）。
- 例外检查：`ProtoBeamCone` 引用同包的 `ConeVolumeRenderer`，移动时要保证非 1.20.1 版本仍有那个 31 行的 stub（`ready()` 返回 false），否则 `ProtoBeamCone` 编译不过。
- 这一阶段结束时，三个旧版本的模组已经拥有：光束贴片、光柱几何、`VehicleLights` 模型解析、`TrainPose`、`LightController`、`RailRelightScheduler`、`BeamRelight`、`RailWeather`、内置车型表、`DebugProbe`、ANTE/NTE rail 混入。

### 阶段 2 —— B 类逐版本小改

- **54 个文件 / 13 341 行**，平均每文件 1–3 处改动；**0 个类需要重写**。
- 3 个 shim 已经存在，直接沿用：`McCompat`（`SimpleOption`）、`KeyHooks`（`KeyBinding` 三参构造）、`GlowCompat`（`BufferRenderer` 三写法）。
- 需要**新增**的 shim（建议同样做成逐版本小文件，别散在业务类里）：
  - `RegistryCompat`：`Registries.*` ↔ `net.minecraft.util.registry.Registry.*`（命中 8 个文件：`SprayType`、`TabIcons`、`LteCoreItems`、`XiamenObjects`、`DisplayPushTicker`、`FangsuSignalCompat`、`CollisionImpact`、`TrainImpactDamageSource`）
  - `DamageCompat`：`DamageType`/`RegistryEntry<DamageType>` → 1.18/1.19.2 的 `DamageSource` 直构（`CollisionImpact`、`TrainImpactDamageSource`）
  - `ItemGroupCompat`：`FabricItemGroup` ↔ `FabricItemGroupBuilder`（`LteCoreItems`、`XiamenObjects`）
  - `CommandCompat`：`client.command.v2` ↔ `v1`（`Commands`、`LteBindCommand`）
- 版本分支型：`LightBackends`（`Ldl4Backend` / `Ldl2Backend`）、`RenderHooks`（旧版把 1.20.1 新增的 `SprayType.init()`/`RainSpray.tick()`/`DebugProbe.tick()`/`ProtoBeamCone.render()` 注册并进去）。

### 阶段 3 —— 屏幕（C 类，最大一块）

- **13 个文件 / 8 399 行**，其中 **7 个类需要新写 MatrixStack 渲染体**，6 个有旧版孪生体可改。
- 先做有孪生体的 6 个（`ParameterScreen` 1 447 / `SidingMapScreen` 955 / `SidingScreen` 737 / `ColorPickerScreen` 310 / `ResetConfirmScreen` 90 / `ConfigScreen` 243，合计 3 782 行，其中布局/逻辑约 80 % 可搬）：把 1.20.1 新增的行（碰撞 16 项、雨雾、光束、PIDS）加到旧版 MatrixStack 外壳里。
- 再做没有孪生体的 7 个（`BeamEditor3DScreen` 1 854 / `TrainPreview` 934 / `BeamEditScreen` 602 / `BeamVehicleScreen` 390 / `BeamListScreen` 383 / `CategoryScreen` 234 / `PackImportScreen` 220，合计 4 617 行）：`render(DrawContext,...)` → `render(MatrixStack,int,int,float)`，`context.drawText*` → `drawTextWithShadow(matrices,...)`，`Tooltip`（1.19.4+）→ `renderTooltip`，`RotationAxis`（1.19.4+）→ `Quaternion`/`AxisAngle4f`。
- 建议按「配置类屏幕 → 光束编辑类屏幕 → 3D 编辑器」的顺序，因为 3D 编辑器（1 854 行）同时依赖 `TrainClientRegistry` 与实时预览，最后做。

### 阶段 4 —— 厦门方块与 BE 渲染（依赖阶段 0 的 named jar）

- **5 个文件 / 879 行**，**4 个类需要重写渲染/BE 绑定**（`XiamenPsdBlocks` 347、`RenderPsdDoor` 221、`PsdAPGItem` 154、`XiamenClient` 102），第五个 `RenderPsdApgDoor` 55 行是薄壳。
- MTR 侧类的存在性已确认（2.2 表），卡点全在 named 重映射与 `BlockEntityRenderer` 的方法名（`method_3563` vs `rendersOutsideBoundingBox`）。
- 资源侧（`assets/mtrlte/blockstates`、`models/block`、`textures/block`、`loot_tables` 共 180+ 文件）与版本无关，直接复制。

### 阶段 5 —— 渲染敏感的三个独立件

- `ConeVolumeRenderer`（650 行）：算法（深度 blit、ray-march uniform、粗采样统计）可复用，约 12 处类型换成 `Shader`；**1 个类需要按版本重写类型层**。旧版现在是 31 行 stub，`ProtoBeamCone` 会自动退化成加法壳光柱——所以这一件可以最后做，做不完也不阻塞其余功能。
- `mixin/beamlight/BeamLightMixin`（111 行）：注入点 `getLightmapCoordinates` 四版本都在，`require=1` 需按版本重新验证；**1 个类**，另外要在旧版新建 `mtrlte.beamlight.mixins.json`。
- `mixin/rain/WorldRendererRainMixin`（54 行）：`renderWeather`/`tickRainSplashing` 四版本都在且都是 private，但 `renderWeather` 内部实现跨版本变化大，`require=0` 需逐版本确认；**1 个类**。

### 阶段 6 —— 服务端功能（阶段 0 的 `environment:"*"` 就位后）

- `server/`（5 文件 1 093 行）、`net/`（4 文件 415 行）、`bind/`（13 文件 2 678 行）、`signal/` 的联网与存储（7 文件 1 498 行，不含方速那 3 个与 `LteCoreItems`）都属 A/B 类，没有渲染面，可在阶段 2 顺带完成；真正要先验证的是「服务端装不装都能跑」的降级路径（`ServerAuthority` + `ClientHandshake` 握手）。

### 一句话的优先级

```
阶段 0（named remap + 清单文件） → 阶段 1（74 文件原样下沉）
  → 阶段 2（54 文件小改） → 阶段 6（服务端）
  → 阶段 3（13 屏幕） → 阶段 4（厦门 5 文件） → 阶段 5（cone / beamlight / rain）
```

前两个阶段做完，三个旧版本就已经有「车头灯 + 光柱 + 轨行区动态光 + 雨雾」的完整主功能；后四个阶段是把 1.20.1 的编辑器、厦门方块、服务端碰撞补齐。

---

## 5. 阻塞项清单（按严重度）

| # | 阻塞项 | 影响范围 | 解除方式 |
|---|---|---|---|
| 1 | `tools/remap-mtr-named.sh` 只支持 1.20.1 | 阶段 4 全部（4 个类，879 行）；`XiamenPsdBlocks`/`RenderPsdDoor` 直接编译失败 | 参数化 `MAP`/`MCI`/`OUT`，为三个版本各产出 named jar |
| 2 | 旧版 `fabric.mod.json` 无 `main` 入口、`environment: client` | 服务端碰撞/信号/PIDS 全部不生效 | 阶段 0 改写 |
| 3 | 旧版缺 5 个 mixin 配置（mtr / mtrserver / beamlight / collision / rain） | 信号联动、PIDS 图块、碰撞、下雨静音、方块光烘焙全不加载 | 阶段 0 增文件 |
| 4 | 1.18.2 无 `client.command.v2` / `command.v2` | `Commands`(607)、`LteBindCommand`(146) | 退回 v1 API |
| 5 | 1.18.2/1.19.2 无 `itemgroup.v1` | `LteCoreItems`(138)、`XiamenObjects`(337) | `FabricItemGroupBuilder` |
| 6 | 1.18.2/1.19.2 无 `net.minecraft.registry.*`、`DamageType`、`InputSupplier`、`LimbAnimator`、`Tooltip`、`RotationAxis`、`SimpleOption` | 8 个 B 类文件 + 屏幕 | 各自的 shim（见阶段 2 / 3） |
| 7 | 旧版 `lambdynlights` 是硬 `depends` | 没装 LDL 的客户端直接拒绝启动 | 改 `recommends` + `LightBackends` 的 Noop 兜底（1.20.1 已有该兜底，旧版 `LightBackends` 只有 16 行，没有 Noop） |
| 8 | 方速 1.20.1 字节码 | 3 个 mixin + `FangsuTrafficLightMode` | 旧版不带 `mtrlte.fangsu.mixins.json`，功能降级为空 |
| 9 | LDL 4.x 只有 1.20.1 | `Ldl4Backend`、`MtrHeadlightDynamicLights` | 旧版继续用 `Ldl2Backend`（已存在，1 077 行，含 LDL 2.x 的 8 点近似与重建预算） |
