# 2026-11-09 撞飞后「抖很久才死」：窗口 500→120、全程抑制受击镜头、冲量锁到落地（1.20.1 / LTE）

构建：`bash tools/build-all.sh v1.20.1` → **BUILD SUCCESSFUL**（13 s）
产物：`v1.20.1/build/libs/lte-1.0.0.jar`（546 951 字节，sha256 `bceb7a2a9928cb71…`）
→ 桌面 `lte-1.0.0+1.20.1.jar` + 实例
`PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/`（三处字节一致）。**未提交 git。**

> 同一工作树里还有其它并行改动（`ProtoBeamCone` / `coneOnTail` 等），jar 里包含它们；
> 构建期间遇到过一次 `:clean FAILED`（另一个构建占着 `v1.20.1/build`）与一次
> `ProtoBeamCone.java:328` 的中间态编译错误，等对端改完重跑即通过。

---

## 现场

「被撞飞以后抖了很久才死亡（估计是那个十秒搞得）」—— 实际不是 10 tick 的结算周期
（那是 0.5 s，正好卡在原版无敌帧之后），而是三件事叠在一起：

1. `trainCollisionHurtTicks = 500`（25 s）：车早就开走，人还在按 hp/s 磨血；
2. 每 10 tick 一笔伤害把 `hurtTime` 重置成 10，镜头一秒歪两次，25 秒不停；
3. 冲量锁只有固定 `flightTicks + 4`（10–18 tick），落地后「跟车走」的速度推送又接着推；
   同一次接触在 20 tick 冷却后还能再吃一发冲量。

## 改法

| 项 | 旧 | 新 |
|---|---|---|
| `trainCollisionHurtTicks` | 500（25 s），钳制 0–1200 | **120（6 s）**，钳制 **40–600** |
| 伤害速率 | `0.56·exp(1.35(v−5)/100)` hp/s | **不变**（公式、`DAMAGE_INTERVAL_TICKS=10` 都没动） |
| 受击镜头抑制 | 最后一击后 1.5 s 内清 | **整个窗口内每 tick 清** `hurtTime/maxHurtTime` |
| 冲量锁 | 固定 `flightTicks+4` tick | **锁到落地**（`isOnGround()` 或脚底往下 0.1 格内有方块） |
| 落地 | 继续按 hp/s 磨到死 | **第一次落地一次结算完剩余预算** |
| 诊断行 | `impact: v= tier= dmg= pushed=` | `impact: v= tier= dmg= window= left= pushed=` |

### 1. 窗口

`CollisionImpact.tick()` 仍然在每次接触时把 `hurtUntil` 推到 `now + 120`，同时写一份**墙钟**
`hurtUntilMs`（客户端 tick 与服务端 tick 不同源，镜头抑制只能看墙钟）。窗口内伤害速率与结算
周期完全不变 —— 变的只是「最多磨 6 秒」。

### 2. 镜头

`CollisionImpact.suppressHurtTilt()` 的条件从「`lastDamageMs` 在 1.5 s 内」改成
「`launched` 或 `now < hurtUntilMs`」。`CollisionSystem.tick()`（`END_CLIENT_TICK`，渲染之前）
每 tick 调一次，所以受击包无论哪一 tick 到达都会被立刻清掉。
窗口一结束（预算花完 / 落地结算完 / 6 s 到点）就不再清 —— 挨僵尸、摔落的受击表现照旧。
1.5 s 的 `TILT_SUPPRESS_MS` 只留作「服务端打伤害 → 客户端收到受击包」的兜底。

### 3. 冲量锁到落地

`State.impulseLock` 在冲量写进 velocity 时打开，`impulseActive(LivingEntity)` 每次被问到时：

```
距写入 < 250 ms                      → 仍锁（这一 tick 人还站在原来的地面上）
now >= impulseMaxMs（40–100 tick）   → 解锁（兜底：掉虚空 / 落在非方块上）
isOnGround() 或 脚底往下 0.1 格有方块 → 解锁（落地）
否则                                 → 仍锁
```

`CollisionSystem.velocityPush` / `ServerCollision.velocityPush` 都在锁内直接 return；
`tick()` 的冲量分支也用 `!impulseActive(entity)` 门控 —— 滞空期间不再吃第二发冲量，
落地后 20 tick 冷却照常，所以不会「落地又被同一列车连弹」。

### 4. 落地结算（新增）

`flightDamage()` 每 tick 看一次落地：起飞满 4 tick 且 `grounded()` 时，
`settleOnLanding()` 把 `预算 − 已扣` 整笔交出去：

```
预算 = trainCollisionDamageMax > 0 ? min(最大生命, trainCollisionDamageMax)
                                   : 最大生命 × 1.5（OVERKILL_SCALE，抵自然回血）
落地伤害 = 预算 − applied            // ≤ trainCollisionDamageMax，永远不会超
```

伤害仍然走正规 `LivingEntity#damage(TrainImpactDamageSource)`，所以死亡屏 / 死亡文案 / 高速低速
分段都不变。窗口只额外留 2 笔（1 s），给「减伤把这一笔吃掉」留一次「必死那一击」的机会；
若 6 s 内既没落地也没死（掉虚空 / 挂在半空），窗口到点就收手，不再一路磨下去。

## 日志

```
[lte] impact: v=82.3 tier=FLY dmg=0.77 window=120 left=118 pushed=true
[lte] landing: settle=28.94 hp=20.00 max=20.00 cap=30.00
[lte] damage: src=TrainImpactDamageSource want=28.94 hp=20.00->0.00 landed=true iframes=0 …
[lte] death: … key=death.attack.mtrlte.trainHigh …
```

`window=` 是配置的窗口总长（tick），`left=` 是这一 tick 还剩多少 tick（0 = 窗口已结束）。

## 实例配置

`PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/config/mtrlte.json`：`trainCollisionHurtTicks`
`500 → 120`（原文件备份 `mtrlte.json.bak-20261109-hurt`），JSON 已用 node 解析验证。
`temp/mcrun`、`temp/mcbeam` 两个测试实例同步为 120。

## 未做 / 已知取舍

- 50–80 km/h（LAUNCH 档）现在 6 s 窗口只掉约 6 hp（以前 19 s 慢慢磨死）；真的给了冲量的按落地
  结算走，所以「撞飞 → 落地 → 死」这条链反而更快。
- 没有启动游戏验证手感（按要求）。建议实机看三行日志：`impact:`（window/left）、
  `landing: settle=`（落地那一笔）、`death:`（文案）。
