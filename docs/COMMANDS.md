# LTE 指令与配置清单

> 适用版本：LTE 1.0.0（mod id `mtrlte`），Minecraft 1.20.1 / Fabric。
> 所有客户端指令都需要权限等级 0；带「服务端」标记的在多人服由服主执行。

---

## 一、指令

主指令 `/mtr_lte`，旧名 `/externallights` 保留为别名（两者完全等价）。

| 指令 | 作用 |
|---|---|
| `/mtr_lte` | 打开 LTE 控制面板（与按键绑定同一个入口） |
| `/mtr_lte beam3d` | 打开 3D 光束编辑器（默认选世界里在用的车型） |
| `/mtr_lte beam3d <车型id>` | 同上，直接打开指定车型 |

### 车灯

| 指令 | 作用 |
|---|---|
| `/mtr_lte headlight` | 查看当前车灯模式与生效来源 |
| `/mtr_lte headlight auto` | 自动：白天关、夜晚/隧道/雨天开 |
| `/mtr_lte headlight always` | 常亮 |
| `/mtr_lte headlight off` | 常关 |

### 列车挡雨（车内看不到雨滴）

| 指令 | 作用 |
|---|---|
| `/mtr_lte rain` / `/mtr_lte rain status` | 查看挡雨状态 |
| `/mtr_lte rain on` / `off` | 开关挡雨 |
| `/mtr_lte rain force` | 强制按「下雨」处理（不下雨也能测） |
| `/mtr_lte rain force on` / `off` | 同上，显式指定 |

### 原版内置列车

| 指令 | 作用 |
|---|---|
| `/mtr_lte trains` / `status` | 查看内置车型数量、已过滤数量 |
| `/mtr_lte trains off` | 不加载 MTR 自带车型（资源包车型不受影响） |
| `/mtr_lte trains on` | 恢复加载 |
| `/mtr_lte trains reload` | 立即重载（不用 F3+T） |

### 光柱（车灯光束）

| 指令 | 作用 |
|---|---|
| `/mtr_lte cone` / `status` | 回显：开关、几何源、渲染路径、alpha、boost 状态 |
| `/mtr_lte cone volume on\|off` | `off`（默认）= 壳层路径（已验证可见）；`on` = 实验性 raymarch 体积光 |
| `/mtr_lte cone occl on\|off` | raymarch 是否做深度遮挡（仅 volume 模式有效） |
| `/mtr_lte cone steps <N>` | raymarch 步进数（性能 / 质量权衡） |
| `/mtr_lte cone tail on\|off` | 尾灯是否也画光柱；`off`（默认）= 只给头灯，尾灯位仍用于动态光/光带 |

### 调试（不写入配置，退出游戏即失效）

| 指令 | 作用 |
|---|---|
| `/mtr_lte debug` | 回显所有调试开关 |
| `/mtr_lte debug coneSource model\|glow\|status` | 光柱几何源：`model` = 读车型灯位；`glow` = 复用光带几何（对照用） |
| `/mtr_lte debug cone on\|off` | 可见探针锥（2 格前方，纯白，5 秒） |
| `/mtr_lte debug coneBoost on\|off` | 真实光柱强制 alpha 0.45 且关闭全部衰减（与探针同参数，用于判断「太淡」还是「没画」） |
| `/mtr_lte debug spray on\|off` | 水雾探针：眼前眼平爆一圈，5 秒，此期间忽略露天限制 |
| `/mtr_lte debug killself [low\|high]` | 用 `TrainImpactDamageSource` 给自己一记必死伤害，验证死亡屏与文案（仅单人 / 局域网主机） |

---

## 二、配置项（`config/mtrlte.json`）

旧文件名 `mtr-external-lights.json` / `mtr-headlight.json` 会自动迁移，旧文件保留。
每一项在面板里都有单独的 `↺` 恢复默认；底部有「全部恢复默认」（带二次确认）。
侧线详情页里的 `↺` 语义是「清除本侧线覆盖，重新跟随全局」。

### 光源 · 光带（BeamGlow，稳定功能）

| 键 | 默认 | 说明 |
|---|---|---|
| `beamGlow` | `true` | 车灯光带总开关 |
| `beamDistance` | `128` | 光带可见距离（格） |
| `beamWidth` | — | 光带宽度 |
| `beamHeadLength` / `beamTailLength` | — | 头/尾光带长度 |
| `beamHeadHeight` / `beamTailHeight` | — | 头/尾光带高度偏移 |
| `beamHeadColor` / `beamTailColor` | — | 头/尾颜色（RGB） |
| `beamLift` | — | 整体抬升 |
| `beamAlpha` | — | 光带不透明度 |

### 光源 · 光柱（BeamCone）

| 键 | 默认 | 说明 |
|---|---|---|
| `beamCone` | `false` | 光柱开关（与光带相互独立）。v21 起默认关（实验性）；开：`/mtr_lte cone ...` 或手改 JSON |
| `beamConeSource` | `MODEL` | `MODEL` = 按车型灯位；`GLOW` = 复用光带几何 |
| `beamConeAlpha` | `0.30` | 峰值不透明度（0.16 时会被 8 位色舍入成 0，导致完全不可见） |
| `beamConeVolume` | `false` | `false` = 壳层路径（默认，可见）；`true` = raymarch 体积光（实验） |
| `beamConeOcclusion` | — | raymarch 深度遮挡 |
| `beamConeSteps` | — | raymarch 步进数 |
| `beamBlockLight` / `beamBlockLightRadius` | — | 光柱照亮方块（需 LDL 或自带光照） |

### 光源 · 模式与自动开关

| 键 | 默认 | 说明 |
|---|---|---|
| `headlightMode` | `auto` | `auto` / `always` / `off` |
| `parkedLightsOff` | `true` | 车厂停放不亮灯（不在运行线路上 + 开动过 + 长时间静止） |
| `parkedLightsOffTicks` | `200` | 停放判定阈值（tick，200 = 10 s @ 20 tps） |
| `autoHeadlights` | `true` | 自动开灯 |
| `autoLightsInTunnel` | `true` | 隧道内自动开灯（与「水雾不进隧道」同一判定源） |
| `dynamicLight` | `true` | 动态光源 |
| `ldlRegisterSources` | — | 向 LDL 注册光源 |
| `headLuminance` / `tailLuminance` | — | 头/尾灯亮度 |
| `headLength` / `tailLength` | — | 头/尾灯长度 |
| `headOffset` / `tailOffset` | — | 沿车体方向偏移 |
| `heightOffset` | — | 高度偏移 |
| `maxDistance` | — | 动态光源最大距离 |
| `missingKeepTicks` / `missingDimTicks` / `missingDetachTicks` | — | 列车消失后的宽限/变暗/摘除 |
| `swapEnds` | `false` | 交换头尾 |

### 轨道光照

| 键 | 默认 | 说明 |
|---|---|---|
| `railLight` | — | 轨道照亮总开关 |
| `railRelightTicks` / `railRelightEveryTicks` | — | 重算周期 |
| `railRelightRadius` / `railRelightMaxTrains` | — | 半径与列车数上限 |
| `railLightDelta` / `railRelightBudget` | — | 变化阈值 / 每轮预算 |
| `railRelightMaxMove` / `railRelightMaxInterval` / `railRelightIdleInterval` | — | 位移触发阈值 / 最大间隔 / 空闲间隔 |
| `railLightUnderShaders` | — | 光影下是否仍照亮 |
| `railRelightLoadGraceSec` | `8.0` | 进世界后让路秒数 |
| `railRelightMaxBlockDist` | `64` | 方块距离上限 |
| `railRelightCooldownMs` | `500` | 单区块冷却 |
| `railRelightMaxPerSec` | `12` | 全局每秒上限 |
| `railChunkBuildBoost` | — | 区块构建加速（ANTE/NTE） |
| `railChunkBuildPerFrame` | — | 每帧加速上限 |
| `railRelightFrustumCos` | `0.25` | 视锥余弦阈值 |
| `railRelightDiagnostics` / `railLightShaderBoost` / `railLightGain` | — | 诊断 / 光影增益 |

### 原版内置列车过滤

| 键 | 默认 | 说明 |
|---|---|---|
| `disableBuiltInTrains` | `false` | 不加载 MTR 自带车型（资源包车型与在用列车始终保留） |
| `builtinTrainsDiagnostics` | — | 打印 `builtin trains: ... removed=K kept=S` |

### 水雾 / 雨

| 键 | 默认 | 说明 |
|---|---|---|
| `rainSpray` | `true` | 雨天水雾开关 |
| `rainSprayMinSpeedKmh` | `50` | 起始车速（低速渐入的上端） |
| `rainSprayLowSpeedBoost` | `true` | 20–50 km/h 渐入出雾：密度 = `lerp(0.35,1.0,(kmh-20)/(min-20))`；<20 不生成 |
| `rainSprayDiagnostics` | — | 诊断行 `spray: trains= spawned= roofSkipped= cap= lowBoost= avgSize=`（逐车 `spray-diag: ... low=`） |
| `rainHush` | — | 列车挡雨（车内不画雨） |
| `rainHushSplash` | — | 挡雨时是否仍显示雨点溅射 |
| `rainHushDiagnostics` | — | 诊断 |

水雾使用模组自有粒子类型 `mtrlte:spray`，**不受 Sodium Extra 的粒子屏蔽影响**
（Sodium Extra 按已知 id 查表，未知 id 默认放行）。隧道判定与「进隧道自动开灯」同源：`RailWeather.openSky`。

### 碰撞 · 列车

| 键 | 默认 | 说明 |
|---|---|---|
| `trainCollision` | `true` | 列车外部碰撞总开关 |
| `trainCollisionHeight` | — | 碰撞高度（按车体） |
| `trainCollisionEndPad` | `1.0` | 首尾端额外延长（格） |
| `collisionHeadExtra` / `collisionTailExtra` | — | 车头/车尾额外长度 |
| `roofDropMinSpeedKmh` | `50` | 车顶超过此速度才可能被甩下 |
| `roofDropMaxRadiusM` | `100` | 弯道半径小于此值才可能被甩下 |
| `trainCollisionDamage` | `true` | 碰撞伤害 |
| `trainCollisionPushOnlyMaxKmh` | `5` | 低于此速度只推不伤 |
| `trainCollisionLowDamageMaxKmh` | `20` | 低速段上限 |
| `trainCollisionHighDamageMaxKmh` | `50` | 中速段上限 |
| `trainCollisionLaunchMaxKmh` | `80` | 撞飞段上限（≥ 此值进入飞行档） |
| `trainCollisionDamageBasePerSecond` | `0.56` | 伤害基准 |
| `trainCollisionDamageExpK` | `1.35` | 指数系数 |
| `trainCollisionDamageSpeedScaleKmh` | `100` | 速度尺度 |
| `trainCollisionFlyDamageScale` | `1.0` | 飞行档伤害倍率 |
| `trainCollisionDamageMax` | — | 单次结算上限 |
| `trainCollisionHurtTicks` | `120` | 撞击后持续掉血窗口（tick，40–600 = 2–30 s） |
| `knockbackDistance` | `5.5` | 撞飞距离基准（格，1–80）＝ 50 km/h 时飞多远 |
| `knockbackExpK` | `1.0` | 撞飞距离指数 k（0–3）：`d(v)=base·exp(k(v−50)/50)`，每快 50 km/h ×e^k |
| `knockbackMax` | `80` | 撞飞距离上限（格，1–200） |
| `trainCollisionLaunchHorizontal` / `trainCollisionLaunchVertical` | `0.45` | 撞飞竖直初速（滞空约 0.5 s） |
| `trainCollisionFlyHorizontal` / `trainCollisionFlyVertical` | `0.60` | 高速撞飞竖直初速（约 0.7 s） |
| `trainCollisionImpactCooldownTicks` | `20` | 冲量冷却 |
| `trainCollisionMobs` | — | 伤害同样作用于生物 |
| `damageInCreative` | `false` | 创造模式玩家也受伤/被撞死（旁观仍免疫） |
| `collisionDiagnostics` | — | 诊断行 `collision(server): trains= cars= hits= damage=` |

### 碰撞 · 道床

| 键 | 默认 | 说明 |
|---|---|---|
| `trackbedCollision` | — | 轨道道床碰撞（可站上去） |
| `trackbedCollisionEntities` | — | 同样接住生物 |
| `trackbedCollisionItems` | — | 同样接住掉落物 |
| `trackbedThickness` | — | 道床厚度 |

### 侧线覆盖

任何带 `...AnySiding` 的开关都表示「该功能在侧线详情页可单独覆盖」；侧线详情页的 `↺` 是清除覆盖、重新跟随全局。

---

## 三、伤害与死亡

伤害按接触车速分档，公式 `hp/s = base · exp(k · (v − 5) / scale)`，每 **10 tick** 结算一次
（避开原版无敌帧，同时不再每 tick 重置受击动画，所以不会出现镜头持续抖动）。

| 车速 | 行为 |
|---|---|
| < 5 km/h | 只推开，无伤害 |
| 5–20 | 推开 + 低速伤害 |
| 20–50 | 推开 + 中速伤害 |
| 50–80 | 撞飞（滞空约 0.5 s） + 高速伤害 |
| ≥ 80 | 更大冲量 + 飞行档伤害 |

- 撞击后有 `trainCollisionHurtTicks`（默认 120 tick = 6 s，钳制 40–600）的持续掉血窗口 —— 接触本身只有几 tick，靠接触期伤害永远死不了。伤害速率公式不变，窗口只是不再拖 25 秒；被撞飞（真的给了冲量）的在第一次落地一次结算完剩余预算。
- 撞飞冲量锁到落地为止（`isOnGround()` 或脚底离地 < 0.1 格），滞空期间不做「跟车走」的速度推送，落地后也不会被反复弹。
- 持续掉血窗口内每 tick 清 `hurtTime/maxHurtTime`（受击镜头不抖不歪），窗口一结束立刻恢复；其它伤害源的受击表现不受影响。
- 致死那一击走 `LivingEntity#damage(TrainImpactDamageSource)`，因此死亡屏与死亡消息正常。
- 创造 / 旁观默认免疫；创造模式想受伤需打开 `damageInCreative`。
- 死亡消息：
  - 低速：`%1$s 因为被罚了5000HKD而被气似了`
  - 高速：`%1$s 随着列车的呼啸声消失了`

---

## 四、诊断速查

| 现象 | 先看哪一行 |
|---|---|
| 光柱看不到 | `[lte] cone: ... drawn= quads= path= dmin= range= g1..g9=` |
| 光柱太淡 / 太实 | `/mtr_lte debug coneBoost on` 对照；`beamConeAlpha` |
| 光带看不到 | `[lte] glow: ... calls= quads= beams= near= skipped=` |
| 水雾看不到 | `[lte] spray: ... trains= spawned= roofSkipped= sprayType= culled=` |
| 水雾不出现但下雨 | 检查 `trains=`（附近是否真有车）与 `rainSprayMinSpeedKmh` |
| 撞了没伤害 | `[lte] impact: v= tier= dmg=` 与 `collision(server) ... hits= damage=` |
| 撞了不死 | 是否创造/旁观/无敌（`damageInCreative`）；用 `/mtr_lte debug killself` 单独验证链路 |
| 轨道加载慢 | `[lte] railq: req/rebuilt/skipped/deferred/paused/quota` |
| 资源包车型消失 | `[lte] builtin trains: ... removed= kept=` |

---

## 五、按键

默认按键在「选项 → 控制 → 按键绑定 → LTE」中修改，与 `/mtr_lte` 打开同一个面板。
