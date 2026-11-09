# 2026-11-09 碰撞手感（速度推送）+ 水雾探针 报告（1.20.1 / LTE）

构建：`bash tools/build-all.sh v1.20.1` → **BUILD SUCCESSFUL**
产物：`v1.20.1/build/libs/lte-1.0.0.jar`（493 551 字节，sha256 `a3b42e7f53c9a950…`）
→ 桌面 `lte-1.0.0+1.20.1.jar` + 实例 `PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/`
（桌面 / 实例 / 测试目录三处字节一致）。**未提交 git。**

> 说明：同一个工作树里还有其它并行改动（`signal/` 包、`BeamGeom/BeamGlow`），jar 里包含它们。
> 本次顺手修了 `signal.SignalLinkClient` 的 `private` 构造器 —— Fabric entrypoint 用反射 new，
> 客户端一启动就 `IllegalAccessException` 崩在 `Initializing game`（实机第一次启动就撞上了）。

---

## A. 碰撞手感

### 三个真因（都不是「参数没调好」）

1. **推出失败就放弃**。`applyPush` 里 `world.isSpaceEmpty(...)` 不成立直接 `return false` →
   被站台边缘/墙夹住时位置一格不动、`pushCar` 返回 false、于是<b>连伤害都不结算</b>。
   玩家看到的「卡一会儿然后列车穿过去」就是这一条。
2. **只推位置、不给速度**。位置修正发生在 `Entity#move` 之后，下一 tick 原版又从旧速度开始算，
   人是被"挪"开的，不是被"推"开的。
3. **正面撞击被当成侧面**。车身段只推法向（永远垂直于轨道）：车头正面撞来时横向穿透（≈1.5 格）
   总是小于轴向穿透（整段半长），于是每次都被往侧面挤 —— 就是「被挤到一旁」。
4. **58–80 扣血偏慢的真因是"接触时间"，不是公式**。80 km/h 的车掠过 3 米只要 0.15 s，
   伤害只在接触的 tick 结算，一次撞击总共 2–3 hp，人永远死不了（死亡屏自然没机会出现）。

### 改法

**速度推送（每 tick 写一次 `velocity` + `velocityModified`）**

```
车速 v_bpt = km/h ÷ 72
正面（车朝人开来，见下面的判定）：
    v_水平 = 运动方向 · min(v_bpt, 1.35)                          // 被车头顶着走
侧面（车从身边掠过）：
    v_水平 = 运动方向 · v_bpt · 0.45 + 车体法向 · clamp(v_bpt·0.55, 0.12, 1.35)
    v_y 保持原值（撞飞冲量的 0.25/0.45 不被覆盖）
撞飞冲量刚写进来的 8 tick 内不做速度推送（否则 1.8 格/tick 的"飞出去"会被压回 1.1）
正面判定： ahead = (玩家 − 车厢中心)·运动方向 > 0  且  penU < penN · 1.5
运动方向取自「上一 tick 车厢中心差」，拿不到才退回 -u（MTR 的 a 端是行进前端）
```

**位置修正不再放弃**：整段推得出去就推 `pen`；推不出去就二分 4 次取「最大可行距离」；
一格都推不动就只留下速度与伤害。`pushCar` 的返回值改成「撞上了」而不再是「位置动了」。
`CarGeometry`（服务端/生物那条路）同样加了 `contact()`（只算几何、不查 `isSpaceEmpty`）
和二分回退。

**冲量与伤害**：`CollisionImpact.impulse()` 加计数器，诊断行新增
`impulses=`（这 5 秒施加了几次冲量）、`vel=`（速度推送次数）、`hurt=`（持续掉血扣了多少 hp）。
=`pushed`/`hits` 现在是「接触数」，不再要求位置推成功。

**死亡消息**：致死那一击常常发生在车早就开走之后（持续掉血窗口），此时拿不到 `hitSpeed`。
新增 `CollisionImpact.wasFast(uuid)`，用<b>撞击时</b>记下的车速快照决定走
`death.attack.mtrlte.trainHigh`（≥50）还是 `trainLow`，`CollisionSystem` / `ServerCollision` /
`MobCollision` 三条路都改成用它 —— 之前 `hurt`/`flight` 硬编码 `high=true`，
低速撞死会显示高速文案。

### 伤害：新增「撞击后持续掉血窗口」（这是 15–25 秒能成立的前提）

```
trainCollisionHurtTicks 默认 500 tick (25 s)
一次能扣血的撞击（v ≥ v0）→ hurtUntil = now + 500
窗口内不碰车也每 tick 按撞击车速继续扣（CollisionImpact.hurtDamage），直到死亡或窗口结束
一 tick 只结算一次（State.lastApplied 防 contact/hurt/flight 各扣一遍）
累计上限仍是实体最大生命（一次接触/一次飞出最多 20 hp）
```

公式（`damagePerSecond`，未改结构，只重调默认值）与实测表：

```
hp/s   = base · exp(k · (v − v0) / scale) · (v ≥ 80 ? flyScale : 1)
       = 0.56 · exp(1.35 · (v − 5) / 100)      [flyScale = 1.0]
hp/tick = hp/s ÷ 20
```

| v (km/h) | 格/tick | hp/s | 致死秒数 | 含自然回血（0.25 hp/s） |
|---:|---:|---:|---:|---:|
| 5 | 0.069 | 0.56 | 35.7 | 64.5 |
| 20 | 0.278 | 0.69 | 29.2 | 45.9 |
| 40 | 0.556 | 0.90 | 22.3 | 30.9 |
| 50 | 0.694 | 1.03 | 19.5 | 25.7 |
| **58** | 0.806 | **1.15** | 17.5 | **22.3** |
| **60** | 0.833 | 1.18 | 17.0 | **21.6** |
| **70** | 0.972 | 1.35 | 14.9 | **18.2** |
| **79** | 1.097 | 1.52 | 13.2 | **15.7** |
| **80** | 1.111 | 1.54 | 13.0 | **15.5** |
| 100 | 1.389 | 2.02 | 9.9 | 11.3 |
| 140 | 1.944 | 3.46 | 5.8 | 6.2 |
| 160 | 2.222 | 4.54 | 4.4 | 4.7 |

→ **58–80 全程落在 15–25 秒**（含满饥饿自然回血）；80 以上指数继续加速，不需要 `flyScale` 跳变。
旧默认（0.5 / k=2.0 / flyScale=3.0）算出来是 58→14 s、80→3 s，但接触只有几 tick，
实际只掉 2–3 hp —— 所以旧值既"看起来太快"又"实测偏慢"。

**实例配置也改了**（`config/mtrlte.json` 里这些键是显式写死的，改代码默认值不生效）：
`trainCollisionDamageBasePerSecond 0.5→0.56`、`ExpK 2.0→1.35`、`FlyDamageScale 3.0→1.0`、
新增 `trainCollisionHurtTicks: 500`。原文件备份为 `mtrlte.json.bak-20261109`。
JSON 已用 node 解析验证。

---

## B. 水雾粒子「生成了 600 颗但一颗也看不到」

### 探针改造（`/mtr_lte debug spray on`）

原来：脚底 +0.15 格、脚前 3 格、每 tick 3 颗 CLOUD、10 秒。
现在：**眼前 2 格 · 眼平高度**的一圈 **FLAME**（8 颗/tick，半径 0.45–0.80，逐 tick 旋转）、
持续 **5 秒**，外加三条对照，四条通道分别计数：

| 计数器 | 含义 |
|---|---|
| `eye` | 眼平 2 格的一圈 FLAME（主探针，落点先过 `isAir`，不空气就记 `airSkipped`） |
| `self` | 眼睛前 0.8 格的 FLAME（一定在空气里、一定不被遮挡） |
| `cloudOld` | 旧位置（脚底+0.15、脚前 3 格）的 CLOUD —— A/B 用 |
| `pmDirect` | 绕开 `ClientWorld`、直接塞进 `ParticleManager` 的一颗 FLAME |
| `pm` | `ParticleManager` 里实际持有的粒子数（`getDebugString()`，没有就数 `particles` 队列） |
| `culled` | Sodium Extra 逐类型屏蔽了哪些我们用的类型 |

另外加了 `-Dlte.debugSpray=<延迟 tick>` 自动开探针（实机没有输入自动化时用；默认不存在则完全不动）。

### 实机结论（1 次启动，`tools/mc-test-once.sh`，`lte_probe_test` = 「测试摄影专用」副本）

```
[lte] debug spray: armed by -Dlte.debugSpray, fires in 100 ticks
[lte] debug spray: auto-started by -Dlte.debugSpray
[lte] debug spray: eye=552 self=69 cloudOld=69 pmDirect=0 airSkipped=0 pm=? 
[lte] debug spray: done  eye=1600 self=200 cloudOld=200 pmDirect=0 airSkipped=0
```

- **`airSkipped=0`** → 位置假说被证否：这些点全在空气里（不是"生成在方块内"）。
- **屏幕上什么都没有**（两张截图在探针生效窗口内，画面上只有雨和站台）。
- 结论指向粒子系统之外的一道闸门。逐条核对字节码后定位到 **Sodium Extra**：

```
me/flashyreese/mods/sodiumextra/mixin/particle/MixinParticleManager#addParticle
  @Inject(method="addParticle", at=HEAD, cancellable)
  if (!options.particleSettings.particles)            -> cir.setReturnValue(null)
  if (!otherMap.getOrDefault(粒子id, TRUE))            -> cir.setReturnValue(null)   // 逐类型
```

  而本实例的 `config/sodium-extra-options.json` 里 **`particle_settings.other` 的 95 个类型全是
  `false`**，包括我们用的四个：

```
"minecraft:flame": false, "minecraft:cloud": false,
"minecraft:large_smoke": false, "minecraft:splash": false
```

  **`ParticleManager.addParticle` 直接返回 null，粒子根本没进粒子系统** ——
  `force=true`、`ParticlesMode.MINIMAL`、距离、位置、类型选择全部无关。
  （另：`WorldRenderer.addParticle` 的字节码确认 `force=true` 是直接转给
  `particleManager.addParticle`，**没有**距离/档位闸门 —— 旧代码注释里"force 绕不过 MINIMAL"
  的说法在 1.20.1 是错的，真正的闸门是 Sodium Extra 这一层。）

**所以：本机水雾不可见的根因是 Sodium Extra 的「粒子」逐类型开关被全关，不是我们的代码。**
只要这些类型还是 `false`，任何基于原版粒子的效果（水雾、水花、FLAME 探针）都不可能显示。

代码侧能做且已做的：
1. 新增 `ParticleCull`：反射读 Sodium Extra 的 `particleSettings`，把「被屏蔽的类型」打进
   `[lte] spray: … culled=cloud,large_smoke,splash` 和 `[lte] debug spray: … culled=…`；
   探针结束时还会在聊天栏直接说一句「Sodium Extra 屏蔽了粒子类型 … 在 视频设置 → Sodium Extra
   → 粒子 里打开」。这样下一次"看不到"能一眼归因，不用再怀疑纹理/渲染管道/坐标。
2. 水雾落点加 `airY()`：轨面 +0.3~1.05 那一点若在方块里，向上找最多 4 格到空气；找不到就
   跳过并记 `blocked=`（诊断行新增），不再静默发一颗永远看不见的粒子。
3. 探针的 `pmDirect/pm` 反射改成「按方法名+形参个数匹配，不看返回类型」——
   1.20.1 的 `ParticleManager#addParticle(ParticleEffect, d,d,d,d,d,d)` 返回 `Particle`，
   第一版按 void 找 method 直接 `NoSuchMethodException`，所以统计是 0。

> 未做（需要你点头）：给水雾做一套不依赖原版粒子的自制渲染（quad billboard / 类似光柱管道），
> 那样才能绕过 Sodium Extra 的屏蔽。工作量和风险都远大于本任务，且会与光柱管道抢渲染层。

---

## 验收 / 待人工确认

- BUILD SUCCESSFUL，jar 已到桌面 + 实例（sha256 `b8e0f550ad19d7e8…`）。**未提交 git。**
- 实机 1 次：模组正常加载、无我们自己的 WARN/ERROR；`[lte] collision: cars=3 segs=27 hits=0
  damage=0 impulses=0 vel=0 hurt=0.0` → 新字段全部上线（那一次玩家站着没被撞，所以接触数为 0）。
- 仍需人眼看的三件事：
  1. 站到轨道上让车正面撞：应被**顶着往前走**（不再横向挤），撞飞档（≥50）应明显抛出；
  2. 58–80 撞一次后在 15–25 秒内死亡，死亡屏文案应为「…随着列车的呼啸声消失了」；
  3. 把 Sodium Extra 的 `cloud / large_smoke / splash` 打开后，雨里车侧应看到水雾。
