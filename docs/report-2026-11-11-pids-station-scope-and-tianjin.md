# PIDS 连接器：绑定扩散 + 天津地铁无效

对象 MTR 3.6.4.2 / 1.20.1（`libs/MTR-fabric-1.20.1-3.6.4.2-named.jar`）与
天津地铁 1.1.3（`TianjinMetro-fabric-1.20.1-1.1.3.jar`），证据均来自 `javap -p -c`。

## A. 「绑定一次后，新放的 PIDS 自动绑到那个站台」

### 读的这一侧是干净的（javap + 代码核对）

`RenderPIDS.getSchedules(T be, BlockPos pos, Map)` 是从 `render()` 里
`getSchedules(be, be.getPos(), map)` 调进来的，`platformIds` 取自 **参数 be**：

```
127: aload_1; instanceof IPIDS$TileEntityPIDS; getPlatformIds()   // PIDS 分支
 96: ... TileEntityArrivalProjectorBase.getPlatformIds()           // 投影机分支
```

`IPIDS$TileEntityPIDS` 的 `platformIds` 是 **实例字段**
（`private final Set<Long> platformIds`，无 static 版本），所以不存在「一块屏记住另一块屏」的
服务端/客户端共用的数据。旧 `RenderPIDSStationMixin` 也是每帧
`world.getBlockEntity(pos)` 现读，`PidsStationIndex` 的粘性缓存键是 **platformId**
（站台→车站，与屏无关），`PidsScheduleScope` 只影响「给玩家发哪些站台的到站数据」，不参与
车站判定。三条怀疑点全部排除。

### 真正的毛病在写入端：一次绑定写了一整片屏

旧 `BindTargets.pidsAt(pos)` 收「点击格 + **同方块类的 6 邻居**」，
`PidsConnectorItem.bind` 再把同一个 platformId 写进**每一个**返回的方块实体：

- `BindTargets.java`（旧 `pidsAt` / `addPids`）：`world.getBlockState(n).getBlock() == block` 就算同一台；
- `PidsConnectorItem.java` 的 `for (PidsTile entry : pids) pidsBindSingle(...)`。

「同方块类」并不等于「同一台屏」：两块**独立**的 PIDS 挨着摆 / 上下叠时方块类完全一样
（MTR 的 `BlockPIDS1/2/3` 都是同一个方块的 UPPER/LOWER 两半；天津的
`pids_tianjin` 按 `FACING` 沿墙成对，`pids_tianjin_single` 更是单格排成一排）。于是
「绑一块」=「绑一片」，玩家在已绑定的屏旁边加一块，下一次绑定时新屏也被写进同一个站台，
看起来就是「绑定一次之后，新放的屏自己就被绑到那个站台」。

### 改法：只认「这一台屏」

- `BindTargets.dataPos(world, pos)`：数据格由方块自己给 —— 天津/JCM 有
  `getBlockPosWithData(BlockView, BlockPos)`（javap：`canStoreData` = FACING ∈ {NORTH, WEST}，
  否则取对面那一格），反射问它；MTR 原版按 `IBlock.HALF` 把 LOWER 归到上面那格
  （`RenderPIDS` 只在 UPPER 渲染，MTR 自己的 PIDS 界面也写 `pos.above()`）。
- `BindTargets.pidsAt`：只收「数据格 + 朝另一半方向的那一格（同方块且 HALF 相反）」，
  **不再扫 6 邻居**。上下叠着的两块独立屏不会再被当成一台。
- `PidsConnectorItem` / `LteBindCommand`：绑定表键统一用 `dataPos`，点哪一半都一样。
- 读侧（新 `PidsStationResolver`）+ 日志：唯一输入是「这一格 BE 的 platformIds」；
  空的就原样返回 MTR 的答案（= 自动模式），任何看起来像「上一块屏的车站」的答案都不可能产生。

日志（变化时才打）：

```
[lte] pids station: pos=... be=[...] -> station=<id/名字|null> src=be|mtr
[lte] bind: pids verify platforms=[...] tiles=[...]
```

`be=[] src=mtr` = 这块屏没绑过；两块屏打出同一个 `be` 才说明**写入**把它们写成了一样。

## B. 连接器对天津地铁 PIDS 无效

### 识别本来就通，坏在渲染

- `TileEntityBlockPIDSTianjin extends mtr.data.IPIDS$TileEntityPIDS`（javap 确认），
  所以旧的类名链判定其实能认出来；现在改成 **`instanceof mtr.data.IPIDS.TileEntityPIDS`**
  为主、类名链兜底（`MtrTiles.isPids`），三家通用且不依赖类名字符串。
  `setData(String[], boolean[], Set<Long>, int)` 天津也覆写了，内部 `super.setData` +
  `markDirty` + `syncData`（javap），写入没问题。
- 真正的原因是**渲染**：天津自带 `ziyue.tjmetro.render.RenderPIDSTianjin`，
  自己抄了一份 `getSchedules`，第 0 条指令就是
  `RailwayData.getStation(STATIONS, DATA_CACHE, pos)`，`station == null` 直接返回空表。
  我们原来只往 `mtr.render.RenderPIDS` 注入，**碰不到这个类**，于是站外 / 跨站绑定对天津屏无效。
  （`RenderPIDSTianjinSingle extends RenderPIDSTianjin`，注入基类即覆盖两种型号。）

### 改法

新增 `RenderPidsTianjinStationMixin`（`@Mixin(targets = "ziyue.tjmetro.render.RenderPIDSTianjin")`），
把同一套 `PidsStationResolver` 判定接到天津的 `getSchedules` 上。目标类只在装了天津地铁时存在，
由 `MtrMixinPlugin` + `HostProbe.tjPidsRenderer()` 探测，没装就整条不应用。

## 验收

`gradle -p v1.20.1 build` → BUILD SUCCESSFUL；jar 已放到桌面与 `temp/mcrun/mods/`
（`lte-1.0.32.jar`，三处 md5 一致）。未启动游戏。
