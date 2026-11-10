# 连接器三件套：节点高亮 / 投影机站外绑定 / PIDS 绑定消失

日期：2026-11-10 · 版本：1.20.1（`lte-1.0.19.jar`）· 证据来源：`libs/MTR-fabric-1.20.1-3.6.4.2.jar`
（CFR 反编译）与 `~/.gradle/caches/fabric-loom/1.20.1/.../mappings.tiny`（yarn 1.20.1+build.10）。

---

## A. 手持投影机 / PIDS 连接器也高亮轨道节点

**改动**：`signal/SignalNodeHighlight.java` → `signal/ConnectorNodeHighlight.java`（旧文件删除），
`RenderHooks` 注册点改成 `ConnectorNodeHighlight::render`。

- 手持判定：`held(player)` 依次看主手、副手有没有 `signal_linker` / `projector_connector` /
  `pids_connector`，命中就返回（信号连接器优先，因为它多一个「已选节点」状态）。
- **绘制完全复用**：同一个 `AFTER_TRANSLUCENT` 回调、同一份 `Scratch` 小顶堆、同一套参数
  （`radius = options.getViewDistance() * 16`、`FRUSTUM_DOT = 0.2`、`NEAR_HOLD = 4`、
  `MAX_NODES = 4000`、`WorldRenderer.drawBox` + 单个 `VertexConsumer`、**一帧一次 `draw(LINES)`**）。
  多判两个物品不增加任何一次 draw，也不增加一次 Map 遍历。
- 只有拿**信号连接器**时才把 `SignalLinkItem.selectedNode(held.stack)` 那颗画青色；另外两件没有选区
  概念（`selected = null`），所以全是白框。
- 诊断行加了物品名：`[lte] node marks: item=<signal|projector|pids> nodes=N drawn=D range=R`。

---

## B. 投影机在站外绑定不成功

**原因**：`onStationClicked` 走的是 `LteMtrLookup.stationNearRail(...)`，而它是
`stationAt(点到的位置)` → 失败后 `platformIdOnRail → stationOfPlatform`。
站外点轨道节点时第一步必然空（`Station#inArea` 只认车站矩形），第二步又被
`DataCache.platformIdToStation` 卡住 —— 这张表是 `DataCache#sync` 时才建的，站外/刚划完站台的
情况下查不到，几何兜底（站台中点落不落在车站矩形里）同样为空，于是整体返回 `null`，
玩家看到的就是「这里推不出车站」。

**改法**：和 PIDS 完全同一套解析顺序（复用 `LteMtrLookup.platformIdOnRail`，一行没新写）：
`getClosePlatformId`（±5 格）→ `getPlatformByPos`（这一格就是站台轨道）→ **沿
`RailwayData#getRailsMap()` 做 BFS**（256 节点 / 256 格上限）走到第一个站台；再由站台反推车站
（`stationOfPlatform`，同样先查 `platformIdToStation`、查不到按几何判）。
**解析到站台就算成功**，车站推不出来也照样绑（把那个站台 id 写进去）。

`ProjectorConnectorItem` 的变化：

| 现在 | 以前 |
|---|---|
| 待绑定状态记 `lte_platform_id` + `lte_station_id`（可能为 0）+ 点击格 + 名字 | 只记 `lte_station_id` |
| `resolve()` 四级回退：点击格重算 → `stationById` → 点击格重跑 `platformIdOnRail` → `stationOfPlatform` | `stationAt(点击格)` → `stationById` |
| `bind()` 写 `platformIdsOf(station)`；车站为空（或车站名下没有站台）时写解析到的那**一个**站台 | 车站为 null 直接失败 |

**MTR 的 `setData` 到底接受什么（javap 证据）**

```
$ javap -p -classpath libs/MTR-fabric-1.20.1-3.6.4.2.jar \
    'mtr.block.BlockArrivalProjectorBase$TileEntityArrivalProjectorBase'

private final java.util.Set<java.lang.Long> platformIds;
private int displayPage;
public java.util.Set<java.lang.Long> getPlatformIds();
public int getDisplayPage();
public void setData(java.util.Set<java.lang.Long>, int);
```

**只有 `platformIds` + `displayPage`，没有 station 参数、没有车站字段。** 所以「投影机绑到车站」
在 MTR 的数据模型里只能是「把该车站名下的站台 id 写进 platformIds」——
`ProjectorLinkStore` 那张自己记的「pos → stationId」表只是为了 `/ltebind projector` 能反查，
MTR 一个字节都不读它。绑定动作本身没有副作用（`setData` 内部 `markDirty()` + `syncData()`）。

站外仍然不显示到站：`RenderPIDS#getSchedules` 第一件事就是
`RailwayData.getStation(ClientData.STATIONS, DATA_CACHE, pos)`，`pos` 是**投影机自己的位置**，
为 null 直接返回空表。这是 MTR 的模型限制，绑定成功后会黄字提示（新增
`message.mtrlte.projector.outside_station`）。

---

## C. PIDS 绑定后过一会儿数据「消失」

### 逐条排查（都有代码/javap 证据）

**① 「被 MTR 自己的刷新逻辑覆盖」—— 不成立（渲染侧只读）。**
`RenderPIDS#getSchedules`（CFR 398–442 行）只做三件事：按位置找车站 → 取该车站的站台表 →
用 `platformIds` 过滤。全 jar grep `platformIds.clear()` 只有
`IPIDS$TileEntityPIDS#readCompoundTag`（第 59 行）和 `setData`（第 86 行），
`readCompoundTag` 只在**区块加载**和**收到 `BlockEntityUpdateS2CPacket`** 时被
`BlockEntityMapper#readNbt`（`method_11014`）调到。**MTR 没有任何周期性代码会清空它。**

**② 「`PersistentState` 没标脏」—— 不成立（两边都查了）。**
- 我们自己的表：`PidsLinkStore.put/remove` 本来就有 `markDirty()`（第 99 / 104 行），
  `ProjectorLinkStore` 同样。
- MTR 的方块实体：`setData` 里 `this.method_5431()` —— mappings.tiny 第 43032 行
  `method_5431 = markDirty`；`BlockEntity#markDirty` 的字节码是
  `World.markDirty(pos)`，而 `World#markDirty` 的字节码是
  `if (isChunkLoaded(pos)) getWorldChunk(pos).setNeedsSaving(true)`。**落盘链是通的。**

**③ 「只在客户端写了 / 客户端没收到」—— 这就是真因，但方向是「客户端从没收到」。**

证据链：

1. `setData` 的第二件事是 `syncData()`（`method_5431` 之后），而
   `mtr.mappings.BlockEntityClientSerializableMapper#syncData()` 的实现是
   ```java
   if (this.world instanceof ServerWorld) ((ServerWorld) world).getChunkManager().markForUpdate(pos);
   ```
2. `ServerChunkManager#markForUpdate(BlockPos)` 的字节码（yarn 1.20.1 的 MC jar）：
   ```
   ChunkHolder holder = getChunkHolder(ChunkPos.toLong(sectionX, sectionZ));
   if (holder != null) holder.markForBlockUpdate(pos);
   ```
   也就是**只是把这一格排进「下个 tick 广播给正在跟踪该 chunk 的玩家」的队列**，
   它不保证刚点完连接器的这个玩家就在那份跟踪集合里。
3. 我们原来的绑定代码里**没有任何一处显式推送**。客户端方块实体的 `platformIds` 因此仍是空集合。
4. 而 MTR 的 PIDS 显示对空集合走的是**自动模式**（`RenderPIDS#getSchedules` 421–422 行）：
   ```java
   Set<Long> temp = ((IPIDS.TileEntityPIDS) entity).getPlatformIds();
   yield temp.isEmpty()
       ? Collections.singleton(((IPIDS.TileEntityPIDS) entity).getPlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE))
       : temp;
   ```
   空 → 就近站台 → **屏幕上是一块「没绑过」的 PIDS**。`getPlatformId` 还带客户端缓存
   （`cachedRefreshTime` / `cachedPlatformId`，`DataCache#needsRefresh` 才重算），
   所以这个「未绑定」的样子会稳定存在、而不是闪一下。
5. 为什么感觉是「过一会儿」才变回去：刚绑完客户端的数据缓存（`ClientData.STATIONS` /
   `DATA_CACHE`）往往还没热，`getSchedules` 直接返回空表（白屏）；等数据同步落地的下一帧，
   空 `platformIds` 的自动模式把全部到站刷出来 —— 和「从没绑定过」一模一样，落在这个时间点。

**结论**：服务端那份一直是好的（所以 `/ltebind pids` 能看到值、重进世界也在），
掉的是**客户端那一份**，而 MTR 的渲染把「客户端空集合」显示成「未绑定」。
另外还有第二个真实的「看起来没绑」的原因：写进去的站台不属于「PIDS 所在位置那个车站」时，
`getSchedules` 用那个车站的站台表过滤，一个都对不上 → 空白（现在会黄字提示）。

### 修法（每次绑定做四件事）

1. **服务端写**：`MtrTiles.pidsSetPlatforms` → MTR 的 `setData(...)`（唯一合法入口，
   消息 / 隐藏位 / 翻页原样读出来再写回去，玩家的自定义设置不会被冲掉）。
2. **客户端写**：新增 `MtrTiles.pushToClients(ServerWorld, tile, pos)` ——
   用 `BlockEntityUpdateS2CPacket.create(be)` 把**整份 NBT**直接推给 64 格内每个玩家，
   不再依赖 `markForUpdate` 那条 tick 队列。该包走 `toInitialChunkDataNbt()`，而 MTR 的
   `BlockEntityClientSerializableMapper#toInitialChunkDataNbt()` 会把 `writeCompoundTag()`
   的内容（含 `platform_ids`）拼进去，所以客户端拿到的就是刚写进去的那份。
   - 关于 `tile instanceof BlockEntity`：静态类型是 `Object`，向下转型永远合法；而且
     **产物 jar 里这条指令引用的是 `net/minecraft/class_2586`**（与 MTR 同款），
     `grep -a` 已核对，所以运行期成立 —— 与 `BlockEntity instanceof TileEntityPIDS`
     那个「指向另一个 `class_2586`」的坑不是一回事。
3. **落盘**：新增 `MtrTiles.markChunkSaved(world, pos)` → `WorldChunk#setNeedsSaving(true)`，
   补在 `setData` 的 `markDirty` 之外，不再依赖反编译出来的实现细节。
4. **回读校验**：写完立刻 `getPlatformIds()` 读一遍，日志
   `[lte] bind: pids verify platforms=[...]`（`TreeSet`，两半的并集）；
   读不回来就红字 `message.mtrlte.pids.verify_failed` 并把方块实体类名链打给玩家，
   不假装成功。

投影机那条路同样加了 ②③④（日志为 `[lte] bind: projector verify platforms=[...]`）。

---

## 改动文件

| 文件 | 内容 |
|---|---|
| `signal/ConnectorNodeHighlight.java` | 新增（旧 `SignalNodeHighlight.java` 删除），三连接器共用一套节点高亮 |
| `RenderHooks.java` | 注册点改名 |
| `bind/MtrTiles.java` | `pushToClients` / `markChunkSaved`（显式推包 + 标脏），注释写明为何能 `instanceof BlockEntity` |
| `bind/PidsConnectorItem.java` | 双端写 + 落盘 + 回读校验 + 车站/站台不匹配提示；类注释补 C 的完整成因 |
| `bind/ProjectorConnectorItem.java` | 站台优先解析（`platformIdOnRail` 三级 + BFS）、`resolve()` 四级回退、站外也绑、写后推包与校验 |
| `lang/zh_cn.json`、`lang/en_us.json` | 新增 `projector.selected_platform`、`projector.outside_station`、`pids.platform_mismatch`、`pids.verify_failed` |

## 验收

```
BUILD SUCCESSFUL in 11s
v1.20.1/build/libs/lte-1.0.19.jar  986646 字节
→ C:\Users\AD\Desktop\lte-1.0.19.jar
→ C:\Users\AD\Desktop\lte-1.0.19+1.20.1.jar
→ <PCL>/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.19.jar   （三者 md5 一致 6741bf21…）
```

jar 内已核对：`ConnectorNodeHighlight.class` 存在、`MtrTiles` 引用 `class_2622/class_2586/class_3218`、
`ProjectorConnectorItem` 含 `lte_platform_id` 与 `bind: projector verify platforms={}`、
`PidsConnectorItem` 含 `bind: pids verify platforms={}`、lang 含 `verify_failed`。
未启动游戏。
