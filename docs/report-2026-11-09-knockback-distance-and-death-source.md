# 2026-11-09 撞飞距离（可配置）+ 致死伤害源排查（1.20.1 / LTE）

构建：`bash tools/build-all.sh v1.20.1` → **BUILD SUCCESSFUL**
产物：`v1.20.1/build/libs/lte-1.0.0.jar`（513 462 字节，sha256 `6f9185b893a1fa12…`）
→ 桌面 `lte-1.0.0+1.20.1.jar` + 实例
`PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.0+1.20.1.jar`（三处字节一致）。
**未提交 git，未启动游戏。**

---

## A. 撞飞距离：`knockbackDistance`（默认 5.5 格，钳制 1–20）

### 配置

```json
"knockbackDistance": 5.5
```

界面：伤害分区新增一行「撞飞距离 5.5 格（80+ 为 1.5 倍）」，滑块 1–20、步长 0.5，带行内 ↺。
原来那两行「撞飞/飞出水平初速」已从界面移除（字段保留只为旧 JSON 还能读，**不再参与计算**，
`@Deprecated`）。

### 公式（不是「拍一个数」，是反解）

竖直初速先定滞空，再由距离反解水平初速：

```
滞空 n tick ：vy -= 0.08; y += vy; vy *= 0.98          （原版 LivingEntity#travel）
水平位移    ：每 tick 先位移、后衰减 0.91                ⇒ Σ(0.91^k, k=0..n−1)
反解        ：vx = 距离 / Σ(0.91^k)
档位        ：50 ≤ v < 80 → 距离 = knockbackDistance
              v ≥ 80      → 距离 = knockbackDistance × 1.5
```

实现：`CollisionImpact.flightTicks / horizontalReach / knockbackHorizontal / launchDistance`
（`GRAVITY=0.08`、`VERTICAL_DRAG=0.98`、`HORIZONTAL_DRAG=0.91` 全部与被反编译核对过的
`LivingEntity#travel` 一致）。

### 离线积分实测（`node tools/knockback-check.js`）

| 竖直初速 | 滞空 | Σ(0.91^k) | 5.5 格 → vx | 落地实测 | 8.25 格（80+）→ vx | 落地实测 |
|---:|---:|---:|---:|---:|---:|---:|
| 0.25 | 6 t | 4.801 | 1.145 (82 km/h) | **5.50 格** | 1.718 | **8.25 格** |
| **0.45（默认）** | **10 t** | **6.784** | **0.811 (58 km/h)** | **5.50 格** | **1.216** | **8.25 格** |
| 0.60 | 14 t | 8.144 | 0.675 (49 km/h) | **5.50 格** | 1.013 | **8.25 格** |

距离与 vx 线性，所以任意距离都精确落地（积分器与 Java 代码逐行一致）：

```
d=1  → vx 0.147 → 1.00 格      d=10 → vx 1.474 → 10.00 格
d=3  → vx 0.442 → 3.00 格      d=20 → vx 2.948 → 20.00 格
```

### 「为什么以前看着只挪一格」

三个真因，本次都修了：

1. **服务端写的速度到不了玩家本人。** 单人里位置是**客户端权威**的；`ServerPlayerEntity`
   的 `setVelocity` 只随实体跟踪包发给**其他**玩家。新增 `CollisionImpact.ImpulseMirror`
   （`CollisionBootstrap` 装上）：同一组速度回写本进程的 `ClientPlayerEntity`（UUID 相同才写，
   走 `client.execute` 回客户端线程）。多人由服务端速度包负责，hook 为 no-op。
2. **冲量锁只有 8 tick。** 滞空 10–14 tick，8 tick 之后「跟着车走」的速度推送（1.1–1.35 格/tick，
   方向是车的前进方向）把外抛速度压回去。现在锁定 `max(8, 滞空 + 4)` tick。
3. **实例 JSON 里 `trainCollisionLaunchVertical=0.25`**（比默认 0.45 低）—— 滞空只有 6 tick，
   同样的 vx 少飞 30%，且几乎没有抛物线。已改回默认 0.45 / 0.45→0.60（见 D 节）。

诊断行（每次冲量一行，节流由 20 tick 的 `impact:` 行负责）：

```
[lte] knockback: tier=LAUNCH dist=5.50 vx=0.811 vy=0.45 air=10t
```

---

## B. 致死一击 / 死亡屏 / 死亡文案

### 结论先说

**致死那一击确实走的是 `TrainImpactDamageSource`（不是 `generic`）** —— 代码路径一直是
`applyDamage() → sourceFor() → new TrainImpactDamageSource(generic 的 DamageType, high)`，
1.20.1 的 `DamageTracker#getDeathMessage()` 最后一跳是
`damageSource.getDeathMessage(entity)`（已用 javap 反编译确认是 invokevirtual，不是重建源），
所以覆写的文案会被采用。**「日志里没有 death.attack」是因为根本没有死亡发生，而不是源不对**：
之前没有任何一行日志记录过死亡或 damage 的结果，`damage=` 那个数字是「请求打出去多少」，
不是「血掉了多少」。

### 实机日志证据（`.../logs/latest.log`，22:00:16–22:00:46）

```
22:00:16 [Server] [lte] impact: v=60.0 tier=LAUNCH dmg=0.59 pushed=true
22:00:16 [Server] [LTY: 已将自己的心律共鸣为凡人模式]        ← 外部 mod 的「神明/凡人」开关
22:00:21 [Server] [lte] collision(server): trains=20 cars=83 hits=41 damage=5.4
22:00:26 [Server] [lte] collision(server): ... hits=0 damage=5.9
22:00:31 [Server] [lte] collision(server): ... hits=0 damage=5.9
22:00:36 [Server] [lte] collision(server): ... hits=0 damage=5.9
22:00:39 [Server] [LTY: 已将自己的心律共鸣为神明模式]        ← 切成无敌
22:00:41 [Server] [lte] collision(server): ... hits=0 damage=2.9
```

23 秒内「请求伤害」累到 23 hp（一整条血），**没有任何死亡行、聊天栏没有死亡消息**。
中间还有一次 `神明模式`（god mode）切换。这两件事正好对应两个吞伤害的入口：

| 吞伤害的入口 | 原版行为（javap 核对） | 旧代码 | 现在 |
|---|---|---|---|
| `PlayerAbilities#invulnerable`（神明模式） | `PlayerEntity#damage` 第 3 行就 `return false`；`Entity#isInvulnerable()` 看不见它 | 只挡了 creative/spectator/`invulnerable` 标志 → **一直空攒 `applied`** | `damageable()` 也挡 `getAbilities().invulnerable`，并打一行 `damage: skipped - abilities.invulnerable(god)` |
| 别人的无敌帧 | `timeUntilRegen > 10` 且 `amount <= lastDamageTaken` → `return false` | 致死笔也可能被吞（0.6 hp < 上一笔） | **致死笔（`amount >= hp`）先 `timeUntilRegen = 0`** |
| 减伤（保护附魔/抗性/吸收） | `generic` 已 bypass 护甲，但保护附魔仍乘算 | 0.59 hp/窗口被压到 0.2 以下 → 25 s 只掉几滴血 | 新增「必死那一击」：raw 预算花掉一整条血而人还活着 → 补一记 `hp×8+20`（保护 IV 也压不住），只打一次 |

### 新增日志（每次 `damage()` 一行 + 死亡一行）

```
[lte] damage: src=TrainImpactDamageSource want=0.59 hp=20.00->19.79 landed=true iframes=20 reset=false inv=false god=false creative=false at dev.js.mtrheadlight.collision.CollisionImpact.applyDamage(CollisionImpact.java:806)
[lte] death: target=LTY src=TrainImpactDamageSource key=death.attack.mtrlte.trainHigh msg="LTY 随着列车的呼啸声消失了"
```

- `want` = 请求血量；`hp=a->b` = **真实血量变化**（这一项才能证明伤害有没有落地）
- `landed` = `LivingEntity#damage()` 的返回值
- `iframes` / `reset` = 无敌帧计数、这一笔是否清零了它
- `inv` / `god` / `creative` = 三种无敌来源
- `at` = 一层调用栈（谁打的）

### 致死调用链

```
ServerCollision.tick (END_SERVER_TICK, 单人也是这里权威)
  └─ collide(world, ServerPlayerEntity, …)
       ├─ CollisionImpact.flightDamage / hurtDamage / tick     ← 累积（每 10 tick 结算）
       └─ CollisionImpact.applyDamage(target, hp, high)
            ├─ lethal ? target.timeUntilRegen = 0
            ├─ source = new TrainImpactDamageSource(generic 的 DamageType, high)
            ├─ target.damage(source, hp)
            │    └─ LivingEntity#damage → damageTracker.onDamage(source, hp)
            │         → applyDamage → setHealth(0) → onDeath(source)
            │              → DamageTracker#getDeathMessage()
            │                   → damageSource.getDeathMessage(entity)      ← 虚调用到我们
            │                        → Text.translatable("death.attack.mtrlte.trainHigh", 名字)
            └─ logDamage() → [lte] damage: … / [lte] death: …
```

### `/mtr_lte debug killself [low|high]`

用**本模组自己的伤害源**对服务端玩家实例打一记必死伤害（`timeUntilRegen=0`，`hp×8+20`），
把「死亡屏 + 文案」这条链路从撞击条件里彻底解耦出来。

预期输出（单人，生存，无无敌）：

```
聊天栏 / latest.log：
LTE：killself — src=TrainImpactDamageSource key=death.attack.mtrlte.trainHigh hp=20.00->0.00 landed=true alive=false msg="LTY 随着列车的呼啸声消失了"

日志紧跟一行：
[lte] death: target=LTY src=TrainImpactDamageSource key=death.attack.mtrlte.trainHigh msg="LTY 随着列车的呼啸声消失了"
```

- 屏幕：**死亡界面**（「你死了！」+ 那句自定义文案），按重生可复活。
- 如果 `landed=false`：会带 `blocked=`（`creative` / `spectator` / `abilities.invulnerable(god)` /
  `invulnerable-flag`）—— 那就是「死亡屏不弹」的真凶，先关掉那个模式再试。
- 多人（服务器不在本进程）：命令直接说「需要单人存档」，不会假装成功。

---

## C. 改了哪些文件

| 文件 | 改动 |
|---|---|
| `common/…/HeadlightConfig.java` | 新增 `knockbackDistance`（默认 5.5，钳制 1–20）+ clamp + `sanitized()`；两个水平初速字段标 `@Deprecated`（保留只为旧 JSON） |
| `v1.20.1/…/collision/CollisionImpact.java` | 距离→水平初速反解（`flightTicks`/`horizontalReach`/`knockbackHorizontal`/`launchDistance`）；冲量锁改为「滞空+4」；`ImpulseMirror` 客户端镜像；`damageable()` 增加 god mode；致死笔清 `timeUntilRegen`；「必死那一击」兜底；`[lte] damage:` / `[lte] death:` / `[lte] knockback:` 日志；`debugKill()` |
| `v1.20.1/…/collision/TrainImpactDamageSource.java` | 新增 `high()` / `key()` 供日志对照 |
| `v1.20.1/…/collision/CollisionBootstrap.java` | 安装 `ImpulseMirror`（回写本地 `ClientPlayerEntity`） |
| `v1.20.1/…/server/ServerCollision.java` | 飞出阶段死亡文案改用 `wasFast()`（原来硬编码 `true`，低速飞出会显示高速文案） |
| `v1.20.1/…/Commands.java` | `/mtr_lte debug killself [low|high]` |
| `v1.20.1/…/ConfigWriteTarget.java` | 新参数 `COLLISION_KNOCKBACK=31`（全局读写/恢复默认/全部恢复默认；侧线页 ignored） |
| `v1.20.1/…/ParameterScreen.java` | 伤害分区新增「撞飞距离」行（1–20，步长 0.5，带 ↺）；移除两行「水平初速」 |
| `v1.20.1/resources/assets/mtrlte/lang/{zh_cn,en_us}.json` | `opt.knockbackDistance` |
| `tools/knockback-check.js`、`tools/knockback-sim.js` | 离线积分 / 与 Java 逐行一致的校验脚本 |

## D. 实例配置改动（`.../1.20.1-Fabric 0.19.3/config/mtrlte.json`）

原文件备份：`mtrlte.json.bak-20261109-knockback`

```diff
+ "knockbackDistance": 5.5,
- "trainCollisionLaunchVertical": 0.25,
+ "trainCollisionLaunchVertical": 0.45,
- "trainCollisionFlyVertical": 0.45,
+ "trainCollisionFlyVertical": 0.6,
```

竖直初速改回默认：0.25 只有 6 tick 滞空、顶点 0.34 格 —— 那是「被推了一下」，不是撞飞；
0.45 / 0.60 给 10 / 14 tick 滞空、顶点 1.0 / 1.8 格。距离是 `knockbackDistance` 说了算，
改竖直不会改落地距离（反解会自适应）。

## E. 待实机确认（本次未启动游戏）

1. `[lte] knockback: tier=LAUNCH dist=5.50 vx=0.811 vy=0.45 air=10t` 出现，落点约 5–6 格；
2. `[lte] damage:` 每 10 tick 一行，`hp=` 真的在降；
3. 撞死后出现 `[lte] death:`，`src=TrainImpactDamageSource`、`key=death.attack.mtrlte.trainHigh`、
   `msg="… 随着列车的呼啸声消失了"`；
4. `/mtr_lte debug killself` 一行输出 + 死亡界面。
