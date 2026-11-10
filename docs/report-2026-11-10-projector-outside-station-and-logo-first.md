# 报告：站外投影机绑定（诊断 + 渲染兜底）与「厦门风格物件」页签顺序

日期：2026-11-10 · 版本：1.20.1 · jar `lte-1.0.21`

## A. 站外投影机

### 结论先说

**写入从来没问题，是渲染被 MTR 挡住了。** `setData(Set<Long>, int)` 确实把 platformIds 写进去了
（javap 反编译确认它内部只做 `clear → addAll → displayPage → markDirty → syncData`，**没有任何内部逻辑会重置它**），
但 MTR 的渲染入口 `mtr.render.RenderPIDS#getSchedules` 第一件事是：

```
0: RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)   // pos = 投影机自己
12: if (station == null) return new ArrayList<>();
```

投影机站在车站矩形外 → `getStation` 返回 null → 直接返回空表 → 屏幕上什么都不显示。
玩家看到的就是「绑不上」。所以降级方案不是「只存自己的表」，而是**只需要换掉这一次 getStation 调用**。

### 三步逐条

| 阶段 | 结果 | 证据 |
|---|---|---|
| ① 识别 | 正常。投影机方块实体 `mtr.block.BlockArrivalProjectorBase$TileEntityArrivalProjectorBase`；四种尺寸的方块实体（`BlockArrivalProjector1Small/Medium/Large$TileEntityArrivalProjector1*`）都是它的子类，`MtrTiles.isProjector` 走**超类链精确名**比对，命中的就是基类名 | `javap -p` 类头 |
| ② 写入 | 正常。`public void setData(java.util.Set<java.lang.Long>, int)` 存在且 public；字节码 `Set.clear / Set.addAll / putfield displayPage / markDirty / syncData`，**无重置** | `javap -p -c` |
| ③ 解析 | 三级判定逻辑本身成立，但站外场景三级都可能落空（取决于点位），现在每级都打日志 | 见下 |

### 新增诊断（逐次右键，一行一条）

```
[lte] projector click: pos=x,y,z kind=<projector|node|other> stationAt=<id/name|null> platformId=<n> tile=<类名链>
[lte] projector click: branch=projector pos=... sneaking=... hasStation=... hasPlatform=...
[lte] projector click: branch=station pos=... platformId=... station=... name=... pendingProjector=...
[lte] rail lookup[projector]: pos=... s1(getClosePlatformId)=0 s2=0 s3(railWalk)=0 MISS
[lte] rail walk[projector]: start=... visited=... no platform within 256 nodes / 256 blocks
[lte] projector setData: tile=<链> wrote N ids=[...] page=K
[lte] bind: readback platforms=[...] empty=false
[lte] bind: pushed to clients=true
[lte] render pids: pos=... outside any station -> using station id/name from platform P of N bound ids
```

`s1/s2/s3` 就是 `platformIdAt`（MTR `getClosePlatformId`，±5 格）→ `platformIdAtExact`（`getPlatformByPos`）
→ 沿 `getRailsMap()` 的 BFS。BFS 失败会额外说明是「railsMap 为空」「附近 3 格没有轨道节点」还是
「走完 256 个节点都没碰到站台」。

### 修复：客户端 `RenderPIDSStationMixin`

`mtrlte.mtr.mixins.json` 的 `client` 列表新增 `RenderPIDSStationMixin`：

```java
@Redirect(method = "getSchedules",
          at = @At(value = "INVOKE", target = "Lmtr/data/RailwayData;getStation"),
          remap = false)
```

* 投影机真的在车站里 → 原样返回（MTR 行为完全不变，PIDS 也完全不变）；
* 站外 + 是投影机 + platformIds 非空 → 用 `DataCache.platformIdToStation` 把「第一个能反查到车站的
  站台」换成那座车站，MTR 自己的 `requestStationIdToPlatforms` + platformIds 过滤照常跑；
* 其它情况 → null（保持原行为）。异常全部吞掉（渲染线程不能抛）。

**静态校验**（没有启动游戏，按要求）：编译后 `javap -p -v` 确认注入处理器的描述符是
`(Ljava/util/Set;Lmtr/data/DataCache;Lnet/minecraft/class_2338;)Lmtr/data/Station;` —— 与 MTR 运行时
（intermediary `class_2338`）一致；`@At` 目标串为 `Lmtr/data/RailwayData;getStation`；
`getSchedules` 里对 `getStation` 的 INVOKESTATIC 只有一处。注入找不到时 `defaultRequire: 0` +
`required: false` 兜底，最坏退回 MTR 原行为，不会崩游戏。

### 同时改掉的

* 绑定成功后不再发「MTR 按投影机位置找车站，屏幕会是空的」那条黄色警告（现在是错的）。
* `MtrTiles.isProjector` 增加「简单名含 `TileEntityArrivalProjector`」的兜底，防止分支/移植改了内部类位置。
* `MtrTiles.projectorSetPlatforms` 失败时打印具体原因（不是投影机 / 没有 getDisplayPage / setData 抛异常 + 异常类与消息），
  不再统一报成「投影机没了」。
* `LteMtrLookup.platformIdOnRail` 增加带 tag 的重载（`projector` / `pids` / `resolve`），三级全打日志。

## B. 「厦门风格物件」页签顺序

`XiamenObjects#register` 里的 `ItemGroupEvents.modifyEntriesEvent(...)`：`entries.add` 是**追加到末尾**，
所以加的顺序就是页签里从左到右的顺序。原来 `door → glass → end → logo`，把 `entries.add(logo)` 提到第一个：

```java
.register(entries -> {
    entries.add(logo);      // ← 厦门地铁标志，第一位
    entries.add(psdDoor);
    entries.add(psdGlass);
    entries.add(psdEnd);
});
```

（`v1.20.1/.../xiamen/XiamenObjects.java` 第 163 行一带。这是该页签唯一的 `modifyEntriesEvent` 注册点。）

## 产物

* `BUILD SUCCESSFUL`（gradle 8.14 / JDK 21）
* `桌面/lte-1.0.21+1.20.1.jar`
* `PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.21.jar`（旧的 `lte-1.0.20.jar` 已移除）
* 三份 sha256 一致：`c2eff3a9081d7d05065d3d25669f5dc5562076ff4b739d5d4d406a9cd8c0a69f`
