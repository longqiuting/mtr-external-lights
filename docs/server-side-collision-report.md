# LTE 服务端支持报告（environment=* / 服务端权威碰撞）

日期：2026-11-09　版本：**只做 1.20.1**　构建：`gradle -p v1.20.1 build` → `v1.20.1/build/libs/lte-1.0.0.jar`

---

## 0. 结论（TL;DR）

1. `environment` 改成 `"*"`，新增 `main` 入口 `dev.js.mtrheadlight.server.LteServer`（不引用任何客户端类），
   client 入口原样保留。**没装服务端组件照样连服**：客户端只多注册一个握手接收器。
2. Mixin 分成两组：**客户端组**（7 个配置，全部 `client: [...]` → dedicated server 上一条都不应用）
   与**服务端组**（`mtrlte.mtrserver.mixins.json`，`mixins: [...]` + `defaultRequire: 0` + 类资源探测插件）。
3. 服务端权威碰撞：`server.ServerCollision` 每 tick 用 `TrainTickCapture`（MTR `TrainServer#simulateTrain`
   TAIL 登记）+ `TrainPose` + `CarGeometry`（从 `CollisionSystem` 抽出的纯计算几何）算出「撞没撞、往哪推」，
   后果交给已有的 `CollisionImpact`（分档 / 指数伤害 / 撞飞 / 冷却 / `TrainImpactDamageSource` 两段死亡消息）。
   也就是说**玩家与生物、单人与多人、客户端与服务端走的是同一套公式**。
4. 双重结算的规则：**服务端有本模组 → 服务端权威 → 客户端只做表现**。判定在
   `CollisionSystem#serverOwnsIt()`：`对端握手过 mtrlte:hello` 或 `MinecraftClient#getServer() != null`（单人）。
5. 静态扫描证明 `server/` 闭包（28 个类）**没有任何** `net.minecraft.client.*` / `mtr.client.*` / 渲染类引用。
6. **服务端道床**：`server.ServerTrackbed` 每 tick 把玩家 64 格内的掉落物 / 下落的方块 / 生物吸到轨面上
   （与玩家同一条 `RailBed` 几何，服务端的轨道表来自 `RailwayData#getRailsMap()`）。

---

## 1. fabric.mod.json（最终）

```json
"environment": "*",
"mixins": [
  "mtrlte.ante.mixins.json",        // client
  "mtrlte.collision.mixins.json",   // client  (MixinEntityMove)
  "mtrlte.fangsu.mixins.json",      // client
  "mtrlte.nte.mixins.json",         // client
  "mtrlte.rain.mixins.json",        // client
  "mtrlte.mtr.mixins.json",         // client  (TrainClientRegistry)
  "mtrlte.mtrserver.mixins.json",   // SERVER  (TrainSimulateCaptureMixin)
  "mtrlte.beamlight.mixins.json"    // client
],
"entrypoints": {
  "main":   ["dev.js.mtrheadlight.server.LteServer"],
  "client": [
    "dev.js.mtrheadlight.MtrHeadlightClient",
    "dev.js.mtrheadlight.collision.CollisionBootstrap",
    "dev.js.mtrheadlight.mtr.BuiltInTrainsInit",
    "dev.js.mtrheadlight.RainTest",
    "dev.js.mtrheadlight.BeamLightTest",
    "dev.js.mtrheadlight.ConeTest"
  ],
  "lambdynlights:initializer": ["dev.js.mtrheadlight.MtrHeadlightDynamicLights"],
  "modmenu": ["dev.js.mtrheadlight.ModMenuIntegration"]
}
```

* `main` 入口只有 `LteServer`：读本侧配置、注册 `END_SERVER_TICK` / JOIN、标记 dedicated、注册握手。
* `lambdynlights:initializer` 与 `modmenu` 只在客户端被调用（对应模组本身只在客户端存在），dedicated server 不会碰。
* 依赖未变（`mtr: "*"`）。MTR 不在时模组根本不会加载，等于「静默跳过」的最强形式。

### mixin 配置逐条

| 配置 | 分组 | require | 环境探测 |
|---|---|---|---|
| `mtrlte.collision.mixins.json` | `client: [MixinEntityMove]` | `defaultRequire: 0` | 只在客户端应用 |
| `mtrlte.rain.mixins.json` | `client: [WorldRendererRainMixin]` | 1 | 只在客户端应用 |
| `mtrlte.beamlight.mixins.json` | `client: [BeamLightMixin]` | 1 | 只在客户端应用 |
| `mtrlte.mtr.mixins.json` | `client: [TrainClientRegistryMixin]` | 0 | 插件 `MtrMixinPlugin`（探 `mtr.client.TrainClientRegistry`） |
| `mtrlte.ante.mixins.json` | `client: [...]` | 0 | 插件 `AnteMixinPlugin` |
| `mtrlte.nte.mixins.json` | `client: [...]` | 0 | 插件 `NteMixinPlugin` |
| `mtrlte.fangsu.mixins.json` | `client: [...]` | 0 | 插件 `FangsuMixinPlugin` |
| **`mtrlte.mtrserver.mixins.json`** | `mixins: [TrainSimulateCaptureMixin]` | **0** | **插件 `MtrServerMixinPlugin`（探 `mtr.data.TrainServer`）** |

「服务端组」为什么不用 `"server": [...]`：`"mixins"` 数组在两端都应用，而这张表在**单人**也必须填
（整合服务器上的生物碰撞与玩家服务端结算都要它）。真正的分界线是「有没有引用客户端类」，
不是配置文件名；`server/` 闭包的扫描（§4）保证这条线没被越过。

---

## 2. 双重结算的解决方案

```
                ┌──────────────── 单人（整合服务器） ────────────────┐
玩家位置        客户端 Entity#move 注入（推出 / 车顶 / 载人）          ← 表现，位置由客户端权威
扣血/撞飞/死亡   server.ServerCollision（END_SERVER_TICK）            ← 唯一一份
生物            MobCollision（客户端入口注册的 END_SERVER_TICK）      ← 唯一一份

                ┌──────────────── 多人，服务端装了 LTE ─────────────┐
玩家位置        客户端照旧推（手感）
扣血/撞飞/死亡  ServerCollision（服务端 tick）
客户端伤害      关闭：ClientHandshake 收到 mtrlte:hello → remoteAuthoritative = true

                ┌──────────────── 多人，服务端没装 LTE ─────────────┐
玩家位置        客户端推
扣血/撞飞      客户端自己施加（老行为：只有受击表现 / 本地冲量，血量由服务器同步）
生物            不生效（没有人能改服务端生物的血量）
```

* `CollisionSystem#serverOwnsIt()` = `ServerAuthority.remoteAuthoritative()` **或** `client.getServer() != null`。
  为真时 `impact()` / `flight()` 直接返回 —— 既不扣血也不加冲量，避免「客户端加一份、服务端再加一份」。
* 单人为什么算「服务端权威」：`main` 入口在同一进程里跑，`ServerCollision` 每 tick 都在算；再把客户端的
  那一份算上就是双倍。
* 握手协议（`net/LteChannel`，通道 `mtrlte:hello`，version 1）：
  `C2S {version}` → `S2C {version, trainCollision, damage, mobs, highDamageMaxKmh, pushOnlyMaxKmh}`。
  客户端 JOIN 后发请求（前 5 tick 重试），服务端在 `ServerPlayConnectionEvents.JOIN` 也会主动推一次，
  发送前用 `ServerPlayNetworking.canSend(player, id)` 判断对方装没装（没装就不发，避免原版刷未知包）。
  版本不匹配 → 客户端忽略应答，回到纯客户端模式。
* 配置不一致：**以服务端为准**，客户端只打一行 warn
  （`[lte] server-side collision is authoritative: damage=… on the server, damage=… in this client's config`；
  高速档分界不同另有一条）。两边各读自己的 `config/mtrlte.json`。

---

## 3. 服务端类

| 文件 | 作用 |
|---|---|
| `server/LteServer.java` | `main` 入口：读配置、注册 tick / JOIN / 握手、标记 dedicated |
| `server/ServerCollision.java` | dedicated server 的权威碰撞：列车 → 车厢段 → 实体 → 推出 / 扣血 / 撞飞 |
| `server/ServerConfig.java` | 服务端 tick 读哪份配置（dedicated = 自己加载的文件；单人 = 客户端活对象） |
| `collision/CarGeometry.java` | 从 `CollisionSystem` 抽出的**纯计算**几何（Seg / Car / 分段 / 推出计算） |
| `net/LteChannel.java` | 通道 id + 两端共用的编解码 |
| `net/ServerAuthority.java` | 「谁说了算」的 volatile 标志 + 一次性的 warn |
| `net/ServerHandshake.java` | 服务端半边握手（common，无客户端类） |
| `net/ClientHandshake.java` | 客户端半边握手（`net.fabricmc.fabric.api.client.*`，只在客户端入口注册） |
| `server/ServerTrackbed.java` | 服务端道床：玩家 64 格内的物品 / 生物 / 下落方块吸附到轨面 |
| `server/MtrServerData.java` | 服务端轨道表（`RailwayData#getRailsMap()`，由 mixin 按维度登记） |
| `collision/RailBed.java` | 道床几何的纯计算部分（客户端 `Trackbed` 与服务端 `ServerTrackbed` 共用） |

玩家推不推：**默认不接管**。玩家位置是客户端权威的，客户端已经在推；服务端每 tick 也去 `requestTeleport`
就是抖动。只有在穿透 ≥ 0.6 格（没装 LTE 的客户端 / 丢包后卡进车体）时服务端才接管一次。
扣血、撞飞（`velocity + velocityModified` 同步给客户端）、死亡消息则始终由服务端结算。
生物（dedicated server）完全由服务端推。

排除项：创造 / 旁观 / 无敌 / 已死 / 有载具 / **MTR 列车上的乘客**（`Train#isPlayerRiding`，用反射调用，
因为 MTR 的 jar 未 remap、形参在字节码里是 `class_1657`）。

防御：每个实体、每列车、每次 MTR 调用各自 try/catch；`TrainTickCapture` 表空 → 什么也不做；
400 tick 内一列车都没登记到 → 一条 warn（`no MTR train was captured … version mismatch?`）；
`ServerCollision` 类本身引用不到 MTR 时，`LteServer` 的 tick lambda 里那层 `catch (Throwable)` 兜住。

---

## 3.5 服务端道床（物品 / 生物 / 下落方块）

客户端那条道床只作用于本地玩家（位置客户端权威）。其它实体在服务端改才有效，所以由
`server.ServerTrackbed` 每 tick 跑一遍：

```
RailBed 采样（RailwayData#getRailsMap()，与客户端 ClientData.RAILS 同构）
可站立面 = 轨面 + trackbedThickness(0.15)
resting : vy ≤ 0.02 且 脚底 ∈ [面−0.03, 面+0.10]
crossed : vy < 0 且 上一 tick 的 y ≥ 面−0.03 且 本 tick 的 y < 面     // 从上往下穿过
生效    : y 吸到面、vy 归零、fallDistance 清零、onGround=true、velocityModified
```

* **范围**：只在玩家 64 格内、且实体所在区块已加载；不做全图扫描。
* **开关**：`trackbedCollision`（总）× `trackbedCollisionItems`（物品 / 下落方块）×
  `trackbedCollisionEntities`（生物），默认全开。总开关关掉 → 全不生效；
  列车碰撞关掉时道床仍然工作（两者互不依赖）。
* **与列车碰撞的隔离**：被撞过的实体 10 tick 内（`ServerCollision.recentlyHit`）不吸附；
  `CollisionImpact` 的「飞出」状态也不吸附 —— 飞行中不该被轨面拽住。
* **不会从下面被吸上来**：`crossed` 要求「上一 tick 已经在轨面之上」，站在高架下方的实体永远不满足；
  再加一道 `MAX_DROP = 3` 格，已经在深处的不硬拽。
* **性能**：轨道缓存按 16 格格子锚定（`RailBed#nearestCell`），同一格的一堆掉落物共用一份缓存，
  重建频率是「每格每 20 tick 一次」，而不是「每个实体每 tick 一次」。
* **诊断**：每 5 秒一行 `[lte] trackbed: players=P items=I mobs=M`（这一窗接住的数量；全 0 不打）。

服务端的轨道表来源：`RailwayData` 没有公开的「按世界取实例」入口，所以 `RailwayDataCaptureMixin`
在 `RailwayData#simulateTrains()` 的 HEAD 登记 `this`，`MtrServerData` 再用一次反射读出它的私有
`world` 字段按维度建索引。反射失败 / MTR 换版本 → 道床实体碰撞静默关闭，列车碰撞不受影响。

---

## 4. 静态验证（服务端依赖扫描）

脚本：`tools/scan-server-deps.sh`（从 `dev.js.mtrheadlight.server.*` 出发，用 `javap -v` 读常量池，
沿 `dev/js/mtrheadlight/*` 递归，收集所有外部类名；命中 `net/minecraft/client/`、`mtr/client/`、
`mtr/render/`、`mtr/sound/` 或我们的渲染/界面类即失败）。

```bash
gradle -p v1.20.1 compileJava
bash tools/scan-server-deps.sh "C:/Users/AD/AppData/Local/Temp/lte-classes"   # build/classes/java/main 的副本
```

结果（可达闭包，28 个类，全部是两端通用的）：

```
collision.CarGeometry(+Car/Push/Seg)  collision.CollisionImpact(+State/Tier)
collision.RailBed(+Entry/Sample)      collision.TrainImpactDamageSource
collision.TrainTickCapture            HeadlightConfig(+Effective/SidingSettings)
LteLog                                net.LteChannel(+ServerHello)
net.ServerAuthority                   net.ServerHandshake
server.LteServer                      server.MtrServerData
server.ServerCollision                server.ServerConfig
server.ServerTrackbed                 TrainPose(+1/CachedPose)
=== verdict ===
  OK - no net.minecraft.client.* / mtr.client.* / rendering class reachable
```

外部引用里只有：`java.*`、`com.google.gson.*`、`com.mojang.authlib.GameProfile`、
`net.fabricmc.api.ModInitializer`、`net.fabricmc.fabric.api.event/lifecycle/networking.**`（服务端侧）、
`net.minecraft.{entity,server,network,text,util.math,world}.**`、`mtr.data.{Train,Rail,TransportMode}`、
`mtr.path.PathData`、`org.slf4j.*`。

补充：`jdeps` 对成品 jar 的同一检查

```bash
jdeps -verbose:class --ignore-missing-deps v1.20.1/build/libs/lte-1.0.0.jar \
  | grep -E "^\s+dev\.js\.mtrheadlight\.server\." | grep -c client      # -> 0
```
`net.*` 里唯一引用 `net.fabricmc.fabric.api.client.**` 的是 `net/ClientHandshake`
（**只在 client 入口注册**，dedicated server 上不会被加载）。

顺带修掉的两个真问题（扫描发现）：

* `HeadlightConfig.sanitized()` 原来引用 `RailChunkBuildBoost.MAX_PER_FRAME` —— 那是个客户端渲染类，
  而配置类会被服务端入口加载。改成同值的本地常量 `MAX_RAIL_CHUNK_BUILD_PER_FRAME = 8`。
* `HeadlightConfig` 的日志原本走 `MtrHeadlightClient.LOGGER`（client 入口类）。改成 common 的 `LteLog`。
* 之前 `@Mixin(LivingEntity.class) @Accessor("timeUntilRegen")` 编译不过：字段声明在 `Entity` 上且是
  `public`。删掉 accessor，`LivingEntityAccess` 直接赋值 —— 顺带让「清零受击窗口」在服务端也生效
  （原来那条只在客户端 mixin 列表里，dedicated server 上等于伤害被无敌帧吞掉）。

### Mixin 描述符核对（服务端注入能不能命中）

```
javap -p -s dev/js/mtrheadlight/mixin/mtrserver/TrainSimulateCaptureMixin.class
  private void mtrheadlight$capture(net.minecraft.class_1937, float, mtr.data.Depot,
      mtr.data.DataCache, java.util.List<?>, java.util.Map<?,?>, java.util.Map<?,?>,
      java.util.Map<?,?>, CallbackInfoReturnable<Boolean>);
```

MTR 3.6.4.2：`public boolean simulateTrain(class_1937, float, Depot, DataCache, List, Map, Map, Map)`
—— 8 个参数 + 返回值一一对应，`remap = false` 下描术符已经是运行期名（`class_1937`）。

---

## 5. 运行时证据 / 没能在此验证的部分

已观察到的（真实客户端实例 `…/mcboot-rain/game/logs/latest.log`，13:13）：

```
[lte] server initialised (collision=true damage=true mobs=true)
[lte] client initialised (head=15 tail=9 len=11.0/4.0)
```

即 `main` 入口 + client 入口在同一个进程里都跑起来了，没有异常、没有重复结算报错。

**只能实机（多人）验证的：**

1. dedicated server 启动：本机没有 MC 1.20.1 服务端 jar / fabric-server launcher，网络取版本清单超时，
   15 分钟内搭不起来 → **没跑**。静态扫描保证「没有客户端类进入服务端闭包」，但
   「Mixin 配置在 dedicated server 上是否有其它模组冲突」只有真开一次服才知道。
2. 服务端权威的实际手感：扣血速度、撞飞冲量（`velocityModified` + 传送包）、死亡消息两段文案在聊天栏/
   死亡屏的显示。需要「装 LTE 的服务端 + 两个客户端（一个装 LTE、一个原版）」。
3. 握手时序：真实延迟下 `mtrlte:hello` 前 5 tick 重试够不够；服务端无 LTE 时客户端是否完全没有日志噪音。
4. `Train#isPlayerRiding` 反射：MTR 若在别的版本里改了方法名/签名，这里会静默变成「不排除乘客」。
5. 生物（村民 / 动物）在 dedicated server 上被推、被撞飞、被撞死的实际表现。
6. **道床对实体的吸附**：玩家丢在轨道上的物品是否稳稳停住（不弹跳、不掉进虚空）、下落的沙子是否
   在道床上变成方块、生物会不会在高架下方被误吸 —— 需要真实轨道 + 真实实体，只能实机看。
7. 道床的 CPU 开销：`RailBed` 每格每 20 tick 重建一次整张轨道表的扫描，轨道数量极大的存档里
   需要看 MSPT。
6. 与 `MobCollision`（单人生物）在同一 tick 里的顺序：两者互斥（`server.isDedicated()` 分流），
   但单人里 `CollisionImpact` 的静态状态是共享的，值得跑一次「单人撞生物 + 撞玩家」确认冷却互不干扰。

---

## 6. 已知取舍

* 服务端**不做车顶载人 / 道床抬升**：那是玩家位置的客户端权威部分（`Ticket`），服务端去改只会抖动。
* 服务端不读客户端配置：两边各读自己的 `config/mtrlte.json`，伤害相关不一致时以服务端为准并 warn。
* `CarGeometry` 已经是唯一实现，但 `CollisionSystem` 与 `MobCollision` 里仍留有各自的历史副本
  （并行任务正在改那两个文件，本轮不动它们）。后续应删掉副本、统一调用 `CarGeometry`。
