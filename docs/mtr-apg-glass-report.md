# 半高屏蔽门（APG）：玻璃上的线路图 + 端部紫黑块

> 对象：MTR-fabric-1.20.1-**3.6.4.2**（`D:\MC-MTR-JS\temp\mtrjar`）、天津地铁（`D:\MC-MTR-JS\temp\tj.jar` / `temp\tj`）。
> 证据：`cfr` 反编译 + `javap` + 资源包逐文件核对（脚本 `tools/audit-apg-assets.js`）。
> 本文对应 `xiamen/XiamenPsdBlocks.java`、`xiamen/XiamenObjects.java`、`xiamen/XiamenClient.java` 里 APG 那一支。

---

## 0. 两个问题的结论（先看这段）

| 问题 | 确切原因 | 修法 |
| --- | --- | --- |
| 玻璃上没有线路图 | `ApgGlass extends BlockPSDAPGGlassBase`——这个基类**根本没有方块实体**，而线路图是 BER `mtr.render.RenderAPGGlass` 用方块实体里的 `getPlatformId()` 现画的。没有 BE → 没有 BER 可以挂 → 只剩静态模型 | 改成 `extends BlockAPGGlass`（MTR 自家 APG 玻璃）、给方块实体（父类 `BlockPSDTop.TileEntityRouteBase`）、把 **MTR 的 `RenderAPGGlass`** 注册到这个 BE 类型上 |
| 端部是紫黑棋盘 | 方块 id 是 `mtrlte:xiamen_apg_end`，但资源里只有 `assets/mtrlte/blockstates/**xiamen_apg_glass_end**.json`——**blockstate 文件名与方块 id 不匹配** → `BlockModels` 拿不到任何变体 → 回退 missing model（紫黑棋盘）。**不是贴图缺失**：40 个 multipart 引用的 16 张贴图全部存在 | 把文件重命名为 `xiamen_apg_end.json`（内容一字未改，与 MTR 的 `apg_glass_end.json` 逐条等价） |

---

## 1. 玻璃上的线路图是谁画的

**不是方块模型，是方块实体渲染器。** 类与签名：

```java
package mtr.render;
public class RenderAPGGlass extends RenderRouteBase<BlockAPGGlass.TileEntityAPGGlass> {
    private static final float COLOR_STRIP_START = 0.75f;   // 12/16
    private static final float COLOR_STRIP_END   = 0.78125f;// 12.5/16

    public RenderAPGGlass(BlockEntityRenderDispatcher dispatcher);   // 实际是 class_824

    @Override protected RenderType getRenderType(BlockView world, BlockPos pos, BlockState state);
    @Override protected void renderAdditional(StoredMatrixTransformations m, long platformId,
                                              BlockState state, int leftBlocks, int rightBlocks,
                                              Direction facing, int color, int light);
}
```

构造参数（MTR 写死，不是可配置）：

```java
super(dispatcher, /*z*/4.0f, /*topPadding*/8.0f, /*bottomPadding*/4.0f, /*sidePadding*/8.0f,
      /*transparentWhite*/ false, /*arrowDirectionProperty*/ BlockAPGGlass.ARROW_DIRECTION);
```

即：`z = 4/16`，上留白 8/16、下留白 4/16、左右各 8/16，不透明底（`transparentWhite=false`），
箭头朝向属性取 `BlockAPGGlass.ARROW_DIRECTION`（**属性名 `propagate_property`，整数 0..3**）。

注册点（MTR 自己的 `MTRClient.init()`）：

```java
RegistryClient.registerTileEntityRenderer(BlockEntityTypes.APG_GLASS_TILE_ENTITY.get(), RenderAPGGlass::new);
```

**这个 BER 只挂在 MTR 的 `APG_GLASS_TILE_ENTITY` 上**，所以想让玻璃有线路图，方块必须有
「类型能被注册渲染器」的方块实体（见 §4 的两种做法）。

### 画什么、画在哪一格

`RenderRouteBase#render(...)`（**final**）每帧：

1. `entity.getPlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE)` 把方块位置换算成**站台 id**（0 = 没找到，整块不画）；
2. 用 `getTextureNumber(world, pos, facing, left/right)` 沿 `side_extended` 链路数出左右各几格（决定线路图纹理的宽高比）；
3. `getRenderType(...)` 决定这一格画什么：

```java
// RenderAPGGlass
if (HALF == LOWER) return RenderType.NONE;                       // 下段什么都不画
if (floorMod(x,8) < 4 == floorMod(z,8) < 4) return RenderType.ARROW;  // 交替：箭头 / 线路图
return RenderType.ROUTE;
```

4. `RenderType.ROUTE` → `ClientData.DATA_CACHE.getRouteMap(platformId, false, arrowDir == 2, w/h, false)`；
   `ARROW` → `getDirectionArrow(...)`；`STATION_NAME` → `getStationName(...)`（APG 玻璃不走这条）；
5. `renderAdditional(...)` 再补两样东西（只有 `HALF==UPPER && side != SINGLE` 时）：
   **线路色带**（`DATA_CACHE.getColorStrip(platformId)`，画在 y=0.75..0.78125）和
   **站名**（`DATA_CACHE.getSingleRowStationName(platformId, w/h)`，画在 0.125 的深度上）。

一句话：**线路图 = `RouteMapGenerator` 现生成的纹理**，玻璃和吊板读的是同一份。

## 2. 和 `RenderPSDTop` / `RenderRouteBase` 是不是同一套

是，**同一个基类、同一份数据**：

```
RenderRouteBase<T extends BlockPSDTop.TileEntityRouteBase>        ← 共有逻辑（final render）
├── RenderPSDTop     (全高吊板, y+2)      ctor: z=?, top/bottom/side 各不同, transparentWhite=false
└── RenderAPGGlass   (半高玻璃上段)       ctor: z=4/16, top=8/16, bottom=4/16, side=8/16
```

数据链完全相同：

```
TileEntityRouteBase#getPlatformId(Set<Platform> platforms, DataCache cache)
        └─ RailwayData.getClosePlatformId(...)      // 方块坐标 → 站台 id（带 cachedRefreshTime 缓存）
ClientData.PLATFORMS  +  ClientData.DATA_CACHE      // DATA_CACHE 的真实类型是 mtr.client.ClientCache
        └─ ClientCache#getRouteMap(platformId, vertical, flip, aspectRatio, transparentWhite)
                └─ mtr.client.RouteMapGenerator.generateRouteMap(...)  → DynamicResource（动态纹理）
        └─ getDirectionArrow / getColorStrip / getSingleRowStationName  同上，都是 ClientCache 里现生成的
```

所以：**玻璃的线路图不需要我们给数据**，只要方块实体是 `TileEntityRouteBase` 的子类、
放在站台旁边（`getClosePlatformId` 能命中），并且线路/车站是 MTR 的 Dashboard 数据或 JS 脚本包给的。

## 3. 天津地铁怎么做的（差异）

| | MTR | 天津 | 我们（本次） |
| --- | --- | --- | --- |
| 玻璃父类 | `BlockPSDAPGGlassBase`，`BlockAPGGlass` 加 `ARROW_DIRECTION` | `extends BlockAPGGlass` | `extends BlockAPGGlass` |
| 方块实体 | `BlockAPGGlass.TileEntityAPGGlass extends BlockPSDTop.TileEntityRouteBase` | 见下 | `TileEntityXiamenAPGGlass extends BlockPSDTop.TileEntityRouteBase`（自注册类型） |
| BER | `RenderAPGGlass extends RenderRouteBase`，注册在 `APG_GLASS_TILE_ENTITY` | `ziyue.tjmetro.render.RenderAPGGlassTianjinBMT extends ziyue.tjmetro.render.RenderRouteBase` | **直接用 MTR 的 `RenderAPGGlass`** |

天津其实有 **四个** APG 玻璃，做法不一样：

- `BlockAPGGlassTianjin`（最朴素那只）：
  ```java
  public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) { return null; }
  protected boolean isAPG() { return true; }
  ```
  → **它自己就没有线路图**（故意返回 null，省掉 BER 开销）。
- `BlockAPGGlassTianjinBMT`：`extends BlockAPGGlass` + 额外 `style` 属性（`route / station_name / next_station`）
  + 自己的方块实体 `TileEntityAPGGlassTianjinBMT extends BlockPSDTop.TileEntityRouteBase`
  + 自己的 BER `RenderAPGGlassTianjinBMT`。
- `BlockAPGGlassTianjinJinjing` / `...TRT`：同上，各自一个 BE + 一个 BER。

**关键差异（也是我们不需要照抄的原因）**：天津不得不**整份重写** `RenderRouteBase`
（`ziyue.tjmetro.render.RenderRouteBase`），因为 MTR 的 `RenderRouteBase#render` 是 **`final`**，
而天津要在里面改几何（他们的 `topPadding=8, bottomPadding=1, z=4`）并塞 `style` 分支。
我们不改几何、不加属性分支，所以**直接 new MTR 的 `RenderAPGGlass` 就够**——
任务要求「优先继承/复用 MTR 的 BER」，这正是它成立的条件。

## 4. 我们原来缺什么 / 现在怎么补的

### 缺的三样（缺一不可）

1. **方块实体**：`BlockPSDAPGGlassBase` 没有 `createBlockEntity`（`javap` 只看到 `use` 与 `connectGlass`），
   我们的 `ApgGlass` 继承它 → 方块压根没有 BE；
2. **`ARROW_DIRECTION` 属性**：`RenderRouteBase#render` 无条件 `IBlock.getStatePropertySafe(state, arrowDirectionProperty)`；
   `BlockPSDAPGGlassBase` 没有这个属性（好在 `getStatePropertySafe` 会回退到属性默认值，不会抛异常）；
3. **BER 注册**：`XiamenClient` 里原来只有 门扇 / 吊板 / APG 门扇 三个渲染器，没有玻璃。

顺带一个隐藏 bug：`BlockPSDAPGBase#isAPG()` 的实现是
```java
return this instanceof BlockAPGDoor || this instanceof BlockAPGGlass || this instanceof BlockAPGGlassEnd;
```
继承 `BlockPSDAPGGlassBase` 时它是 `false`，上段的碰撞/选中框高度按整格（16）算；
换成 `BlockAPGGlass` 后自动是 `true`（9/16），和 MTR 一致。

### 现在的实现

```java
// XiamenPsdBlocks
public static final class ApgGlass extends BlockAPGGlass {          // isAPG()=true, ARROW_DIRECTION, 刷子切箭头
    @Override public BlockEntityMapper createBlockEntity(BlockPos pos, BlockState state) {
        return new TileEntityXiamenAPGGlass(pos, state);
    }
    @Override public Item asItem() { return itemOrAir(XiamenObjects.apgGlassItem()); }
}

public static final class TileEntityXiamenAPGGlass extends BlockPSDTop.TileEntityRouteBase {
    public TileEntityXiamenAPGGlass(BlockPos pos, BlockState state) {
        super(XiamenObjects.apgGlassEntityType(), pos, state);
    }
}
```

```java
// XiamenObjects.register()
apgGlassEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, APG_GLASS_BE_ID,
        FabricBlockEntityTypeBuilder.<XiamenPsdBlocks.TileEntityXiamenAPGGlass>create(
                XiamenPsdBlocks.TileEntityXiamenAPGGlass::new, apgGlass).build());
```

```java
// XiamenClient.onInitializeClient()
BlockEntityRendererRegistry.register(XiamenObjects.apgGlassEntityType(), context ->
        (BlockEntityRenderer<XiamenPsdBlocks.TileEntityXiamenAPGGlass>) (Object)
                new RenderAPGGlass(context.getRenderDispatcher()));
```

> 为什么不直接沿用 MTR 的 `APG_GLASS_TILE_ENTITY`？也可以（`BlockAPGGlass` 默认就返回它，
> MTR 已经给那个类型注册了 BER），但那个 `BlockEntityType` 的 blocks 集合里没有我们的方块，
> 而且和本模组「门扇 / 吊板都用自己注册的 BE 类型」的既有做法不一致。自己的类型 + 同一次转型
> （和 `RenderPSDTop` 那一处一模一样，运行期 `RenderRouteBase` 只按 `TileEntityRouteBase` 调
> `getPlatformId`）既统一又安全。

### 资源侧的连带改动

`ApgGlass` 多了 `propagate_property`（0..3）属性，`assets/mtrlte/blockstates/xiamen_apg_glass.json`
的 `variants` 从 32 条补成 **128 条**（`facing × side × half × propagate_property`），
保证任何状态都能精确命中模型（不再依赖「变体键可以只写部分属性」这种隐式行为）。

## 5. 端部紫黑块：确切原因

**没有任何贴图缺失。** 缺的是 **blockstate 文件的名字**：

```
方块 id                      assets/mtrlte/blockstates/
mtrlte:xiamen_apg_end    →   xiamen_apg_end.json      ← 之前不存在（不存在 = missing model = 紫黑棋盘）
（孤儿文件）                  xiamen_apg_glass_end.json ← 之前实际存在的那个
```

证据：
- `XiamenObjects.APG_END_ID = new Identifier("mtrlte", "xiamen_apg_end")`；
- 目录里只有 `xiamen_apg_glass_end.json`，没有 `xiamen_apg_end.json`；
- 该文件的 40 条 multipart 条件与 MTR 的 `apg_glass_end.json` **一一对应、零差异**（脚本比对：
  `ours 40 / mtr 40`，`in MTR but not ours: 空`，`in ours but not MTR: 空`），
  引用的 10 个模型、**8 个**纹理 id（`apg_glass_bottom / single_bottom / top / single_top / side_triangle /
  psd_side_light / transparent` + `item/apg_glass`）全部存在。

修法：`git mv` 语义的重命名（不提交）→ `xiamen_apg_glass_end.json` → **`xiamen_apg_end.json`**。

## 6. APG 全部模型 → 贴图 id 对照表

> 由 `tools/audit-apg-assets.js`（枚举 blockstates → 模型 → 沿 `parent` 链收集 `textures` → 逐个查文件）生成。
> 结论：**全部存在**，0 个 `MISSING`。

**`xiamen_apg_glass` — 8 个模型**

| 模型 | 贴图 id（全部存在） |
| --- | --- |
| `apg_glass_bottom_left` | `mtrlte:item/apg_glass`<br>`mtrlte:block/transparent`<br>`mtrlte:block/apg_glass_bottom`<br>`mtrlte:block/psd_side_light` |
| `apg_glass_bottom_middle` | `mtrlte:item/apg_glass`<br>`mtrlte:block/transparent`<br>`mtrlte:block/apg_glass_single_bottom`<br>`mtrlte:block/psd_side_light` |
| `apg_glass_bottom_right` | `mtrlte:item/apg_glass`<br>`mtrlte:block/transparent`<br>`mtrlte:block/apg_glass_bottom`<br>`mtrlte:block/psd_side_light` |
| `apg_glass_bottom_single` | `mtrlte:item/apg_glass`<br>`mtrlte:block/transparent`<br>`mtrlte:block/apg_glass_single_bottom`<br>`mtrlte:block/psd_side_light` |
| `apg_glass_top_left` | `mtrlte:item/apg_glass`<br>`mtrlte:block/transparent`<br>`mtrlte:block/apg_glass_top`<br>`mtrlte:block/psd_side_light`<br>`mtrlte:block/apg_side_triangle` |
| `apg_glass_top_middle` | `mtrlte:item/apg_glass`<br>`mtrlte:block/transparent`<br>`mtrlte:block/apg_glass_single_top`<br>`mtrlte:block/psd_side_light` |
| `apg_glass_top_right` | `mtrlte:item/apg_glass`<br>`mtrlte:block/transparent`<br>`mtrlte:block/apg_glass_top`<br>`mtrlte:block/psd_side_light`<br>`mtrlte:block/apg_side_triangle` |
| `apg_glass_top_single` | `mtrlte:item/apg_glass`<br>`mtrlte:block/transparent`<br>`mtrlte:block/apg_glass_single_top`<br>`mtrlte:block/psd_side_light`<br>`mtrlte:block/apg_side_triangle` |

**`xiamen_apg_end` — 10 个模型**

| 模型 | 贴图 id（全部存在） |
| --- | --- |
| `apg_glass_end_bottom` | `mtrlte:block/apg_glass_single_bottom`<br>`mtrlte:block/psd_side_light` |
| `apg_glass_end_top` | `mtrlte:block/apg_glass_single_top`<br>`mtrlte:block/psd_side_light` |
| `apg_glass_bottom_left` / `_right` | `mtrlte:item/apg_glass`, `mtrlte:block/transparent`, `mtrlte:block/apg_glass_bottom`, `mtrlte:block/psd_side_light` |
| `apg_glass_bottom_middle` / `_single` | `mtrlte:item/apg_glass`, `mtrlte:block/transparent`, `mtrlte:block/apg_glass_single_bottom`, `mtrlte:block/psd_side_light` |
| `apg_glass_top_left` / `_right` | `mtrlte:item/apg_glass`, `mtrlte:block/transparent`, `mtrlte:block/apg_glass_top`, `mtrlte:block/psd_side_light`, `mtrlte:block/apg_side_triangle` |
| `apg_glass_top_middle` / `_single` | `mtrlte:item/apg_glass`, `mtrlte:block/transparent`, `mtrlte:block/apg_glass_single_top`, `mtrlte:block/psd_side_light`（single 多一个 `apg_side_triangle`） |

**`xiamen_apg_door`**：`{"variants": {"": {"model": "minecraft:block/air"}}}` —— 和 MTR 的 `apg_door.json`
一样是「空气模型」，门扇整个由 BER `RenderPSDAPGDoor(type=2)` 画（贴图 `mtr:textures/block/apg_door_*`），
blockstate 不参与。

物品模型（背包图标，与方块渲染无关，已核对可用）：

| 物品 | 模型 | 贴图 |
| --- | --- | --- |
| `mtrlte:xiamen_apg_glass` | `item/generated` | `mtrlte:item/apg_glass` ✔ |
| `mtrlte:xiamen_apg_end` | `item/generated` | `mtrlte:item/apg_glass_end` ✔ |
| `mtrlte:xiamen_apg_door` | `item/generated` | `mtrlte:item/apg_door` ✔ |

## 7. 验证

- `gradle -p v1.20.1 clean build` → **BUILD SUCCESSFUL**，jar `v1.20.1/build/libs/lte-1.0.24.jar`
  （1,169,340 B，`md5 eb01a32d8af12c235f28f686891710d7`）；
- jar 内已含 `assets/mtrlte/blockstates/xiamen_apg_end.json`（7931 B）、
  `assets/mtrlte/blockstates/xiamen_apg_glass.json`（128 变体）、
  `dev/js/mtrheadlight/xiamen/XiamenPsdBlocks$TileEntityXiamenAPGGlass.class`，
  `XiamenClient.class` 里能查到 `RenderAPGGlass` 常量；
- `node tools/audit-apg-assets.js`：8 个方块 id 全部有 blockstate、**0 条 `MISSING`**、0 个孤儿 blockstate；
- 分发（三份 md5 一致）：
  `~/Desktop/lte-1.0.24.jar`、`~/Desktop/lte-1.0.24+1.20.1.jar`、
  实例 `D:\MC-MTR-JS\temp\mcrun\mods\lte-1.0.24.jar`（旧的 `lte-1.0.0+1.20.1.jar` 已重命名覆盖，避免同 id 双份）；
- 未启动游戏（按任务要求）。

## 8. 复现脚本

```bash
node tools/audit-apg-assets.js      # 方块 id → blockstate → 模型 → 贴图 id → 是否存在
node tools/apg-table-md.js          # 同上，输出 markdown 表格
```
