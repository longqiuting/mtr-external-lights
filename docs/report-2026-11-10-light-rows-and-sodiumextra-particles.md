# 光源分区行序归位 + Sodium Extra 粒子注入性调查（1.20.1）

日期：2026-11-10 · 目标版本：1.20.1 · 产物：桌面 `lte-1.0.0+1.20.1.jar` + 实例
`PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.0+1.20.1.jar`（三处字节一致）。
**未提交 git，未启动游戏。**

---

## A. 动态光源开关移到光源分区，与光带 / 光锥并排

### 改了什么

`v1.20.1/.../ParameterScreen.java#buildRows()`：把「动态光源 | 车灯亮度」这一行从光源页尾部
（原来排在六个光束数值滑块之后、只剩「光束编辑」按钮跟在后面）上移到「光柱（光锥）」之后。

### 新行序（全局页 → 光源）

| # | 行 | 类型 |
|---|---|---|
| 1 | 总开关 \| 渲染距离 | toggle + slider |
| 2 | 车灯模式：自动/常亮/关闭 | cycle（仅全局页） |
| 3 | **光带 \| 照射长度** | toggle + slider |
| 4 | **光柱（光锥）** | wide toggle |
| 5 | **动态光源 \| 车灯亮度** | toggle + slider |
| 6 | 车头颜色 | swatch |
| 7 | 车尾颜色 | swatch |
| 8–13 | 光束距离 / 车头长度 / 车尾长度 / 光束宽度 / 车头高度 / 车尾高度 | slider |
| 14 | 光束编辑… | action |

三个「灯」开关现在是连续的三行：贴地光带 → 体积光锥 → 真实方块光照。侧线页同一顺序，
只是没有「车灯模式」（它没有侧线覆盖）。

### 证据（编译产物字节码，不是只看源码）

`javap -p -c dev/js/mtrheadlight/ParameterScreen.class`，`buildRows()` 的常量顺序：

```
56: iconst_0 / iconst_0 -> Row.toggle(II)        // MASTER=0, DISTANCE=0
78: Row.mode()                                   // 仅 isGlobal()
91: iconst_2 / iconst_2 -> Row.toggle(II)        // LIGHT_BAND=2, HEAD_LENGTH=2  ← 光带
106: iconst_3 -> Row.toggleWide(I)               // BEAM_CONE=3                ← 光锥
120: iconst_1 / iconst_1 -> Row.toggle(II)       // DYNAMIC_LIGHT=1, LUMINANCE=1 ← 动态光源
135: iconst_1 -> Row.color(Z)                    // 车头颜色
149: iconst_0 -> Row.color(Z)                    // 车尾颜色
163..: Row.value(...)                            // 六个光束数值
```

常量取自 `ConfigWriteTarget`：`DYNAMIC_LIGHT = 1`、`LIGHT_BAND = 2`、`BEAM_CONE = 3`。

行语义没动：动态光源仍带车灯亮度滑块，缺 LambDynamicLights 时仍置灰 + 悬停提示
（`hint.needsLdl`），侧线覆盖、↺ 恢复、`ALL_TOGGLES` 都没改。

---

## B. Sodium Extra 的 `particle_settings.other` 能不能「外部模组注入条目」

### 结论

**没有注入 API，也不需要注入。** Sodium Extra 0.5.9 的粒子页是**遍历运行时粒子注册表**生成的：
任何模组只要用 `Registry.register(Registries.PARTICLE_TYPE, ...)` 注册了自己的 `ParticleType`，
就会自动出现在它的「粒子」页里（按 id 的 **namespace 分组**），并且默认是**开**。
这就是 MrCrayfish 家具那种「自家的粒子被 Sodium Extra 单独控制」的实现方式 —— 它并没有往别人的
配置里塞条目，而是让自己的类型被别人的枚举**扫到**。

### 证据（sodium-extra-0.5.9+mc1.20.1.jar，javap -p -c）

1. 选项入口：`me/flashyreese/mods/sodiumextra/client/gui/SodiumExtraGameOptionPages#particle()`

   ```
   341: getstatic  class_7923.field_41180        // Registries.PARTICLE_TYPE
   344: invokeinterface class_2378.method_10235   // Registry#getIds() -> Set<Identifier>
   349: stream()
   354: invokedynamic #401  apply                 // Identifier::getNamespace
   359: Collectors.groupingBy(Function)           // Map<String namespace, List<Identifier>>
   373: invokedynamic #424  accept(List)          // 每个 namespace -> 一个 OptionGroup
   ```

   每个 id 由 `lambda$particle$24(Identifier)` 建成一个 `OptionImpl<Boolean>`，名字是
   `translatableName(id, "particles")` = `options.particles.<namespace>.<path>`，
   提示是 `options.particles.<namespace>.<path>.tooltip`（缺翻译时退回把 path 的下划线去掉的原文）。

2. 读写绑定：

   ```
   lambda$particle$23(id, options) -> particleSettings.otherMap.getOrDefault(id, TRUE)   // 读
   lambda$particle$22(id, options, v) -> particleSettings.otherMap.put(id, v)            // 写
   ```

3. 真正拦截的地方：`me/flashyreese/mods/sodiumextra/mixin/particle/MixinParticleManager#addParticle`
   （`@Inject(method = "addParticle", at = HEAD, cancellable)`）

   ```
   if (!options.particleSettings.particles) { cir.setReturnValue(null); }        // 总开关
   Identifier id = Registries.PARTICLE_TYPE.getId(effect.getType());             // class_7923.field_41180.method_10221
   if (!options.particleSettings.otherMap.getOrDefault(id, TRUE)) {
       cir.setReturnValue(null);                                                  // 逐类型
   }
   ```

4. 字段本身是公开可写的普通 Map：
   `SodiumExtraGameOptions$ParticleSettings` → `public java.util.Map<net.minecraft.class_2960, java.lang.Boolean> otherMap;`

结论拆开说：

- **能自定义条目吗**：能，但不是「注入」—— 是**注册一个自己的 ParticleType**，它下次打开
   Sodium Extra 粒子页就会被列出来（key = `options.particles.mtrlte.spray`）。
- **默认值**：`getOrDefault(id, TRUE)`。表里没有这个 id ⇒ 放行。所以自己的类型天然可用，
  除非用户自己去把它关掉。
- 本机现状（`versions/1.20.1-Fabric 0.19.3/config/sodium-extra-options.json`）：
  `particle_settings.other` 里 **110 个 `false`**，包括
  `minecraft:cloud / large_smoke / splash / flame` 全是 `false`；
  **没有任何 `mtrlte:*` 键**。这就是旧版水雾（借 `minecraft:cloud`）一颗都看不见的根因，
  也是现在 `mtrlte:spray` 能出来的原因。

### 因此没有做的事

- 没有写 `sodium-extra-options.json`（不硬改别人的配置）。
- 没有反射往 `otherMap` 里塞条目（它本来就会自己列出来，塞进去反而会让用户失去开关权）。

### 顺手做的两件事

1. `assets/mtrlte/lang/{zh_cn,en_us}.json` 增加
   `options.particles.mtrlte.spray` = 「水雾（LTE）」/ "Rain spray (LTE)" 及其 `.tooltip`，
   让 Sodium Extra 的粒子页里这一项显示成人话，而不是 `spray`。
2. 修正 `SprayType` 的类注释：原来写「表里只有它列出来的原版类型」，实际是遍历整个注册表；
   自注册类型的正确表述是「被它枚举到，且默认 TRUE」。

### LTE 自己的开关仍然在粒子分区（兜底）

`ParameterScreen#buildRows()` → `case PARTICLE`：

| # | 行 |
|---|---|
| 1 | **雨天水雾：开/关 \| 水雾起始速度** (`RAIN_SPRAY` + `RAIN_SPRAY_MIN_SPEED`) |
| 2 | 挡雨：开/关 (`RAIN_HUSH`) |

即 `cat.particle`（粒子）分区第一行就是水雾开关，全局页和侧线页都有（两项都可按侧线覆盖）。
不装 / 装了但没在 Sodium Extra 里开这一项时，用户仍可在 LTE 自己的面板里关掉水雾；
两边都能关，任一边关闭都生效（Sodium Extra 那层在 `addParticle` 直接返回 null）。

---

## 构建与产物

```
gradle build --no-daemon   (v1.20.1)
BUILD SUCCESSFUL in 5s
```

sha256（三份一致）：`97b900cea451fdc2ed589f5e97357e0428b5221d76c71dc8c54c2defddeaacdc`
大小：685,658 字节。

- 构建产物：`v1.20.1/build/libs/lte-1.0.0.jar`
- 同一字节的快照：`temp/lte-mine.jar`（防止并行构建 `clean` 把文件删掉）
- 桌面：`C:/Users/AD/Desktop/lte-1.0.0+1.20.1.jar`
- 实例：`C:/Users/AD/Desktop/PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.0+1.20.1.jar`

拷贝前 `tasklist` 无 `javaw` / Minecraft，**未启动游戏**。**未提交 git。**

### 关于产物来源（重要）

这次构建期间，同一工作树上有**另一个会话在并行开发**（`xiamen/` 雨天 PSD 门：`XiamenClient`、
`XiamenObjects`、`XiamenPsdBlock`，以及 `BeamEditor3DScreen` / `LteServer` / `LteCoreItems` /
`fabric.mod.json` 的未提交改动）。因此：

- 交付的 jar 是**工作树当时的快照**，除了本次改动，也含那个会话的未提交代码（它编译通过）；
  这些文件不属于本任务，本任务没有修改它们。
- 曾尝试在 `git archive HEAD` 的隔离副本里只带本次三个文件重建，以得到「HEAD + 本次改动」的
  干净产物；失败原因是并行会话的 JVM 一直独占
  `~/.gradle/caches/fabric-loom/.../minecraft-merged-intermediary-...jar`
  （`FileSystemException: 另一个程序正在使用此文件`），8 次重试 + `--offline` 都不行，故放弃。
- 产物内容已核对：jar 内 `assets/mtrlte/lang/zh_cn.json` 含
  `options.particles.mtrlte.spray`；`javap -c dev/js/mtrheadlight/ParameterScreen.class` 的
  `buildRows()` 顺序为 `toggle(2,2) → toggleWide(3) → toggle(1,1) → color(true)`（即上表 A 节）。

## 改动文件

- `v1.20.1/src/main/java/dev/js/mtrheadlight/ParameterScreen.java`（行序 + 类注释）
- `v1.20.1/src/main/java/dev/js/mtrheadlight/SprayType.java`（注释按实测字节码更正）
- `v1.20.1/src/main/resources/assets/mtrlte/lang/zh_cn.json`、`en_us.json`（Sodium Extra 条目名）
- `docs/report-2026-11-10-light-rows-and-sodiumextra-particles.md`（本文件）

