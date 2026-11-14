# 1.20.1 → 1.19.2 功能移植报告（2.0.0）

**结果**：`gradle -p v1.19.2 clean build` → **BUILD SUCCESSFUL**；
产物 `v1.19.2/build/libs/lte-2.0.0.jar`（**15 819 799 B / 472 个 class**）。

**功能对等性校验**（不是「看起来差不多」，是逐条比对 jar）：

| 检查 | 结果 |
|---|---|
| 1.20.1 的 `lte-2.0.0.jar` 里 458 个 class | 1.19.2 里**一个不缺**（`comm -23` 空集） |
| 1.19.2 多出来的 class | 只有 14 个 1.19.2 专用垫片（见 §2） |
| 非 class 条目（assets / lang / mixin 配置 / 粒子 / 模型 / 贴图） | 两边一致；只差本次补上的 `META-INF` 合规文件 |
| 6 个 mixin 配置里点名的类 | 全部在 jar 里 |
| 硬 `@Mixin(targets)` | 只剩 3 个「运行期才有」的软目标（方速 ×2、天津地铁 ×1），与本工程其它版本一致 |

源码规模：`v1.19.2` **246 文件**（1.20.1 为 235：多出的 11 个 = `clientgui/` 垫片 5 +
`clientgui/RotationAxis` + `compat/` 5），`common/` 56 文件未改，`v1.20.1` 未改。

---

## 1. 搬了什么

| 类别 | 做法 |
|---|---|
| Java 源码 | `v1.20.1/src/main/java` **整树**复制（235 文件），再按 §3 逐项适配；保留 1.19.2 自己的 `clientgui/` 垫片层 |
| 资源 | `v1.20.1/src/main/resources` 整树复制（1385 文件） |
| `fabric.mod.json` | 用 1.20.1 的那份，只改依赖段：`minecraft: ~1.19.2`、`fabricloader: >=0.14.0`；入口点 / mixin 列表 / 描述原样（17 个 client 入口点全部有对应类） |
| `build.gradle` | MTR 仍用 1.19.2 自己的 named jar；补 1.20.1 的 `processResources` 许可段（`LICENSE` / `NOTICE.md` / `LICENSES` 进 `META-INF`）；删掉 LambDynamicLights 编译依赖（2.0.0 已彻底移除 LDL） |
| `gradle.properties` | `mod_version=2.0.0`（本来就已改好） |

**顺手清掉的旧版残留**（这些是 1.19.2 里上一代功能留下的孤儿，1.20.1 早已删除同款链路，
它们正是旧版编译报错的来源）：

- `mixin/nte/NteBrightnessMixin`、`NteInstancedBrightnessMixin`（引用已删的 `RailLightInject`）；
- `mtrlte.ante.mixins.json`、`mtrlte.nte.mixins.json`（点名的 `mixin.ante.*` / `MixinPlugin` 根本不存在）；
- `ConeVolumeRenderer`、`Ldl2Backend`（体积雾 / LDL 后端，1.20.1 已删）。

---

## 2. 新增的 1.19.2 专用垫片（`dev.js.mtrheadlight.compat` / `clientgui`）

| 类 | 替的是什么 | 为什么必须自己写 |
|---|---|---|
| `clientgui/DrawContext` | 1.20.1 的 `net.minecraft.client.gui.DrawContext` | 1.19.2 只有 `MatrixStack` + `DrawableHelper`。本次给垫片补了 `drawBorder(..)`（1.19.2 那个是 `protected static`，用 4 条 `fill` 拼） |
| `clientgui/Matrix4f`、`Vector3f`、`Quaternionf` | `org.joml.*` | 1.19.2 的原版**没有 JOML**（1.19.3 才进依赖树）。列主序 `float[16]` 的本地实现，`toMc()` 导出成原版矩阵 |
| `clientgui/RotationAxis`（**本次新增**） | `net.minecraft.util.math.RotationAxis` | 1.19.3 才有的类。`POSITIVE_X.rotationDegrees(d)` → `new Quaternion(Vec3f 轴, d, true)`，让 `matrices.multiply(..)` 在 1.19.2 直接可用 |
| `clientgui/Widgets` | `ButtonWidget.builder(..)` + `ClickableWidget#setTooltip(Tooltip)` | 1.19.2 只有 7 参构造器，tooltip 是建按钮时给的 `TooltipSupplier` |
| `compat/ItemGroupTabs`（**新增**） | `FabricItemGroup.builder()` + `Registries.ITEM_GROUP` | 1.19.2 的 fabric-api 没有 item-group-api-v1，页签走 `FabricItemGroupBuilder` 且不是注册表条目 |
| `compat/ItemGroupEvents`（**新增**） | `ItemGroupEvents.modifyEntriesEvent(RegistryKey)` + `ItemGroup.Entries` | 老线只有 `appendItems(Consumer<List<ItemStack>>)`，没有「按 id 追加」。这里做登记表：按注册顺序在填创造栏时执行，`Entries.getDisplayStacks()` 返回**同一个可变列表**，所以 `pinToolsTabOrder` / `pinCoreTabOrder` 的「摘出来插回 0」算法原样可用（并用 WeakHashMap 防止列表复用时重复追加） |
| `compat/Matrices`（**新增**） | joml `Matrix4f` 的 16-float 列主序构造器 + `mXY()` 元素访问器 | 1.19.2 的 `Matrix4f` 字段全私有：`fromColumns(..)` 走 `readColumnMajor`，`columnMajor(..)` 走 `writeColumnMajor` |
| `compat/Pos`（**新增**） | `BlockPos.ofFloored(double,double,double)` | 1.19.3 才加 |
| `compat/Stacks`（**新增**） | `ItemStack#copyWithCount(int)` | 1.19.3 才加 |

---

## 3. API 适配清单

### 3.1 注册表 / 创造页签
| 1.20.1 | 1.19.2 | 面 |
|---|---|---|
| `net.minecraft.registry.{Registries,Registry,RegistryKey,entry.RegistryEntry,tag.*}` | `net.minecraft.util.registry.*` / `net.minecraft.tag.*` | 90 处 import |
| `Registries.ITEM/BLOCK/BLOCK_ENTITY_TYPE/POTION/PARTICLE_TYPE/SOUND_EVENT/ENTITY_TYPE` | `Registry.*`（1.19.2 没有 `Registries` 类） | 117 处 |
| `Registries.ITEM_GROUP` + `FabricItemGroup.builder().displayName(..).icon(..)` | `ItemGroupTabs.create(id, icon)` | 4 个页签 |
| `ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, id))` | `ItemGroupEvents.modifyEntriesEvent(id)`（compat 垫片） | 18 个模块、43 条 `entries.add(..)` |
| 页签显示名 `Text.translatable("itemGroup.mtrlte.*")` | 翻译键 `itemGroup.<ns>.<path>` 自动解析 | 4 个键 lang 里都有，**显示名无差异** |

### 3.2 屏幕 / 输入
| 1.20.1 | 1.19.2 | 面 |
|---|---|---|
| `render(DrawContext context, int, int, float)` | `render(MatrixStack matrices, int, int, float)` + 首行 `final DrawContext context = new DrawContext(matrices);` | **17 个屏幕** |
| `renderBackground(context)` / `super.render(context, ..)` | `renderBackground(matrices)` / `super.render(matrices, ..)` | 2 处 |
| `ButtonWidget.builder(label, action)` | `Widgets.button(label, action)` | 59 处 |
| `widget.setTooltip(Tooltip.of(text))` | 建按钮时 `Widgets.Builder.tooltip(text)`（`ConfigScreen` / `ParameterScreen` 各加一个带 tooltip 的 `addButton` 重载） | 2 处（第 3 处在 §4 降级） |
| `TextFieldWidget.setPlaceholder(..)` | 删除该调用（构造器里的提示文本本来就是兜底） | 1 处 |
| `ClickableWidget.setFocused(..)`（public） | `TextFieldWidget.setTextFieldFocused(..)` | 4 处 |
| `button.getX()/getY()` | `button.x` / `button.y`（1.19.2 是 public 字段） | 4 处 |
| `TextRenderer.draw(.., TextLayerType.NORMAL, ..)` | `draw(.., false /* seeThrough */, ..)` | 1 处 |
| `new PackScreen(manager, ..)` | `new PackScreen(parent, manager, ..)`（1.19.2 多一个父屏参数） | 1 处 |
| `GameOptions.refreshResourcePacks(manager)` | `client.reloadResources()` | 1 处 |

### 3.3 方块 / 物品 / 实体
| 1.20.1 | 1.19.2 | 面 |
|---|---|---|
| `AbstractBlock.Settings.create()` | `AbstractBlock.Settings.of(Material.X)`（玻璃 → `GLASS`，屏蔽门/器械/标识/装饰件 → `METAL`，石材 → `STONE`） | 24 处 / 21 文件 |
| `ItemPlacementContext#getHorizontalPlayerFacing()` | `getPlayerFacing()`（1.19.2 继承自 `ItemUsageContext`） | 26 处 |
| `BlockState#isReplaceable()` | `state.getMaterial().isReplaceable()` | 7 处 |
| `SoundEvents.X.value()`（`RegistryEntry`） | `SoundEvents.X`（1.19.2 直接是 `SoundEvent`） | 2 处 |
| `SoundEvent.of(id)` | `new SoundEvent(id)` | 3 处 |
| `world.scheduleBlockTick(pos, block, n)` | `world.createAndScheduleBlockTick(..)`（`WorldAccess` 的默认方法） | 3 处 |
| `ItemStack#copyWithCount(n)` | `compat.Stacks.copyWithCount(stack, n)` | 2 处 |
| `BlockPos.ofFloored(x, y, z)` | `compat.Pos.ofFloored(x, y, z)` | 3 处 |
| `serverPlayer.getServerWorld()` | `getWorld()`（`ServerPlayerEntity#getWorld` 就返回 `ServerWorld`） | 2 处 |

### 3.4 伤害 / 动画 / 文本
| 1.20.1 | 1.19.2 | 面 |
|---|---|---|
| `RegistryEntry<DamageType>` + `DamageSources#generic()/fall()/thrown(..)` | 1.19.2 **没有 `DamageType` 注册表**：`TrainImpactDamageSource` 改为 `super("mtrlte.trainImpact")`，死亡文案仍由 `getDeathMessage` 覆写（效果与 1.20.1 传 generic 一致）；`fall()` → `DamageSource.FALL`，`thrown(this, owner)` → `DamageSource.thrownProjectile(this, owner)` | 3 处 |
| `player.limbAnimator.updateLimbs(0, 1)` | `limbDistance` / `lastLimbDistance` 直接写 0（1.19.2 没有 `LimbAnimator`） | 1 处 |
| `TranslatableTextContent#getFallback()` | 取不到翻译直接用 key | 1 处 |
| `ResourcePack#openRoot(String...)` + `InputSupplier` | `openRoot(String)` 直接给 `InputStream`（路径段重新拼回一条相对路径）；顺手删掉从未被调用的 `BeamPackWriter#readAll(InputSupplier)` | 2 处 |

### 3.5 GL / 着色器（屏幕空间散射 pass）
| 1.20.1 | 1.19.2 |
|---|---|
| `net.minecraft.client.gl.ShaderProgram` | `net.minecraft.client.render.Shader`（**同一个类在 1.19.2 的包名**，构造器签名 `(ResourceFactory, String, VertexFormat)` 完全一致，所以自写的那层资源 id 重写照样生效） |
| `GameRenderer::getPositionColorProgram` / `getPositionTexColorProgram` | `::getPositionColorShader` / `::getPositionTexColorShader` |
| `BufferRenderer.drawWithGlobalProgram(built)` | `BufferRenderer.drawWithShader(built)`（1.19.2 的 `drawWithShader` 才会上传 ModelView/ProjMat，正是我们需要的） |
| `Shader#getGlRef()` | `Shader#getProgramRef()` |
| `GlUniform.set(org.joml.Matrix4f)` | `GlUniform.set(net.minecraft.util.math.Matrix4f)`（校验矩阵导出成原版类型） |
| `m.set(a).mul(b)` / `.invert()` / `.identity()` / `mXY()` | `m.load(a); m.multiply(b)` / `invert()`（返回 boolean、原地改）/ `loadIdentity()` / `Matrices.columnMajor(m)` |
| `new Matrix4f().rotateX(..).rotateY(..)`（视图矩阵兜底） | `MatrixStack.multiply(RotationAxis.…)` 两句，右乘语义与原链逐位相同 |

散射 pass 的 json 只声明 `matrix4x4` / `float` / `int` 三种 uniform（1.19.2 全支持），
三个灯阵仍按原样用 `glUniform4fv` 手工上传，因此这条链路在 1.19.2 上**不需要降级**。

### 3.6 MTR 3.2.2 与 3.6.4.2 的差（1.19.2 侧是 3.2.2-hotfix-1）
| 1.20.1（MTR 3.6） | 1.19.2（MTR 3.2.2） | 处理 |
|---|---|---|
| `Siding#getTrainCars()` | 没有 | 返回 0 → **降级**（侧线列表显示「6 节」这类信息不再有） |
| `RailwayData#getRailsMap()` | 没有；表在 `RailwayDataModuleBase#rails`（`protected final`） | 反射两步兜底：先试 `getRailsMap()`，失败再取模块父类的 `rails` 字段（两版都拿不到就按「没有轨道」处理，不会崩） |
| `TileEntityPSDAPGDoorBase#getOpenValue()` | 只有 `getOpen(float partialTick)`（返回 0..1） | `Math.round(getOpen(1.0F) * MAX_OPEN_VALUE)` |
| `mtr.mappings.Utilities.createAndScheduleBlockTick(..)` | `Utilities.scheduleBlockTick(..)` | 改名 |

---

## 4. 降级清单（无法等价实现，逐条列出）

1. **侧线列表的「列车节数」**：MTR 3.2.2 的 `Siding` 没有 `getTrainCars()`，恒为 0
   （`SidingInfo.cars()` 保留同一个入口，将来 3.2.x 补了这个 getter 只需改这一处）。
2. **厦门 PIDS 广告分类勾选框的悬停说明**：1.19.2 的 `ClickableWidget` 没有
   `setTooltip(Tooltip)`，MTR 3.2.2 的 `WidgetBetterCheckbox` 也没有可挂说明的构造参数。
   勾选/联动行为完全不变，只是没有那条 300ms 后才出现的说明文字。
3. **撞击死亡的 DamageType**：1.19.2 没有动态 `DamageType` 注册表，改用
   `DamageSource("mtrlte.trainImpact")` + `getDeathMessage` 覆写。**两段死亡文案
   （`death.attack.mtrlte.trainLow/trainHigh`）、照常走语言文件**，玩家可见结果一致；
   差别只在「按哪个原版类型算抗性」（1.20.1 走 generic，这里走无名类型，1.19.2 里
   无名源不享受任何原版减伤，等于必中）。
4. **页签显示名**：1.19.2 的 `FabricItemGroupBuilder` 没有 `displayName(Text)`，名字只能由
   `itemGroup.<namespace>.<path>` 翻译键决定；4 个页签的键与 1.20.1 用的**完全相同**
   （`itemGroup.mtrlte.{tools,core,rcc_platform,nanputuo_decor}`），实际显示无差异 —— 这一条
   列出来只是说明「为什么代码里少了 `.displayName(..)`」。
5. **站车顶时腿不动**：1.19.2 没有 `LimbAnimator`，改为直接把 `limbDistance` /
   `lastLimbDistance` 清零 —— 渲染看到的效果相同（腿并拢），离开车顶后原版 tick 自己算回。
6. **资源包重载**：1.19.2 没有 `GameOptions#refreshResourcePacks(manager)`，改为
   `client.reloadResources()`（重载整份资源）；广告表 / 标语表仍然在回调里立刻失效，行为等价。
7. **NTE / ANTE 铁轨亮度 mixin**：1.20.1 已删掉整条铁轨发光链路，1.19.2 侧同步删除
   （`mixin/nte/*`、`mtrlte.ante/nte.mixins.json`）。这不是降级 —— 是 2.0.0 的功能决策。

---

## 5. 构建与产物

```
JAVA_HOME="C:/Program Files/Java/jdk-21.0.10" \
  /d/MC-MTR-JS/temp/gradle/gradle-8.14/bin/gradle -p v1.19.2 clean build --no-daemon
→ BUILD SUCCESSFUL in 22s
→ v1.19.2/build/libs/lte-2.0.0.jar        (15 819 799 B, 472 class)
→ v1.19.2/build/libs/lte-2.0.0-sources.jar
```

jar 里带齐：`fabric.mod.json`（`version=2.0.0`、`minecraft ~1.19.2`、17 个 client 入口点、
6 个 mixin 配置）、`lte.refmap.json`、`icon.png`、`assets/mtrlte/**`（blockstates / models /
textures / lang / particles / shaders / 内置 `mtrsteamloco` 轨道包）、
`META-INF/{LICENSE-mtrlte, NOTICE-mtrlte.md, LICENSES/*}`。
