# 厦门屏蔽门重做：调研 MTR / 天津地铁 + 双扇可开合实现

> **⚠️ 本文已废弃（2026-11-10 晚）。** 本文实现的「1 格高、单块门叶、一次放 2 格」结构和 MTR 的真实结构不符。
> 屏蔽门是 **3 格高一列**（下段 / 上段 / 吊板），门一次放 **2 列**（左右两扇对开）共 6 格，玻璃 / 端部各 1 列共 3 格。
> 现行实现见 `docs/report-2026-11-10-xiamen-psd-tricolumn.md`。

日期：2026-11-10 · 目标版本：1.20.1 · 未提交 git

## 1. 调研：屏蔽门到底是怎么做的

反编译来源（本机）：

- `D:\MC-MTR-JS\mtr-headlight\libs\MTR-fabric-1.20.1-3.6.4.2.jar`
- `D:\MC-MTR-JS\temp\mcrun\mods\TianjinMetro-fabric-1.20.1-1.1.3.jar`（天津地铁，**没有混淆**：包名 `ziyue.tjmetro`，类名可读）
- 反编译工具：CFR 0.152（`D:\MC-MTR-JS\temp\cfr.jar`），产物在 `temp\mtr-src`、`temp\tj-src`

### 1.1 MTR：方块 + BlockEntity + BlockEntityRenderer

| 证据 | 位置 |
| --- | --- |
| `BlockPSDAPGDoorBase extends BlockPSDAPGBase implements EntityBlockMapper` | `mtr/block/BlockPSDAPGDoorBase.java:66` |
| blockstate 属性 `END / UNLOCKED / TEMP` + 基类 `FACING / HALF / SIDE` | 同上 `:69-72`、`:125-127` |
| `MAX_OPEN_VALUE = 32`（开合进度 0..32） | 同上 `:69` |
| 碰撞箱：`getCollisionShape` 在 BE `isOpen()` 时返回 `VoxelShapes.empty()`，否则走基类 | 同上 `:120-123` |
| 基类外形：`getVoxelShapeByDirection(0,0,0,16,16,4,facing)`（4px 厚、满高） | `mtr/block/BlockPSDAPGBase.java:46-49` |
| BE：`open:int`、`openClient:float`、`setOpen(int)`、`getOpen(float frameDelta)`、`isOpen()`、`getOpenValue()` | `BlockPSDAPGDoorBase.java:147-193` |
| 插值：`getOpen(delta)` 每帧把 `openClient` 往 `open - 0.1` 靠（`change = delta * 0.95f`），返回 `openClient / 32` | 同上 `:180-184` |
| 渲染：`RenderPSDAPGDoor`（BlockEntityRenderer），`getOpen(MTRClient.getLastFrameDuration())` | `mtr/render/RenderPSDAPGDoor.java:86` |
| 滑动：`storedMatrixTransformations.add(m -> m.translate(open * (side ? -1 : 1), 0, 0))` —— 每扇沿墙面平移 **1 格** | 同上 `:128` |
| 上下分段贴图：`psd_door_%s_%s_%s.png`（top/bottom × left/right × 1/2），end 件另有 `psd_door_end_*` | 同上 `:106-111`、`:133-144` |
| 门灯：`isDoorLightOn()`（open=0 灭、=32 常亮、中间闪烁） | 同上 `:195-204` |
| 放置：一次生成 **2 格宽 × 2 格高**（`horizontalBlocks = 2`、`y < 2`）+ 顶上 `psd_top`，`SIDE` 左=LEFT、右=RIGHT | `mtr/item/ItemPSDAPGBase.java:66-88` |
| 由列车开门驱动：`TrainServer#openDoors` 扫描门位 ±1 格，仅 `UNLOCKED` 的门；`setOpen(clamp(doorValue * 64, 0, 32))` | `mtr/data/TrainServer.java:239-254` |
| 右键只做「锁定 / 解锁」（刷子），不做开关 | `BlockPSDAPGDoorBase.java:108-118` |
| 门的 blockstate 是空模型 `"": {"model": "minecraft:block/air"}` —— 几何全交给 BER | MTR `assets/mtr/blockstates/psd_door.json` |
| 固定玻璃几何：`from [2,0,0] to [4,16,16]`，`#front` 贴在 **east 面（x=4）**、`#back` 贴 west 面 | MTR `assets/mtr/models/block/psd_glass_left_base.json` |

### 1.2 天津地铁：完全复用 MTR 的基类，只换贴图/渲染

| 证据 | 位置 |
| --- | --- |
| `BlockPSDDoorTianjin extends BlockPSDAPGDoorBase implements BlockFlagPSDTianjin` | `ziyue/tjmetro/block/BlockPSDDoorTianjin.java:25-27` |
| 自带 BE：`TileEntityPSDDoorTianjin extends BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase` | 同上 `:36-41` |
| 渲染器 `RenderTianjinPSDDoor extends BlockEntityRendererMapper`（MTR 的 BER 包装） | `ziyue/tjmetro/render/RenderTianjinPSDDoor.java:49-51` |
| 开合进度仍取 `entity.getOpen(MTRClient.getLastFrameDuration())` | 同上 `:75` |
| 贴图 `psd_door_tianjin_%s_%s.png`（top/bottom × left/right）、end 件用两张拼（`_1`/`_2`，第二张只滑一半 `open / 2`） | 同上 `:91-111`、`:93` |
| 门灯 `light_on/off.png`、锁定牌 `door_not_in_use.png` | 同上 `:84`、`:112-121` |

**结论**：两家的做法一致 —— **方块（blockstate 记 facing/side/half/end/unlocked）+ BlockEntity 记开合进度 + BlockEntityRenderer 每帧按进度平移贴图，碰撞箱在开门时清空**。天津不是闭源/混淆，可以直接读；但由于它继承 MTR 的基类，`mtr` 必须是编译期依赖，本模组只 `compileOnly` MTR，所以**没有直接继承 MTR 的类**，而是按同样的结构自己写了一套。

## 2. 重做方案（`mtrlte:xiamen_psd_door`）

| 文件 | 作用 |
| --- | --- |
| `xiamen/XiamenPsdDoorBlock.java` | 门扇方块：`facing / side(left/right) / open` 三个属性 + BlockEntityProvider |
| `xiamen/XiamenPsdDoorEntity.java` | 客户端插值进度（对应 MTR 的 `openClient`），每 tick 走 0.07（约 14 tick 开满） |
| `xiamen/XiamenPsdDoorRenderer.java` | BlockEntityRenderer：上下分段贴图 + 沿墙平移，映射规则与 blockstate 的 y 旋转一致 |
| `assets/mtrlte/blockstates/xiamen_psd_door.json` | 16 个变体（facing×side×open）→ 空模型（几何全由 BER 画，和 MTR 的 `psd_door.json` 同思路） |
| `assets/mtrlte/models/block/xiamen_psd_door_empty.json` | 空壳 + 破坏粒子贴图 |
| `textures/block/xiamen_psd_door_{top,bottom}[_back].png` | 从玩家资源包 `厦门地铁一号线车辆` 拷入的 `psd_door_{top,bottom}[_back]_1.png` |

行为：

- **双扇对开**：放置时一次生成两扇（左扇 LEFT + 右扇 RIGHT，和 MTR 的物品一样），各自沿墙滑出 1 格；只有 1 格空位时退化为单扇。
- **开关**：右键切换整对（含上下堆叠的同款门）；红石通电=开、断电=关；关门音效用铁门开门/关门声。
- **碰撞**：关门时 `0..4` 厚（照抄 MTR 的 `getVoxelShapeByDirection(0,0,0,16,16,4,facing)`），开门时 `VoxelShapes.empty()`。
- **贴图**：同一方块内下半 8px 用 `xiamen_psd_door_bottom(_back)`、上半 8px 用 `_top(_back)`；正反面各一张，正面朝 +X（与玻璃板 `psd_glass_left_base` 的 `#front` 同向），门叶放在 x∈[0,2] 即玻璃板（2..4）后面 —— 开门时不会和邻居玻璃共面闪烁。
- **旧存档**：三个 id（`xiamen_psd_door` / `xiamen_psd_glass` / `xiamen_psd_end`）都没改，旧门块读出来是 `side=left, open=false` 的单扇。
- **玻璃 / 端部**：仍是 `XiamenPsdBlock`（静态 2px 板，模型 parent MTR 的 `psd_glass_left_base`），没动。

## 3. 没做的部分

- **列车/站台自动联动**：MTR 是 `TrainServer#openDoors` 在列车到站开门时调 `setOpen()`。本模组没有接 MTR 的列车门事件（需要挂 MTR 服务端的 door value，或改用 ANTE 的接口），目前只能手动 / 红石。
- **HALF（2 格高）**：MTR 一扇门是上下两块。这里保持 1 格高以匹配已有的玻璃 / 端部墙（否则整面墙要抬高一块），代价是上下两段贴图各压成 8px。
- **门灯 / 锁定牌 / 刷子锁定**：MTR 的 `psd_side_light`、`door_not_in_use.png`、`UNLOCKED` 刷子交互都没做。
- **左右专用贴图**：资源包里还有 MTR 3.x 的 `psd_door_top_left_1.png`（576×288 的 box-UV 图集），本实现用的是整面图 `psd_door_top_1.png`，两扇共用一张（没有 leading-edge 差异）。
- 未启动游戏验证（按要求），只有编译 + 几何自检（`temp\PsdCheck.java`、`temp\PsdPair.java`：四向的关门/开门包围盒、碰撞包含关系、双扇开合后 2 格净空、互不重叠）。
