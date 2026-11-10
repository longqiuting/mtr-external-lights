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
| `v1.20.1/KeyHooks.java` | 43 | B | KeyBinding(String,int,String) 3-arg ctor exists in all four, but 1.18.2 has no InputUtil.Type overload of KeyBindingHelper.registerKeyBinding expectation - the 1.18.2 copy already ships the 3-arg form |
| `v1.20.1/TrainNames.java` | 237 | B | net.minecraft.text.TranslatableTextContent is 1.19.2+; 1.18.2 needs net.minecraft.text.TranslatableText and Text.asString() vs Language.getInstance() |
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
| `v1.20.1/RainSpray.java` | 923 | B | ClientData/TrainClient/ParticlesMode/ParticleTypes; ParticlesMode enum moved 1.19 (ParticleStatusEffect) |
| `v1.20.1/SprayParticle.java` | 339 | B | SpriteBillboardParticle/SpriteProvider/ParticleTextureSheet; ParticleTextureSheet.TERRAIN_SHEET name unchanged but SpriteProvider is 1.19+ |
| `v1.20.1/VehicleLightsJsonClient.java` | 93 | B | SimpleResourceReloadListener + ClientLifecycleEvents; absent reload listener ctor arg differs 1.19.4+ |
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

TOTALS
A 3171
B 2431
C 1906
D 140
