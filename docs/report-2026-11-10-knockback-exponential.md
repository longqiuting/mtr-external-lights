# 撞飞距离改为「随时速指数增长」的可配置函数

构建：`gradle clean build`（v1.20.1，JDK 21.0.10）→ **BUILD SUCCESSFUL**
产物：`v1.20.1/build/libs/lte-1.0.0.jar`（914 009 字节，sha256 `1ff3a912bc468e3c…`）
→ 桌面 `lte-1.0.0+1.20.1.jar` + 实例
`PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.0+1.20.1.jar`（三处字节一致，sha256 相同；
jar 内 `CollisionImpact.class` 已确认含 `knockbackDistanceAt` / `knockbackExpK` / `knockbackMax`）。
**未提交 git，未启动游戏。**（构建期间同仓库有另一次并发构建在跑，见文末 F 节。）

---

## A. 公式与配置

```
d(v) = knockbackDistance · exp(knockbackExpK · (v − 50) / 50)    [格，钳制 1 … knockbackMax]
```

| 旋钮 | 字段 | 默认 | 范围 | 含义 |
|---|---|---|---|---|
| 基准距离 | `knockbackDistance` | 5.5 | 1–80 | **50 km/h 时的**距离（曲线起点） |
| 指数系数 | `knockbackExpK` | 1.0 | 0–3 | 每快 50 km/h，距离 ×e^k（k=1 → ×2.72，k=0 → 固定距离） |
| 距离上限 | `knockbackMax` | 80 | 1–200 | 指数曲线的天花板 |

参考速度与速度尺度都固定为 **50**（`CollisionImpact.KNOCKBACK_REF_KMH` /
`KNOCKBACK_EXP_SCALE_KMH`，`CollisionImpact.java:320-323`），**不**用伤害起点
`trainCollisionPushOnlyMaxKmh`（默认 5、侧线可覆盖到 0–300）—— 那个值当 v0 用会让同一档位内
`exp` 差几十倍（v0=10 时 100 km/h 已吃掉 e⁹）。

默认值下的曲线：

| 车速 | 50 | 80 | 120 | 160 | 200 | 300 |
|---|---|---|---|---|---|---|
| 距离（格） | 5.5 | 10.0 | 22.3 | 49.6 | 80.0（封顶） | 80.0 |

`k` 的手感（50/80/120/160/200 km/h）：`k=0` → 5.5/5.5/5.5/5.5/5.5；`k=2` → 5.5/18.3/80/80/80；
`k=3` → 5.5/33.3/80/80/80。

### 水平初速仍是平抛反解（同一套系数）

竖直初速先定滞空 tick 数 `n`（重力 0.08、竖直阻尼 0.98），再
`vx = d(v) / Σ(0.91^i, i=0..n−1)`（`CollisionImpact.knockbackHorizontal`，
`CollisionImpact.java:413-421`）。所以公式值就是落地位移，**逐 tick 积分与公式的误差在 1e-15 量级**
（B 节）。撞飞档（50–80 km/h，vy 0.45）与飞出档（≥80，vy 0.60）现在用**同一条曲线**，
原来的「飞出 ×1.5」已删除（`FLY_DISTANCE_SCALE` 不再存在）。

### UI

伤害分区新增两行，都带 ↺（全局页 `ConfigWriteTarget.COLLISION_KNOCKBACK_K` /
`COLLISION_KNOCKBACK_MAX`，`GlobalTarget` 的 read/write/resetValue/resetAll 全覆盖；侧线页不生成，
`SidingTarget` 里按 global-only 忽略）：

- `opt.knockbackDistance` → 「撞飞基准 %s 格（50 km/h 时）」1–80，步长 0.5
- `opt.knockbackExpK` → 「撞飞指数 k %s（每快 50 km/h ×e^k）」0–3，步长 0.1
- `opt.knockbackMax` → 「撞飞距离上限 %s 格」1–200，步长 1

### 日志

`[lte] knockback:` 行现在带公式输入与输出：
`tier=FLY v=120km/h base=5.50 k=1.00 max=80 dist=22.30 vx=2.739 vy=0.60 air=14t`。

---

## B. 离线验证：四档落点（`tools/knockback-curve.js`）

脚本与 Java 逐行一致（`flightTicks` / `horizontalReach` / `knockbackDistanceAt` /
`knockbackHorizontal` 的系数、`exp` 上界判断、1–80 钳制全部照抄），再用 1.20.1
`LivingEntity#travel` 的空中积分（`vy -= 0.08; y += vy; x += vx; vy *= 0.98; vx *= 0.91`，
落地那一 tick 也算位移）跑真实落点。**游戏未启动，纯离线。**

默认配置（base 5.5 / k 1.0 / max 80 / v0 参考 50）：

| 车速 | 档位 | 竖直 vy | 滞空 | 公式距离 | 反解 vx | **实测落点** | 误差 | 最高点 |
|---|---|---|---|---|---|---|---|---|
| 50 km/h | LAUNCH | 0.45 | 10 t | 5.500 | 0.811 格/t | **5.500 格** | −8.9e-16 | 0.99 格 |
| 80 km/h | FLY | 0.60 | 14 t | 10.022 | 1.231 格/t | **10.022 格** | +0.0 | 1.80 格 |
| 120 km/h | FLY | 0.60 | 14 t | 22.304 | 2.739 格/t | **22.304 格** | +7.1e-15 | 1.80 格 |
| 160 km/h | FLY | 0.60 | 14 t | 49.638 | 6.095 格/t | **49.638 格** | +7.1e-15 | 1.80 格 |

四档全部落在公式值上（差值只有浮点尾数），且都没碰 `knockbackHorizontal` 的 8 / 12 格/t 防御上限
（160 km/h 的 6.095 离 12 还有余量；80 格封顶时约 10.8，仍在上限内）。

复现：

```
node tools/knockback-curve.js
```

---

## C. 改动文件

| 文件 | 改动 |
|---|---|
| `common/…/HeadlightConfig.java` | `knockbackDistance` 改为基准值（钳 1–80）+ 新增 `knockbackExpK`（0–3，默认 1.0）与 `knockbackMax`（1–200，默认 80）及 `*Clamped()`；`fix`/`sanitize` 同步钳制 |
| `v1.20.1/…/collision/CollisionImpact.java` | 删除 `launchDistance` / `FLY_DISTANCE_SCALE`；新增 `KNOCKBACK_REF_KMH`、`KNOCKBACK_EXP_SCALE_KMH`、`knockbackDistanceAt(cfg, speedKmh)`；`knockbackHorizontal` 改为按车速反解；`impulse` 带上 `speedKmh`；`[lte] knockback:` 日志补 base/k/max/dist；类注释同步 |
| `v1.20.1/…/ConfigWriteTarget.java` | 新增 `COLLISION_KNOCKBACK_K/B_MAX`（含 `ALL_VALUES`、全局 read/write/resetValue/resetAll、侧线忽略表） |
| `v1.20.1/…/ParameterScreen.java` | 伤害分区新增两行 + 标签 / min / max / step / 格式化 |
| `v1.20.1/resources/assets/mtrlte/lang/{zh_cn,en_us}.json` | `opt.knockbackDistance` 改文案，新增 `opt.knockbackExpK` / `opt.knockbackMax` |
| `tools/knockback-curve.js` | 新增：指数曲线的四档离线落点验证（本报告 B 节） |
| `docs/COMMANDS.md` | 配置表补两行、改 `knockbackDistance` 说明 |

## D. 实例配置

`.../1.20.1-Fabric 0.19.3/config/mtrlte.json` 原来只写死了 `knockbackDistance: 5.5`，现在补上
`"knockbackExpK": 1.0` 与 `"knockbackMax": 80.0`（与代码默认一致，所以行为不变，只是 JSON 里看得见）。
备份：`mtrlte.json.bak-20261110-knockback-curve`。

## E. 兼容性

- 旧 JSON 只有 `knockbackDistance`：`knockbackExpK` / `knockbackMax` 取字段初值 1.0 / 80，
  旧存档升上来直接就是新曲线（50 km/h 仍是 5.5 格，80+ 从「1.5 倍固定」变成 10 格起指数增长）。
- `trainCollisionLaunchHorizontal` / `trainCollisionFlyHorizontal` 仍是废弃字段，不参与计算。

## F. 构建期间的并发告警

构建过程中发现**同一工作区有另一次 `gradle clean build` 在并发执行**（`temp/build-v1201-glow.log`
对应的进程），它在中途 `clean` 掉 `build/libs` 和 `build/classes`，导致本任务前两次构建报
`ProjectorConnectorItem.java:188: 错误: 程序包LteLog不存在` 并让 `build/libs` 一度反复消失。
本任务自身的 `clean build` 单独跑过一次并 BUILD SUCCESSFUL；随后并发构建又覆盖了
`build/libs`（同一份源码，同样包含本次改动，已用 `unzip` 检查 `CollisionImpact.class` 含
`knockbackDistanceAt` / `knockbackExpK` / `knockbackMax`），最终取那一份 914 009 字节的产物复制到
桌面与实例并校验 sha256（三处一致，`1ff3a912bc468e3c…`）。
若之后还有并发构建覆盖桌面/实例的 jar，需要再复制一次；代码层面不受影响（同一棵树）。
