# 光柱默认关闭 + 低速水雾渐入（2026-11-11）

构建：`v1.20.1` · `gradle-8.14 build`（JAVA_HOME = `C:\Program Files\Java\jdk-21.0.10`）→
**BUILD SUCCESSFUL in 8s**，产物 `lte-1.0.32.jar`（2,074,618 B，
sha256 `36af5f248facff9451b9728f9887624f24c7304d4139dfc7dbf58e78b1401674`）。
jar 已复制到桌面 `lte-1.0.32.jar` 与实例
`PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.32.jar`（三份哈希一致，1.0.31 已删）。
未提交 git。

---

## A. 光柱（beam cone）默认关闭

`HeadlightConfig#beamCone` 的默认值 `true` → **`false`**，`CONFIG_VERSION` **20 → 21**。

### 采用的迁移策略

```java
if (configVersion < 21) {
    if (beamCone) beamCone = false;   // 旧默认值 → 新默认值
}
configVersion = CONFIG_VERSION;       // 21
```

* **判据是「旧值 = 旧默认值」**，和本文件里版本 2 / 5 / 16 的做法完全一致（那只改恰好等于
  旧默认值的值，手改过的其他数值一律不动）。`beamCone` 的旧默认就是 `true`，所以旧文件里的
  `true` **无法与「从没动过」区分** —— 按「旧值是默认值就迁移」这条规则，它落到 `false`。
* **一旦写成 21，手设的 `true` 就永久保留**：之后再加载 `configVersion >= 21`，`migrate()` 第一行
  直接返回，不会再把 `true` 掰回 `false`。用户想开：手改 `config/mtrlte.json` 的 `"beamCone": true`
  或 `/mtr_lte` 的命令 / 参数界面，开关与命令全部保留，一行没删。
* **侧线覆盖不动**：`sidingSettings[*].beamCone` 永远是玩家显式选的（没设就是 `null` = 跟随全局），
  迁移只碰全局值。全局 `false` + 某条侧线 `true` 时，`beamConeAnySiding()` 仍会让那一列车出光柱，
  其它车不出 —— 行为与旧版逐车过滤的逻辑一致。
* 现状核对（用户实例的 `config/mtrlte.json`）：`configVersion: 20`、`beamCone: true`、
  `sidingSettings` 里没有任何 `beamCone` 键 → 首次加载会写成 `false`，全列车不再画光柱。

### 为什么不选「旧文件里的 true 一律保留」

那样本次改动对**现有存档等于没改**（每个老文件都写着 `beamCone: true`），而用户的诉求正是
「光柱还是实验性的，默认别开」。`CONFIG_VERSION` 存在就是为了让「默认值变了」能落到老文件上；
这次和 `beamConeAlpha 0.16 → 0.30`（版本 16）是同一类变更。

### 验证（javap，`build/classes/java/main`）

```
$ javap -p -constants ... HeadlightConfig
  private static final int CONFIG_VERSION = 21;
构造器： 269: iconst_0 / 270: putfield  Field beamCone:Z      ← 默认 false
migrate()：4: bipush 21 / 6: if_icmplt 11 / 9: iconst_0 ireturn  ← 已是 21 直接 return
          244: if_icmpge 259                                  ← configVersion < 21 才进
          248: getfield Field beamCone:Z
          251: ifeq 259                                        ← 已经 false 就不写
          255: iconst_0 / 256: putfield Field beamCone:Z      ← true → false
          260: bipush 21 / 262: putfield Field configVersion:I
```

光柱渲染侧的闸门没改：`ProtoBeamCone` 的 `g2`（`!cfg.beamCone && !cfg.beamConeAnySiding()` →
帧级 return）与 `g5`（逐车 `eff.beamCone`）原样保留；`/mtr_lte debug cone`（探针）与
`beamConeVolume` / `beamConeAlpha` / `beamConeSteps` 等参数一行未动。光带 `beamGlow` 不受影响
（本来就与光柱各自独立）。

---

## B. 低速（20–50 km/h）水雾渐入加密

新增 `HeadlightConfig#rainSprayLowSpeedBoost`（默认 `true`，全局字段，不逐侧线覆盖）。

### 密度曲线

```
factor(kmh) = 0.35 + 0.65 * clamp((kmh - 20) / (minSpeed - 20), 0, 1)
kmh  <= 20        → 不生成（与旧行为一致：停车/溜车不算行驶）
20 < kmh < minSpeed → 生成，但抽签概率再乘 factor（0.35 → 1.00）
kmh >= minSpeed   → factor = 1.00，照旧
```

`minSpeed` = `rainSprayMinSpeedKmh`（默认 50，可逐侧线覆盖）。`minSpeed <= 20` 时整段退化
（没有可渐入的区间），`factor` 恒为 1.0 —— 与关闭该开关完全等价。

实测（jshell 复算 + 与编译产物逐常量核对）：

| km/h | 20 | 21 | 25 | 30 | 35 | 40 | 45 | 49 | 50 | 55 | 60 | 80 | 120 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `low=`（密度乘数） | 0.350* | 0.372 | 0.458 | 0.567 | 0.675 | 0.783 | 0.892 | 0.978 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| 单节抽签概率 | — | 0.093 | 0.115 | 0.142 | 0.169 | 0.196 | 0.223 | 0.245 | 0.250 | 0.304 | 0.357 | 0.571 | 1.000 |

\* 20 km/h 本身被闸门挡住（`kmh <= gateKmh`），0.350 只是曲线端点。阈值处两侧连续：
49 km/h 0.245 → 50 km/h 0.250 → 50.01 km/h 0.250，**50 km/h 不再是一个跳变**。

### 预算：一分没加

* 令牌桶（60/tick 补、240 上限）、每列车每 tick 24、全局每 tick 80 **全部不变**；
* 只把「单次抽签是否通过」乘上 `factor`：
  `nextDouble() > spawnChance(intensity) * chanceScale() * lowFactor`；
* 所以低速段是「更稀」，不是「多花预算」—— 低速车多了也不会把额度顶到高速车的份额上。
  低速时段本来令牌桶就用不完（`skipped` 里 `chance` 计数上升），这也是不需要额外预算的原因。

### 诊断

* 主行新增 `lowBoost=`：
  `[lte] spray: trains=1 spawned=12 density=0.001(12/8000) skipped=40 roofSkipped=0 cap=240 lowBoost=on(20-50 0.35..1.00) avgSize=0.121 ...`
  （关闭 / `minSpeed <= 20` 时打 `lowBoost=off`）
* 逐车行新增 `low=`（本 tick 实际乘到的系数）：
  `spray-diag: id=... speed=32.4 rail=32.1 low=0.62 raining=true sky=open ... reason=ok`
  —— `low=` 落在 0.35–1.00 之间就说明这列车正处在渐入段，1.00 是满档。

编译产物核对（`javap -c RainSpray`）：

```
lowFactor(double,double,boolean):
   2: ifeq 11 / 5-8: dload_0,dload_2,dcmpg,iflt 13   ← !lowBoost || kmh >= min → 1.0
  13: dload_2 / 14: ldc2_w 20.0d / 17: dsub          ← span = min - 20
  29: dload_0 / 30: ldc2_w 20.0d / 33: dsub / 36: ddiv ← t = (kmh-20)/span
  39-60: clamp t 到 [0,1]
  62: ldc2_w 0.35d / 65: ldc2_w 0.65d / 70: dmul / 71: dadd ← 0.35 + 0.65*t
emit(): use cfg.rainSprayLowSpeedBoost && min>20 → gateKmh = 20 否则 min
        spawnChance * chanceScale * (dload 24 = lowFactor)  → 与声明的公式逐条对应
```

### 注意：用户现有配置里 `rainSprayMinSpeedKmh = 0`

实例的 `config/mtrlte.json` 现在是 **`"rainSprayMinSpeedKmh": 0`**（不是任务描述里的 50）。
`minSpeed <= 20` 时渐入段按设计退化，效果等于旧行为（速度 > 0 就有雾，抽签 0.25 起）。
**要看到 20–50 的渐入曲线，把这一项改回 `50`**（默认值）；想让渐入段以别的阈值收口，填任意
>20 的数即可。这一项没有替用户改 —— 它是玩家显式写进配置的阈值，静默覆盖会改变「任何速度都溅水」
这个他可能正在用的效果。

---

## 改动文件

| 文件 | 改动 |
|---|---|
| `common/.../HeadlightConfig.java` | `CONFIG_VERSION` 20→21；`beamCone` 默认 false + 文档；`migrate()` 版本 21 分支（旧 true → false）；新增 `rainSprayLowSpeedBoost = true` + 文档 |
| `v1.20.1/.../RainSpray.java` | 新增 `LOW_SPEED_KMH = 20` / `LOW_SPEED_DENSITY_FLOOR = 0.35`；`emit()` 闸门改 20 并按 `lowFactor` 稀释抽签；新增 `lowFactor(...)`；`Diag.lowFactor`；`lowBoost=` / `low=` 诊断；类注释同步 |
| `docs/COMMANDS.md` | `beamCone` 默认值改 false；新增 `rainSprayLowSpeedBoost` 行；诊断行列名更新 |

## 验证范围

* 构建与字节码核对完毕（默认值、迁移分支、密度公式、抽签乘数、诊断接线）。
* **未启动游戏**（按要求）：实机观感（低速雾的稀疏程度、光柱默认不画）需要进游戏确认。
