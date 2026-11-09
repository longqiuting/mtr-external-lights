# 侧线覆盖扩展到光柱 / 粒子 / 伤害

日期：2026-11-09 ｜ 构建目标：`bash tools/build-all.sh v1.20.1` ｜ 产物：`lte-1.0.0+1.20.1.jar`

## 1. 新增的侧线字段（`HeadlightConfig.SidingSettings`）

全部是**可空包装类型**：`null` = 跟随全局；Gson 只写非 null 的字段，所以只改了一项的侧线条目仍然是一行。

| 字段 | 类型 | 含义 | 钳制 | 全局默认（跟随值时） |
|---|---|---|---|---|
| `beamCone` | `Boolean` | 这条侧线的车是否画体积光柱 | — | `beamCone = true` |
| `beamConeAlpha` | `Double` | 光柱 alpha 预算 | 0.02–0.5 | `0.16` |
| `beamConeOcclusion` | `Boolean` | 光柱深度遮挡（1.20.1 raymarch） | — | `true` |
| `beamConeSteps` | `Integer` | 每像素 raymarch 采样数 | 4–16 | `10` |
| `rainSpray` | `Boolean` | 这条侧线的车雨天是否溅水雾 | — | `true` |
| `rainSprayMinSpeedKmh` | `Double` | 水雾起始速度（km/h） | 0–200 | `50` |
| `rainHush` | `Boolean` | 坐这条侧线的车里是否不画雨 | — | `true` |
| `trainCollisionDamage` | `Boolean` | 这条侧线的车撞人是否扣血 | — | `true` |
| `trainCollisionDamageMinSpeedKmh` | `Double` | 伤害起点 `v0`（km/h），低于它只推开 | 0–300 | `trainCollisionPushOnlyMaxKmh = 5` |
| `trainCollisionDamageMax` | `Double` | 一次接触的累计伤害上限（hp） | 0–200 | `trainCollisionDamageMax = 0`（= 只受实体最大生命限制） |

对应的只读快照 `Effective` 新增同名字段（已钳制），由 `effectiveFor(train.sidingId)` 一次性折叠。

### 新加的全局字段

`HeadlightConfig.trainCollisionDamageMax`（`double`，默认 `0`）：一次接触的累计伤害上限，`0` = 不额外设限。
0.1.x 那个同名旧键是「单次线性伤害上限」，早已在 v12 删除；v13 以更窄的含义重新引入，所以老文件里
残留的 `trainCollisionDamageMax: 12` 现在读作「本次接触最多 12 hp」，行为不会更糟。

`CONFIG_VERSION` 12 → 13：只为把新键写进已有文件（Gson 对缺失字段用初始值，无需逐字段迁移）。

## 2. 侧线覆盖的 JSON 形态

只写改过的字段：

```json
"sidingSettings": {
  "42": {
    "beamCone": false,
    "beamConeAlpha": 0.3,
    "beamConeSteps": 6,
    "rainSpray": false,
    "rainSprayMinSpeedKmh": 150.0,
    "rainHush": false,
    "trainCollisionDamage": false,
    "trainCollisionDamageMinSpeedKmh": 250.0,
    "trainCollisionDamageMax": 7.5
  }
}
```

读回时 `sanitized()` 与 `effectiveFor()` 各钳一次（0.3 保留、99 → 16、0.9 → 0.5）。

## 3. 消费方（都按 `Train.sidingId`，不看玩家位置）

| 位置 | 用法 |
|---|---|
| `common/ProtoBeamCone.render` | 逐车 `eff = cfg.effectiveFor(train.sidingId)`：`beamCone` 关掉的车整列跳过；`beamConeAlpha` 逐车峰值；`beamConeSteps` 逐束（`ConeVolumeBeam.steps`）；`beamConeOcclusion` 为 false 的车这一帧退回加色壳层锥，其余车仍走 raymarch（两条路同帧并存） |
| `v1.20.1/ConeVolumeRenderer` | `ready()/status()` 改成「全局开 **或** 任一侧线开」才放弃深度图；`render()` 用每束的 `steps` 作 uniform |
| `v1.20.1/RainSpray` | 逐车 `eff`：`rainSpray` 关掉记 `reason=off`，`rainSprayMinSpeedKmh` 作速度闸门 |
| `v1.20.1/RainHush` | `computeInside` 逐车 `eff.rainHush`：关掉的那条侧线的车不算「车内」 |
| `v1.20.1/CollisionImpact` | `tick(entity, cfg, eff, …)` 用 `eff` 判档位 / 伤害；撞击时把 `damageOn / damageMax / v0` 快照进 `State`，飞行阶段沿用 |
| `v1.20.1/CollisionSystem` | `CarHit.sidingId`（来自 `Train.sidingId`），推出命中时取「撞上那列车」的侧线解析 `eff` |
| `v1.20.1/CarGeometry.Car` / `ServerCollision` / `MobCollision` | 服务端车厢带 `sidingId`；端部延长与伤害同样走 `effectiveFor`（服务端列车也有 `sidingId`） |

「全局关、某条侧线开」的情况由 `beamConeAnySiding()` / `beamConeOcclusionAnySiding()` /
`rainSprayAnySiding()` / `rainHushAnySiding()` 在帧级放行，逐车再按 `Effective` 过滤 —— 否则提前
`return` 会让那条侧线的覆盖永远画不出来 / 永远不生效。

## 4. 语义：伤害关掉仍然推开 / 撞飞

`trainCollisionDamage = false` **只关扣血**。实现位置：

- `v1.20.1/collision/CollisionImpact.java:251` 撞击时把开关快照进 `State.damageOn`；
- `CollisionImpact.java:260` `impulse(...)` 在伤害之前、且**不看** `damageOn` —— 高速档照旧撞飞；
- `CollisionImpact.java:327` `damage()` 第一行 `if (!s.damageOn …) return 0` —— 这是唯一被关掉的东西；
- 车体推出本身在 `CollisionSystem.pushCar`（`:1076`）/ `ServerCollision` 的 `CarGeometry.applyPush`，
  与伤害开关无关。

所以关掉伤害时人仍然被推开、被撞飞，不会出现「车直接穿人」。

## 5. 界面

- `ConfigCategory`：`PARTICLE` / `DAMAGE` 的 `hasSidingRows` 改为 `true`，侧线分区列表现在是
  光源 / 碰撞 / 粒子 / 伤害（其他仍然不生成）。
- `ParameterScreen.buildRows()`：
  - 光源页的「光柱」开关不再限定全局（侧线可覆盖）；
  - 粒子页三项（水雾开关 + 起始速度同一行、挡雨）全局页与侧线页共用；
  - 伤害页：伤害开关 / 伤害起点速度 / 伤害上限在两侧都生成；分档、指数系数、冲量、冷却、生物
    仍然只在全局页。
- `ConfigWriteTarget`：新增 `DAMAGE_MIN_SPEED`(29) / `DAMAGE_MAX`(30)；`BEAM_CONE`、`RAIN_SPRAY`、
  `TRAIN_DAMAGE`、`RAIN_HUSH` 从「侧线忽略」改为读写侧线字段，`RAIN_SPRAY_MIN_SPEED` 同理；
  `SidingTarget` 的 read/write/follows/reset 六条路径都补齐（`↺` 仍是「删掉这一项覆盖」）。
- 新标签 `opt.damageMax`（zh/en）。`beamConeAlpha` / `beamConeOcclusion` / `beamConeSteps` 没有界面行，
  只能在 `mtrlte.json` 的侧线条目里写（全局页也没有这三行，和改动前一致）。

## 6. 验证

- `bash tools/build-all.sh v1.20.1` → **BUILD SUCCESSFUL**，jar 已复制到桌面与 `temp/mcrun/mods`。
- 离线 jshell（jar + gson + fabric-loader）跑 `HeadlightConfig`：
  - 侧线条目：`cone=false alpha=0.5(0.9 钳制) occl=true(跟随) steps=16(99 钳制) spray=false minKmh=150 hush=false dmg=false v0=250 cap=7.5 overridden=true`；
  - `effectiveFor(0)`：`cone=true alpha=0.16 steps=10 minKmh=50 cap=0.0 dmg=true overridden=false`；
  - `toJson()` 只写设置过的键，`fromJson()` 读回后同样钳制；
  - `anySiding` 系列在「未覆盖（null）的侧线条目」存在时不抛 NPE（第一次实现用 `Predicate` 会拆箱
    崩，已改成 `Function<SidingSettings, Boolean>`）。
