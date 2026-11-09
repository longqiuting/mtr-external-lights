# 水雾改用自注册粒子 `mtrlte:spray`（绕过 Sodium Extra 逐类型屏蔽）

日期：2026-11-09 · 目标版本：1.20.1 · 产物：桌面 `lte-1.0.0+1.20.1.jar` + 实例 `PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/`

## 问题

Sodium Extra 0.5.9 在 `ParticleManager#addParticle` 的 HEAD 上按**粒子类型 id** 逐类屏蔽。
`flame / cloud / large_smoke / splash` 都是它设置界面里的已知类型，用户关掉之后
`force=true`、`ParticlesMode.MINIMAL`、距离全都救不了 —— 粒子在进 `ParticleManager` 的第一道门
就被换成 `null`，屏幕上永远不出现，而我们的计数器照样 `spawned>0`。

## 依据（本机 sodium-extra-0.5.9 jar 的字节码，`javap -p -c`）

```
MixinParticleManager.addParticle(class_2394 effect, ...):
  getstatic class_7923.field_41180          // Registries.PARTICLE_TYPE
  invokeinterface class_2394.method_10295   // effect.getType()
  invokeinterface class_2378.method_10221   // Registries.PARTICLE_TYPE.getId(...)
  getfield SodiumExtraGameOptions$ParticleSettings.otherMap
  iconst_1 / Boolean.valueOf(true)
  invokeinterface java/util/Map.getOrDefault(Object, Object)
  → booleanValue() == false  ⇒  setReturnValue(null)   // 粒子根本没进粒子系统
```

`ParticleSettings.otherMap` 的声明类型是 `Map<Identifier, Boolean>`，默认值是空的
`Object2BooleanArrayMap`。所以：

* **表里没有的 id → `getOrDefault(id, TRUE)` → 放行。** `mtrlte:spray` 不在表里，用户把所有原版
  粒子开关关掉也屏蔽不到它。这就是「注册自己的类型」这条路的全部依据。
* 顺带修掉了一个旧 bug：`ParticleCull` 之前用 **String** 当键查这张 `Map<Identifier, Boolean>`，
  永远命中不了，`culled=` 一直显示 `none`。现在按 `Identifier` 查（字符串键作为后备）。

## 实现

| 文件 | 作用 |
| --- | --- |
| `v1.20.1/.../SprayParticle.java` | 新粒子类，`extends SpriteBillboardParticle` |
| `v1.20.1/.../SprayType.java` | 注册 `mtrlte:spray`（`Registry.register(Registries.PARTICLE_TYPE, ...)` + `alwaysShow=true`）与工厂（`ParticleFactoryRegistry`，PendingParticleFactory 拿到 sprite provider）；裁决 self / vanilla |
| `v1.20.1/.../RenderHooks.java` | 客户端入口里调一次 `SprayType.init()`（不需要改 `fabric.mod.json`） |
| `v1.20.1/.../RainSpray.java` | 水雾改发 `SprayType.effect()`；诊断行加 `sprayType=self\|vanilla`；去掉 `LARGE_SMOKE` 雾体（改为最小档 4 颗重叠水雾） |
| `common/.../ParticleCull.java` | 加 `blocked(id)`；按 `Identifier` 查表；`USED` 加 `mtrlte:spray` |
| `assets/mtrlte/particles/spray.json` | `{"textures":["mtrlte:spray"]}` |
| `assets/mtrlte/textures/particle/spray.png` | 从 1.20.1 客户端 jar 抽的 `assets/minecraft/textures/particle/flash.png`（32×32 白色径向渐变，935 个半透明像素 / 89 个全透明，sha256 一致） |

### 粒子参数（`SprayParticle`）

| 项 | 值 |
| --- | --- |
| 寿命 | 30–50 tick（每颗随机） |
| 竖直初速 | 0.01–0.03 blocks/tick 向上（传入值被夹到该区间） |
| 重力 | `gravityStrength = 0` |
| 阻尼 | `velocityMultiplier = 0.90` |
| 尺寸 | 0.55–1.05 起，线性缩到起点的 45% |
| 透明度 | 0.62–0.86 起，按 `1 - t²` 衰减到 0 |
| 颜色 | 白蓝 tint `(0.82, 0.90, 1.00)`，与贴图相乘 |
| sheet | `PARTICLE_SHEET_TRANSLUCENT` |

贴图为什么不用 `generic_0`：它是 8×8 画布上**唯一 1 个白色 texel**（原版 CLOUD 的贴图），放大后是
一个硬边小方块；`flash.png` 才是原版唯一自带柔和 alpha 衰减的粒子贴图，做雾团不用额外画图。

## 回退

`SprayType` 在**第一次真正要发粒子时**（客户端 tick，所有 initializer 都跑完）判一次：

1. 注册 / 工厂注册抛异常 → `sprayType=vanilla`；
2. `ParticleCull.blocked("mtrlte:spray")` 为 true（未来 Sodium Extra 把默认值改成 FALSE，或用户手工
   写了这条） → `sprayType=vanilla`；
3. 否则 `sprayType=self`。

日志：启动时 `[lte] spray particle type=mtrlte:spray registered=true (...)`，首次生成时
`[lte] spray: using mtrlte:spray (...)` 或 `[lte] spray: falling back to minecraft:cloud (...)`。
每 5 秒的诊断行：

```
[lte] spray: trains=1 spawned=12 skipped=3 raining=true weather=true grad=0.85 particles=ALL sprayType=self culled=none
```

## 验证（未开游戏）

* `./tools/build-all.sh v1.20.1` → **BUILD SUCCESSFUL**（只有 3 条既有的 mixin target 警告 + 1 条
  `DynamicLightsInitializer` 过时警告）。
* jar 内确认：`assets/mtrlte/particles/spray.json`、`assets/mtrlte/textures/particle/spray.png`
  （32×32，935 soft / 89 clear）、`dev/js/mtrheadlight/SprayParticle.class`、`SprayType.class`。
* remap 已生效（`javap` 看到 `class_2400` / `class_7923.field_41180` / `class_4003` 等 intermediary 名）。
* `fabric.mod.json` 未改动（仍用已有的 `dev.js.mtrheadlight.MtrHeadlightClient` 客户端入口）。
* 数值校验（`javap -c BillboardParticle`）：`getSize()` 直接返回 `scale`、顶点色用 `red/green/blue/alpha`
  —— 收缩与淡出都会真正生效；`Particle.tick()` 里 `velocityY -= 0.04 * gravityStrength`，重力 0
  即不加速下落。
