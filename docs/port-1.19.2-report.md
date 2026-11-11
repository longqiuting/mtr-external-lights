# 1.19.4 → 1.19.2 功能移植报告

**结果**：`gradle -p v1.19.2 clean build` → **BUILD SUCCESSFUL**；产物
`v1.19.2/build/libs/lte-1.0.0.jar`（**2 067 368 B / 273 个 class**）。
1.19.4 是 268 个 class，差 5 个 = 本次新增的 4 个兼容类（`Widgets` 内含一个 `Builder` 内部类）。
v1.19.2 侧源码 **115 文件 / 32 565 行**（1.19.4 为 111 / 31 945，多出来的正是这 4 个兼容类 /
620 行）；共享 `common/` **56 文件 / 16 664 行**未动。

文件集与 1.19.4 **逐个对齐**：`diff` 两侧 java 文件列表，差别只有新增的 4 个
`clientgui/{Matrix4f,Vector3f,Quaternionf,Widgets}.java`。

---

## 1. 搬了什么

| 类别 | 做法 |
|---|---|
| Java 源码 | `v1.19.4/src/main/java` **整树**复制到 `v1.19.2/src/main/java`（111 文件），再按 §3 逐项适配。删除 `SidingDetailScreen.java`（1.19.4 已删的孤儿类：`extends ParameterScreen`，而新版 `ParameterScreen` 是 final）。 |
| 资源 | `v1.19.4/src/main/resources` 整树复制（**380 文件**：blockstates / models / textures / lang / particles / shaders / 7 个 mixin 配置 / icon）。 |
| `fabric.mod.json` | 用 1.19.4 的那份（`environment: "*"`、`main` 两端入口、7 个 mixin 配置、`lambdynlights` 落在 `recommends`），只改两处：`minecraft: ~1.19.2`、`lambdynlights: >=2.2.0`（1.19.2 线只有 LDL 2.x）。 |
| `build.gradle` | MTR 换 **named jar**（`libs/MTR-fabric-1.19.2-3.2.2-hotfix-1-named.jar`）；加 `-AMSG_MIXIN_SOFT_TARGET_NOT_FOUND=disabled`（天津地铁 `ziyue.tjmetro` 的目标类只在运行期存在）。 |

## 2. MTR named jar：1.19.2 必须单独产一份（但 API 是同一份）

用 `tools/remap-mtr-named.sh libs/MTR-fabric-1.19.2-3.2.2-hotfix-1.jar … 1.19.2` 产出
`libs/MTR-fabric-1.19.2-3.2.2-hotfix-1-named.jar`。

**能不能复用 1.19.4 那份？不能，但结论是「安全」**——`javap -p -s` 全量导出两个 named jar 的
API（1 839 个类 / 各 75 5xx 行）后逐行 diff，**只有 112 行差异，且全部是 MC 侧包名搬迁**：

```
net.minecraft.network.Packet          → net.minecraft.network.packet.Packet
net.minecraft.util.registry.Registry* → net.minecraft.registry.Registry*
BlockState.isTranslucent(...)         → isTransparent(...)
+ lambda$registerCreativeModeTab$…（1.19.4 的 item group 内部 lambda）
```

`mtr.*` 自己的成员（`getSchedules` / `getNodePos` / `simulateTrains` / `isCloseToSavedRail` …）
两版**完全同名同签名**，类集合除 architectury 注入包名外**逐条相同**。所以：MTR 版本号一样
（`3.2.2-hotfix-1`），只是它继承的 MC 类型按版本变，named jar 必须按版本各一份。

## 3. 1.19.2 特有的卡点（1.19.4 不需要这些）

| # | 1.19.4 的写法 | 1.19.2 的现实 | 本次处理 | 影响面 |
|---|---|---|---|---|
| 1 | `org.joml.Matrix4f` / `Vector3f` / `Quaternionf`、`RotationAxis` | **1.19.2 的原版不带 JOML**（`org.joml` 是 1.19.3 才进依赖树的，1.19.2 的 `version.json` 里没有）；`RotationAxis` 也不存在 | 新增 `clientgui/{Matrix4f,Vector3f,Quaternionf}.java`（**JOML 形状的本地垫片**：列主序 `float[16]`、`m00..m33` 命名、`translate/scale/rotate/mul` 全为右乘、`invert()`、`transformDirection`、`toMc()` 导出成原版矩阵）。`RotationAxis.POSITIVE_X.rotationDegrees(d)` → `new Quaternionf().rotateX(toRadians(d))` | `TrainPreview`（车模预览 / 拾取）、`BeamEditor3DScreen`（拖拽反投影）、`SprayParticle`（雨丝倾斜）、`DrawContext`、`GlowCompat` |
| 2 | `net.minecraft.registry.{Registries,Registry,RegistryKey,RegistryKeys}` | 1.19.2 **连 `Registries` 类都没有**（包里只有 `BuiltinRegistries`），全部走 `net.minecraft.util.registry.Registry` 的静态字段（`Registry.ITEM/BLOCK/BLOCK_ENTITY_TYPE/PARTICLE_TYPE`）；`RegistryKeys` 不存在 | `Registries.X` → `Registry.X`；`RegistryKeys` 的 import 直接删（item group 改造后不再需要） | `LteCoreItems` / `XiamenObjects` / `TabIcons` / `SprayType` / `FangsuSignalCompat` / `DisplayPushTicker` |
| 3 | `fabric-item-group-api-v1`（`FabricItemGroup.builder(id).displayName().icon().build()` + `ItemGroupEvents.modifyEntriesEvent`） | fabric-api 0.77.0 **还没有** item-group-api-v1（1.19.3 才有），只有老线的 `FabricItemGroupBuilder`，且**没有 `displayName`、没有 `ItemGroupEvents`** | `FabricItemGroupBuilder.create(id).icon(..).appendItems(List<ItemStack> → …).build()`；显示名由 id 自动成的 `itemGroup.mtrlte.tools` / `itemGroup.mtrlte.core` 决定（lang 里正好有这两个键）；页签内容物改成 `new ItemStack(item)` 追加 | 两个创造页签 |
| 4 | `ButtonWidget.builder(label, action).dimensions(..).build()` | 1.19.2 **没有 Builder**（1.19.3 才有），只有 7 参构造器 | 新增 `clientgui/Widgets.java`：`Widgets.button(label, action).dimensions(x,y,w,h)[.tooltip(text)].build()`，内部转发到 `new ButtonWidget(x,y,w,h,label,action,TooltipSupplier)` | **41 处**调用（12 个屏幕），只改 `ButtonWidget.builder(` → `Widgets.button(` 一行 |
| 5 | `ClickableWidget#setTooltip(Tooltip)` + `client.gui.tooltip.Tooltip` | 1.19.2 两者都没有；tooltip 是**建按钮时**给的 `ButtonWidget.TooltipSupplier#onTooltip(button, matrices, mouseX, mouseY)` | 把说明提前到建按钮时（`Widgets.Builder.tooltip(Text)`）；`ConfigScreen` / `ParameterScreen` 的 `addButton` 增加带 tooltip 的重载，4 处 `setTooltip` 改为构造期传入；渲染走 `Screen#renderTooltip`（1.19.2 里是 **public** 2 参版） | 4 处悬停说明（`hint.needsLdl` / `hint.damageInCreative` / `tip.needWorld`） |
| 6 | `DamageType` + `RegistryEntry<DamageType>`（`DamageSources#generic().getTypeRegistryEntry()`） | 1.19.2 没有 `DamageType` 注册表，`DamageSource` 就是「name + 覆写 `getDeathMessage`」 | `TrainImpactDamageSource(boolean fast)` → `super("mtrlte.trainImpact")`；`CollisionImpact#sourceFor` 直接 `new TrainImpactDamageSource(high)` | 撞击伤害的两段死亡文案（行为等价） |
| 7 | `player.limbAnimator.updateLimbs(0,1)`（`LimbAnimator`） | 1.19.2 没有 `LimbAnimator`，腿摆幅就是 `LivingEntity.limbDistance` / `lastLimbDistance` 两个字段 | 直接把两个距离写 0（离开车顶后原版 tick 自己算回） | 站车顶时「腿不动」 |
| 8 | `BlockPos.ofFloored(x,y,z)` | 1.19.2 只有 `BlockPos(int,int,int)` 与 `ofFloored(Position)` | `new BlockPos(MathHelper.floor(x), …)` | 2 处（`CollisionSystem`、`RainSpray`） |
| 9 | `ResourcePack#openRoot(String...)` → `InputSupplier` | 1.19.2 是 `openRoot(String) → InputStream` | `readAsset` 不再 `split("/")`，整条相对路径传进去；顺手删掉从未被调用的 `BeamPackWriter#readAll(InputSupplier)` | 光束包读取 |
| 10 | `BlockState#isReplaceable()` | 1.19.4 才有 | `state.getMaterial().isReplaceable()` | `PsdAPGItem` 放置检查 |
| 11 | `TranslatableTextContent#getFallback()` | 1.19.4 才有 | 取不到翻译就直接用 key | `TrainNames` |
| 12 | `ItemUsageContext#getHorizontalPlayerFacing()` | 1.19.4 才有 | `getPlayerFacing()` | `PsdAPGItem` ×2 |
| 13 | `TextFieldWidget#setPlaceholder(Text)` | 1.19.2 没有 placeholder API | 删掉该调用——构造器第 6 个参数（`tr("beam.search")` / `tr("sidings.search")`）本来就是空格时的提示文本 | 两个搜索框 |
| 14 | `ClickableWidget#setFocused(boolean)`（public）/ `ButtonWidget#getX()/getY()` | 1.19.2 里 `setFocused` 是 **protected**，`x`/`y` 是 public 字段、没有 getter | 搜索框用 `setTextFieldFocused(..)`；按钮坐标读 `.x` / `.y` | 3 + 8 处 |
| 15 | `GameRenderer::getPositionColorProgram` 等 | 1.19.2 是 `getPositionColorShader` / `getPositionTexColorShader`（返回 `Shader`，`RenderSystem#setShader(Supplier<Shader>)` 收得上） | 改方法引用 | `DrawContext`（2）、`GlowCompat`（1） |
| 16 | `BufferRenderer.drawWithGlobalProgram(BuiltBuffer)` | 1.19.2 叫 `drawWithShader`（`GlowCompat` 的注释里早就写明了这条对应） | 改调 `drawWithShader` | 3 处满屏 quad 提交 |
| 17 | `DrawableHelper.drawTextWithShadow(String/OrderedText)`、`drawCenteredTextWithShadow(Text/String)` | 1.19.2 的 `DrawableHelper` 只有 `Text`（文本）与 `OrderedText`（居中）两种组合，缺另外四个 | `DrawContext` 六个重载统一改走 `TextRenderer#drawWithShadow`（String / Text / OrderedText 三重载都有），居中版自己算 `centerX - width/2` | 全部屏幕的文字 |

## 4. 与 1.19.4 一致的降级项（本次未变）

- **体积光锥** `ConeVolumeRenderer` 仍是 stub（`ready()` 恒 false），`ProtoBeamCone` 退回加法壳层光柱。
- **动态光源** 走 LDL 2.x 的 `Ldl2Backend`（点光近似），没装 LDL 时退 Noop。
- **方速（fangsu）** 整块不启用（3 个 mixin + plugin 都不带；`FangsuTrafficLightMode` 恒 `NOT_FANGSU`）。
- `mixin.mtr` / `mixin.mtrserver` 的 `defaultRequire = 0`，目标缺失只降级不崩。

**本次没有新增功能降级**：1.19.2 上能等价实现的都等价实现了（tooltip / 创造页签 / 死亡文案 /
腿不动 / 光束编辑器矩阵全部保真）。

## 5. 运行期兼容抽查（javap，不启动游戏）

**MC mixin 目标（`require = 1` 的那条是重点）**

| mixin | 目标 | 1.19.2 | refmap |
|---|---|---|---|
| `beamlight/BeamLightMixin` | `WorldRenderer#getLightmapCoordinates(BlockRenderView, BlockState, BlockPos)I` | ✅ 存在 | `class_761;method_23793(...)` |
| `rain/WorldRendererRainMixin` | `WorldRenderer#renderWeather(LightmapTextureManager;FDDD)V`（**private**） | ✅ 存在（1.19.2 也是 private，同描述符） | `method_22714` |
| `rain/…` 第二条 | `tickRainSplashing(Camera)V` | ✅ 存在 | `method_22713` |
| `collision/MixinEntityMove` | `Entity#move`（`argsOnly index 2` → `Vec3d`） | ✅ `move(MovementType, Vec3d)` | `method_5784` |
| `ante/nte` 亮度 mixin | `World#getLightLevel(LightType, BlockPos)I` | ✅ 存在 | `method_8314` |

**MTR 目标（`remap = false`，按 named jar 核对）**：`RenderPIDS#getSchedules`、
`RenderPSDAPGDoor#render`、`RenderSignalBase#getNodePos` / `#getOccupiedAspect`、
`ClientCache#getResource` / `#getRouteMap` / `#getDirectionArrow`、
`RouteMapGenerator#generateRouteMap/generateColorStrip/generateDirectionArrow`、
`TrainClientRegistry#reset()V` / `#register(String, TrainProperties)`、`BlockPlatform#getActualState`、
`RailwayData#simulateTrains`、`TrainServer#simulateTrain`、`SavedRailBase#isCloseToSavedRail`
—— **全部在 1.19.2 的 named jar 里找到同名同签名**。

**JOML 垫片的数学**：用独立探针把垫片和原版矩阵逐位对照（不启动游戏）：

```
OK   rotation() Rx*Ry*T           （Camera.rotation() 与 vanilla multiply(Quat) 链逐位一致）
OK   toPixels() T*S
OK   T*S * Rx*Ry*T
OK   quaternion orders            （四元数旋转向量 vs Vec3f.rotate，误差 <1e-4）
OK   M * M^-1 == I                （max err 1.19e-7）
OK   M * M^-1(v) == v             （transformDirection 往返精确，轴向拖拽不会偏）
m30=120.0 m31=138.71393 m33=1.0   （m<col><row> 命名与 JOML 一致，m30 = 平移 X）
```

## 6. 验收抽查

```
unzip -l v1.19.2/build/libs/lte-1.0.0.jar | grep -c '\.class$'   -> 273
BeamGlow / ProtoBeamCone / BeamGeom / RainSpray / SprayParticle / SprayType
collision.CollisionSystem / CollisionImpact / TrainImpactDamageSource / signal.SignalLinkItem / SignalLinkStore
xiamen.XiamenPsdBlocks（+ 门/玻璃/端头/顶盖 BE）/ xiamen.PsdAPGItem / RenderPsdDoor / RenderPsdApgDoor
BeamEditor3DScreen / BeamEditScreen / BeamListScreen / BeamVehicleScreen / CategoryScreen /
PackImportScreen / TrainPreview / ParameterScreen / ConfigScreen / CustomPack / BeamPackWriter / TrainNames
clientgui.DrawContext / Matrix4f / Quaternionf / Vector3f / Widgets
mixin.collision.MixinEntityMove / mixin.beamlight.BeamLightMixin / mixin.mtr.* / mixin.rain.* / mixin.mtrserver.*
```

`temp/mixincheck.sh` 按 1.19.2 跑（7 个 mixin 配置）→ **无 MISSING**；`fabric.mod.json` 的 9 个
入口点、`lte.refmap.json` 全部在 jar 内核对通过。

## 7. 边界

- **没有** 修改 `v1.19.4`、`v1.20.1`（纯只读参考），也没有提交 git。
- 新增 `libs/MTR-fabric-1.19.2-3.2.2-hotfix-1-named.jar`（由 `tools/remap-mtr-named.sh` 产出）。
- `common/` 未改（1.19.4 移植时已做的兼容化对它同样适用）。
