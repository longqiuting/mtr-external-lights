# PIDS / 到站投影机「过一会儿自己熄屏」的机制与修法

对象：MTR 3.6.4.2 / 1.20.1（`libs/MTR-fabric-1.20.1-3.6.4.2.jar`，证据来自
`MTR-fabric-1.20.1-3.6.4.2-named.jar` 的 `javap -p -c`）。
症状：站外 / 跨站绑定的 PIDS、到站投影机「亮一会儿灭一会儿」，走近才亮，站着不动一会儿就灭。

结论先说：**不是渲染缓存，也不是我们那次 64 格推送的时机 —— 是 MTR 的到站数据是
「按玩家位置过滤」并且「整表替换」的**。渲染缓存（`RenderPIDS`）根本没有缓存。

---

## 1. 渲染这一层：`RenderPIDS` 没有任何缓存

```
RenderPIDS.getSchedules(T be, BlockPos pos, Map<Long,String>)
   0: RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)
  12: if (station == null) return new ArrayList<>();          // 空屏
  25: DATA_CACHE.requestStationIdToPlatforms(station.id)
  38: if (map.isEmpty()) return new ArrayList<>();             // 空屏
  96: platformIds = be.getPlatformIds()                        // 投影机 / PIDS 分支
 254: 遍历 station 的站台，按 platformIds 过滤，读 ClientData.SCHEDULES_FOR_PLATFORM
```

`javap` 看 `getSchedules` 与 `render`：**没有任何字段读写做缓存**，`render` 每帧调一次
`getSchedules`（`invokevirtual RenderPIDS.getSchedules`，位于 `render` 的 220 偏移处）。
所以「这一帧算不出来」＝「这一帧屏幕黑」。

`RenderPIDS.render` 开头只有两种剔除：

```
shouldNotRender(pos, Math.min(16, RenderTrains.maxTrainRenderDistance), rotate90 ? null : facing)
```

即 **16 格之外整块 PIDS 不画**（`RenderTrains.maxTrainRenderDistance` 在开
`Config.useDynamicFPS()` 时会被 MTR 动态调整，但下限是 32，所以这里恒为 16）。
这一条解释了「从远处走过来才开始有画面」。

`TileEntityPIDS` 里确实有两个缓存字段，但**只在「没绑定」时用**：

```java
private long cachedRefreshTime;    // 自动模式：最近站台的计算时间
private long cachedPlatformId;     // 自动模式：最近站台的 id
public long getPlatformId(Set<Platform> platforms, DataCache cache) {
    if (cache.needsRefresh(cachedRefreshTime)) { cachedPlatformId = RailwayData.getClosePlatformId(...); ... }
}
```
绑定了（`platformIds` 非空）就完全不走它，所以它不是本问题的原因。

## 2. 数据这一层（真正的原因）

服务端 `RailwayData.simulateTrains` 每 tick 给玩家发 `PACKET_UPDATE_SCHEDULE`，**包里的站台是过滤过的**：

```java
// RailwayData.lambda$simulateTrains$7(BlockPos playerPos, Platform platform)
platform.isCloseToSavedRail(playerPos, 16, 16, 16)                       // 玩家 16 格以内
    || (platformIdToStation.get(platform.id) != null
        && station.inArea(playerPos.getX(), playerPos.getZ()))           // 玩家站在该站台所属车站里
```

客户端收到后**整表替换**：

```java
// ClientData.lambda$updateSchedule$17
clearAndAddAll(SCHEDULES_FOR_PLATFORM, newMap);   // clear() + putAll()
```

而发送节奏是：`SCHEDULE_UPDATE_TICKS = 60`（每 3 秒给每个玩家排一次队），玩家移动
`PLAYER_MOVE_UPDATE_THRESHOLD = 16` 格还会更早排；每个 tick 只出队一个玩家
（`playersToSyncSchedules.remove(0)`）。

**于是站外显示屏必然周期性黑屏**：它既不在任何车站矩形里（玩家站在它面前时
`station.inArea` 为假），绑定到的那座车站的站台通常也离它有 16 格以上
（`isCloseToSavedRail` 为假）→ 下一个包（≤3 秒）里没有它的站台
→ `SCHEDULES_FOR_PLATFORM` 被清成不含它 → 屏幕空。

玩家看到的就是：

| 阶段 | 发生了什么 |
|---|---|
| 从远处靠近 | 16 格内 `RenderPIDS` 才开始画；路上穿过站台 / 车站时包里带上了目标站台，于是「亮」 |
| 站到屏幕前不动 | 玩家已离开站台 16 格 / 车站矩形，3 秒内的下一个包把该站台的数据删掉 → **熄屏** |
| 走开再回来 | 再次经过站台 / 车站，包又带一次 → 看起来「自己恢复了」 |

顺带解释「有概率较长时间不工作」：停在任一「不在 16 格内、也不在车站矩形内」的位置，
它就一直不带数据，所以熄屏会持续到你再次走进那两个范围为止。

## 3. `platformIdToStation` 也不是可靠的每帧数据源

`DataCache.sync()` 里：

```java
mapSavedRailIdToStation(platformIdToStation, platforms, stations);  // 第一条指令就是 Map.clear()
...
blockPosToPlatformId.clear();
blockPosToStation.clear();
lastRefreshedTime = System.currentTimeMillis();
```
`sync()` 由 `ClientData.receivePacket`（服务端任意一次数据广播）与
`DataUpdateBroadcast.broadcastDataUpdate` 触发，`mapSavedRailIdToStation` 失败时外层只
`printStackTrace()`。所以渲染时每帧去查这张派生表，是有「某一帧查不到 → 灭一下」的风险的。
虽然它**不是**本次周期性熄屏的主因，但确实是同一症状的第二个来源，一并修掉。

## 4. 改了什么

| 位置 | 作用 |
|---|---|
| `bind/PidsStationIndex` | 客户端 `platformId -> Station` **粘性**索引：查到就记住；先问 `platformIdToStation`（`src=mtr`），拿不到就用权威集合 `platformIdMap` + `Station.inArea(站台中点)` 自己算（`src=ours`）。失败有 500 ms 重试节流。 |
| `mixin/mtr/RenderPIDSStationMixin` | 站外反查改走上面的索引；另外处理「方块在 A 站、绑的却是 B 站站台」（MTR 会拿 A 的站台列表过滤，必然空屏）。日志只在变化时打：`[lte] pids render: pos=... station=<id|null> src=mtr\|ours platformIds=[...]` |
| `bind/PidsScheduleScope` + `mixin/mtrserver/PidsScheduleScopeMixin` | **主修**：把「绑定过的显示屏」的站台加进该玩家的到站数据范围。`SavedRailBase#isCloseToSavedRail` 在 MTR 里只有 `simulateTrains` 一个调用点，`@Inject(RETURN)` 里问一句「玩家是不是就站在某块绑定到该站台的屏旁边」，是就返回 `true`。索引每 20 tick 从 `PidsLinkStore` / `ProjectorLinkStore` + 方块实体里的真实 `platformIds` 重建。 |
| `bind/DisplayPushTicker` | 兜底：每 20 tick 扫绑定表，玩家**新进入** 64 格范围时重推一次方块实体 NBT（`setData` 的 `syncData()` 只排「下个 tick 给正在跟踪该 chunk 的玩家」的队列，不保证人在里面）。 |

## 5. 验证方式（客户端日志）

```
[lte] schedule scope: N platform(s) covered by bound displays     # 索引建起来了
[lte] pids render: pos=... station=... src=ours platformIds=[...]  # 站外反查成功
[lte] pids push: pos=... re-sent to K player(s) entering range     # 进范围补推
```

若出现

```
[lte] schedule scope: bound displays exist but MTR never asked (isCloseToSavedRail mixin not applied?)
```

说明 MTR 换了版本 / 方法改名，`PidsScheduleScopeMixin` 没应用上 —— 这时行为退回 MTR 原版
（站外显示屏仍会周期性熄灭），日志里说清楚而不是静默。
