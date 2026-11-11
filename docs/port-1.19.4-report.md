# 1.20.1 → 1.19.4 功能移植报告

**结果**：`gradle -p v1.19.4 clean build` → **BUILD SUCCESSFUL**；产物 `v1.19.4/build/libs/lte-1.0.0.jar`
（2 059 368 B / **268 个 class**），与 1.20.1 的 269 个 class 相比只少了 7 个「仅 1.20.1」的 D 类
（方速 3 个 mixin + 3 个方速类 + LDL 4.x 后端的 2 个类）。1.19.4 侧源码 **111 文件 / 31 945 行**，
共享 `common/` **56 文件 / 16 664 行**。

---

## 1. 阶段 0：构建基础设施

| 项 | 做法 |
|---|---|
| named MTR jar | `tools/remap-mtr-named.sh` 增加第 3 个参数 `mcver`，`MCI`/`MAP`/yarn 目录由它派生（1.18.2 / 1.19.2 / 1.19.4 / 1.20.1 四档）。产出 `libs/MTR-fabric-1.19.4-3.2.2-hotfix-1-named.jar`，`javap` 确认 `RenderPSDAPGDoor.render(...)` 已是 yarn 名 —— 这是 `XiamenPsdBlocks extends BlockPSDAPGDoorBase` 能编译的前提。 |
| `fabric.mod.json` | `environment` 由 `client` 改 `*`；加 `main`（`LteServer`、`VehicleLightsJsonServer`）；client 入口补齐 `CollisionBootstrap` / `BuiltInTrainsInit` / `VehicleLightsJsonClient` / `SignalLinkClient` / `XiamenClient`；`lambdynlights` 由 `depends` 改 `recommends`（LDL 缺失时走 `LightBackends` 的 Noop）。 |
| mixin 配置 | 新增 `mtrlte.mtr` / `mtrlte.mtrserver` / `mtrlte.collision` / `mtrlte.rain` / `mtrlte.beamlight` 五个。**不带** `mtrlte.fangsu.mixins.json`（D 类）。 |
| 编译参数 | `-AMSG_MIXIN_SOFT_TARGET_NOT_FOUND=disabled`（`RenderPidsTianjinStationMixin` 的目标 `ziyue.tjmetro.*` 只在运行期存在）。 |
| 资源 | 1.20.1 的 `assets/`（371 文件：blockstates / models / textures / lang / builtin_lights.json）整树复制。 |

## 2. 搬进 `common/` 的文件（13 个 + 1 个新增，共 4 556 行）

判据：审计文档的 A 类 **且** 实测 1.18.2/1.19.2 也能编译（这一步很关键，见 §6）。

| 文件 | 行 | | 文件 | 行 |
|---|---|---|---|---|
| `RainHush.java` | 364 | | `net/LteChannel.java` | 98 |
| `VehicleLightPacks.java` | 135 | | `net/ServerAuthority.java` | 108 |
| `mtr/BuiltInTrains.java` | 532 | | `server/ServerConfig.java` | 42 |
| `bind/LteMtrLookup.java` | 477 | | `signal/SignalSourceCapture.java` | 33 |
| `server/MtrServerData.java` | 117 | | `collision/RailBed.java` | 360 |
| `collision/CarGeometry.java` | 497 | | `collision/Trackbed.java` | 77 |
| `collision/TrainTickCapture.java` | 80 | | **`MtrCompat.java`（新）** | 60 |

另外两个原本就在 `common/` 的文件做了版本兼容化（都只动几行）：`DebugProbe`（`BlockPos.ofFloored`
→ 三次 `Math.floor`）、`ProtoBeamCone`（`ConeVolumeRenderer.logIfDue()` 改反射调用，见 §4.1）。

**剩下 8 个审计标 A 的文件留在版本目录**，因为实测在 1.19.2/1.18.2 上编不过（动了 `common` 就会连带
弄坏那两个只读版本）：`BeamEditorOpener`/`BeamSession`（引用只存在于 1.19.4 的 `BeamEditor3DScreen`/
`BeamListScreen`）、`MobCollision`（引用 `CollisionImpact`）、`CustomPack`（1.19.2 上有 22 处 API 差异）、
`BeamSnap`（引用 `BeamSession`）、`SignalLinks`、`bind/PidsStationIndex`（MTR intermediary 名
`class_2338` 与 yarn `BlockPos` 不兼容）、`ConfigCategory`（`Text.translatable` 在 1.18.2 不存在）。
它们现在是「v1.20.1 + v1.19.4 各一份」，功能一致。

## 3. 版本 API 垫片（全部为 1.19.4 侧新增）

| 垫片 | 解决的差异 | 影响面 |
|---|---|---|
| `clientgui/DrawContext.java`（新，约 230 行） | 1.20.1 的 `net.minecraft.client.gui.DrawContext` 在 1.19.4 不存在。保留同名同类形状，内部转发到 1.19.4 的 `DrawableHelper` / `RenderSystem` 静态方法（fill / fillGradient / drawTexture / drawText* / enableScissor），构造时抓住 `MatrixStack`。 | 13 个屏幕：只改 import 一行 + `render` 签名（`DrawContext context` → `MatrixStack matrices` + 一行 `new DrawContext(matrices)`），**8000 行屏幕代码没有重写** |
| `signal/FangsuTrafficLightMode.java`（新） | 1.20.1 版走 `com.fangsu` 反射；1.19.4 无对应构建。保留同样的 `Result{OK,ALREADY,NOT_FANGSU,FAIL}` 与 `toMtr()`，一律返回 `NOT_FANGSU` | 方速交通灯仍可绑定信号，只是不替你切模式（与 1.20.1 装不兼容方速版时的行为相同） |
| `MtrCompat.railsMap()` | 3.2.2 的 `RailwayData` **没有** `getRailsMap()`（只有私有 `rails` 字段） | `LteMtrLookup` / `MtrServerData` / `SignalLinkNet` 三处轨道表读取 |
| 物品页签 | 1.19.4 的 item-group-api-v1 3.0.x 没有 `Registries.ITEM_GROUP`：`FabricItemGroup.builder(id)` + `ItemGroupEvents.modifyEntriesEvent(id)`，页签由 `build()` 自动追加 | `LteCoreItems`、`XiamenObjects` |
| `AbstractBlock.Settings.create()` → `Settings.of(Material.STONE)` | 1.19.4 没有 `create()` | `XiamenObjects#registerLogo` |
| `ServerPlayerEntity#getServerWorld()` → `getWorld()` | 1.20 才加的方法 | `LteServer` 进服同步 |
| `ServerCommandSource#sendFeedback(Text,boolean)` | 1.20.1 收 `Supplier<Text>` | `bind/LteBindCommand`（8 处） |
| MTR named jar 带来的 intermediary 名 | 老代码里 `Pair.method_15441()` / `BlockPos.method_10263()` 等直接写 intermediary 名，换 named jar 后失效 | `SidingScreen` / `SidingMapScreen` 共 13 处 → `getLeft()/getRight()/getX()/getZ()` |

## 4. 卡点与降级项

### 4.1 体积光锥（`ConeVolumeRenderer`）—— **降级：不启用**
1.20.1 的实现依赖 `ShaderProgram`/`GlUniform` + 深度附件 blit + ray-march uniform。1.19.4 仍保留原来的
31 行 stub（`ready()` 恒 false），于是 `ProtoBeamCone` 自动退回「加法壳层光柱」——光柱本身、朝向、
衰减、按车型的几何全部照常，只是没有「被墙体遮挡的部分被深度剔除」这一层。
`ProtoBeamCone` 里对 `logIfDue()` 的调用改成反射（方法不存在就当没有统计窗口），这样四个版本都能编。

### 4.2 动态光源后端 —— **降级：LDL 2.x 点光近似**
`Ldl4Backend`（LDL 4.x 的 `DynamicLightBehaviorManager`/`LineLightBehavior`）只有 1.20.1 有。
1.19.4 走已有的 `Ldl2Backend`（LDL 2.x 的 8 点/线近似 + 重建预算）；`LightBackends` 采用 1.20.1 的
「探测失败退 Noop」结构，没装 LDL 时其余功能全部照常，配置界面里动态光源那行会显示为不可用。

### 4.3 方速（fangsu）—— **降级：整块不启用**
3 个 mixin + `FangsuMixinPlugin` + `FangsuTurnoutLight` + `FangsuCompat` + `FangsuTrafficLightMode`
（真实实现）都不带。代价：手搭道岔不参与动态光、交通灯不能自动切 MTR 联动档。这两条在 1.20.1 上也是
`require = 0` 的降级路径，不装方速时同样不生效。

### 4.4 其它
- `mtr/mixin` 里的天津地铁 PIDS（`ziyue.tjmetro`）mixin 保留，目标类运行期才存在，靠编译参数降级校验。
- `mtrserver` 三个 mixin 依赖 MTR 3.2.2 的 `RailwayData.simulateTrains` / `TrainServer.simulateTrain` /
  `SavedRailBase.isCloseToSavedRail`，审计已 `javap` 确认三者在 3.2.2 都在。
- 雨雾 mixin（`WorldRendererRainMixin`）两处注入都是 `require = 0`，1.19.4 的 `renderWeather` 内部实现
  与 1.20.1 不同也不会崩，最差是「雨声静音」不生效。
- `block light` 的 `BeamLightMixin` 注入点 `getLightmapCoordinates(BlockRenderView, BlockState, BlockPos)`
  在 1.19.4 存在且同签名，保留 `require = 1`（映射一变就大声失败，而不是悄悄变暗）。

## 5. 删除 / 改动的既有文件

- 删除 `v1.19.4/.../SidingDetailScreen.java`：0.2.2 时代的残留，`extends ParameterScreen`（1.20.1 的
  ParameterScreen 是 `final`），全仓库无任何引用，1.20.1 里也没有这个类。
- `v1.19.4` 的 6 个旧屏幕（Config/Parameter/Siding/SidingMap/ColorPicker/ResetConfirm）被 1.20.1 版本
  覆盖（旧版是 0.2.2 的 1 000 行外壳，新版含碰撞 16 项、雨雾、光束、PIDS 等全部参数行）。
- `v1.19.4/build.gradle`：MTR 换 named jar、加软目标编译参数。

## 6. 与其它三个版本的关系（回归验证）

| 版本 | 改动后 `compileJava` |
|---|---|
| **v1.19.4** | 0 错（目标） |
| v1.20.1 | 0 错（只读：搬走的 21 个文件已按需回填，class 数 269 不变） |
| v1.19.2 | 0 错（**比改动前的 4 错更好**：`ProtoBeamCone` 的 `logIfDue` 与 `DebugProbe` 的
`ofFloored` 已在 `common` 里兼容化） |
| v1.18.2 | 2 错，**改动前就有**：`common/DebugProbe.java:530` 用 `Text.literal`，1.18.2 只有
`new LiteralText`（该文件非本次改动行，`git diff` 可见只动了 `isAir` 5 行）。修它需要按版本分叉
`DebugProbe`，超出本次「1.19.4 移植」范围。 |

## 7. 验收抽查

```
unzip -l v1.19.4/build/libs/lte-1.0.0.jar | grep -c '\.class$'   -> 268
BeamGlow / ProtoBeamCone / BeamGeom / RainSpray / SprayParticle / SprayType
collision.CollisionSystem / collision.CollisionImpact / signal.SignalLinkItem / signal.SignalLinkStore
xiamen.XiamenPsdBlocks（+ 门/玻璃/端头/顶盖 BE）/ xiamen.RenderPsdDoor / xiamen.PsdAPGItem
BeamEditor3DScreen / BeamEditScreen / BeamListScreen / BeamVehicleScreen / CategoryScreen /
PackImportScreen / TrainPreview / ParameterScreen / ConfigScreen / CustomPack / BeamPackWriter / TrainNames
mixin.collision.MixinEntityMove / mixin.beamlight.BeamLightMixin / mixin.mtr.* / mixin.rain.* / mixin.mtrserver.*
```
mixin 配置里点到名的类、`fabric.mod.json` 的 9 个入口点、`lte.refmap.json` 全部在 jar 内核对通过
（`temp/mixincheck.sh`）。
