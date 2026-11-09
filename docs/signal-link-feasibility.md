# MTR 信号连接器（右键节点 → 右键信号灯绑定）可行性与实现路径

范围：`mtr-headlight` / `v1.20.1` / MTR-fabric **3.6.4.2**（`libs/MTR-fabric-1.20.1-3.6.4.2.jar`）。
本文的每条结论都对着 javap 输出，命令见文末「证据」一节。

---

## 1. 结论

**可做。只需要 1 个客户端 mixin，其余全是新增代码，不需要注入 MTR 的存档格式。**

| 问题 | 结论 |
|---|---|
| 信号灯的数据在哪 | 方块实体是**空壳**（`TileEntitySignalLight2Aspect1` 只有构造器）；状态每帧现算 |
| 状态怎么算 | `RenderSignalBase.render` → `getNodePos(方块位置扫描)` → `getOccupiedAspect(节点, 角度)` → 查 `ClientData.SIGNAL_BLOCKS` / `ClientData.OCCUPIED_RAILS` / `ClientData.RAILS` |
| 信号灯与节点的关系 | **纯方块位置扫描**（`getNodePos` 在信号灯前后 5 格、左右 4 格内找 `BlockNode`），没有持久化关联 |
| 绑定存什么 | 节点方块坐标（= `ClientData.RAILS` / `RailwayData#getRailsMap` 的 key）+ 维度。信号灯方块坐标是主键 |
| 存哪 | MTR 的 `RailwayData` 没有给第三方挂 NBT 的口子（msgpack + 私有 `KEY_*`）→ 自己一份 `PersistentState`（per-world `.dat`） |
| 怎么让远处信号灯亮 | mixin `RenderSignalBase#getNodePos` 的 `HEAD`，`cancellable` 返回绑定节点。后面的计算逻辑完全复用 |
| 客户端怎么拿数据 | `mtrlte:signal_link` 自定义包（S2C，三种：全量/单条/删除），客户端存一张 `维度 → (信号灯pos → 节点pos)` 表 |
| 物品 / 页签 | 普通 `Item#useOnBlock` 两次点击（状态存物品 NBT，服务端写）；页签用 `Registries.ITEM_GROUP` + `FabricItemGroup` + `ItemGroupEvents` |
| 节点是实体吗 | **不是**，是方块 `mtr.block.BlockNode`。`ItemUsageContext#getBlockPos()` 直接给它的坐标 |

原型已实现并**编译 + remapJar 通过**（见 §6），没有启动游戏。

---

## 2. 关键 javap 证据

### 2.1 信号灯方块：没有数据，只有渲染

```
$ javap -p mtr.block.BlockSignalLight2Aspect1 'mtr.block.BlockSignalLight2Aspect1$TileEntitySignalLight2Aspect1'
public class BlockSignalLight2Aspect1 extends BlockSignalLightBase {
  public BlockSignalLight2Aspect1(class_4970$class_2251);
  public BlockEntityMapper createBlockEntity(class_2338, class_2680);
}
public class ...$TileEntitySignalLight2Aspect1 extends mtr.mappings.BlockEntityMapper {
  public ...$TileEntitySignalLight2Aspect1(class_2338, class_2680);   // 只有构造器
}
```
基类：`BlockSignalLightBase extends BlockDirectionalMapper implements EntityBlockMapper`
（`BlockDirectionalMapper extends class_2383` = `HorizontalFacingBlock`），
`BlockSignalSemaphoreBase` 同构。**方块实体没有任何字段 → 没有地方能塞「我绑了哪个节点」。**

### 2.2 状态是每帧从客户端缓存里现算的

```
$ javap -c -p mtr.render.RenderSignalBase
public final void render(T, float, MatrixStack, VertexConsumerProvider, int, int)
  52: getStatePropertySafe(state, class_2383.field_11177)        // FACING
  78: getNodePos(BlockView, BlockPos, Direction) -> BlockPos     // 私有静态，位置扫描
  89: if (nodePos == null) return;                               // 找不到节点 → 直接不画
 137: getOccupiedAspect(nodePos, facing.asRotation() + 90f)      // 私有实例

private int getOccupiedAspect(BlockPos node, float angle)
  79: ClientData.RAILS.get(node)                                 // 这个节点上的轨道
 156: rail.facingStart.similarFacing(angle)                      // 朝向匹配（阈值 90°，见 2.4）
 178: ClientData.SIGNAL_BLOCKS.isOccupied(PathData.getRailProduct(node, other))
 205: ClientData.OCCUPIED_RAILS.get(...)                        // 有车占用
 202/246: return 跳数（1..aspects-1）                            // 亮第几颗灯
 249: 走到下一段（rail.facingEnd.getOpposite().angleDegrees），最多 aspects 跳
```
即：**aspect = 从这个节点出发、朝信号灯朝向往里数第几段轨道被占用**（0 = 灭灯，-1 = 不亮）。
绑定「节点」在语义上正好就是「这个信号灯管这个节点出去的那一段」。

### 2.3 节点是靠位置扫描找到的（这就是要顶掉的东西）

```
private static BlockPos getNodePos(BlockView world, BlockPos pos, Direction facing)
  int[] d = {0, 1, -1, 2, -2, 3, -3, 4, -4};
  for (dx : d) for (dz : d)
     for (int back = -5; back <= 0; back++)
        p = pos.offset(facing.getOpposite(), dz).relative(facing, dx).offset(facing.getOpposite(), back);
        if (world.getBlockState(p).getBlock() instanceof BlockNode) return p;
  return null;
```
→ 原生信号灯必须「贴着」节点放；绑定就是把这一步的结果换成远处节点的坐标。

客户端状态来源（全部 public static）：

```
$ javap -p mtr.client.ClientData
  public static final mtr.data.SignalBlocks SIGNAL_BLOCKS;
  public static final java.util.Map<java.util.UUID, java.lang.Boolean> OCCUPIED_RAILS;
  public static final java.util.Map<class_2338, java.util.Map<class_2338, mtr.data.Rail>> RAILS;
```
`ClientData.RAILS` 由服务端按「玩家周围 2560 格」筛选后下发并在收到时 `clear()+putAll`
（`RailwayData.lambda$simulateTrains$0` 里 `new Box(a,b).expand(2560.0).contains(playerPos)`；
`ClientData.commitPendingRails` 里 `RAILS.clear(); RAILS.putAll(pending)`）。
**含义：绑定节点离玩家超过约 1600 格（160 区块）时客户端没有该节点的轨道数据 → 灯不亮。**
1600 格以内都正常，且玩家移动 16 格就会重发。

### 2.4 朝向容差 90°，所以「朝向对不上就永远不亮」

```
$ javap -c mtr.data.RailAngle
public static boolean similarFacing(float a, float b)
  Math.abs(normalizeAngle(a - b)) < 90.0f
```
渲染代入的是 `facing.asRotation() + 90`（`asRotation`: S=0/W=90/N=180/E=270 ⇒ E→0，
与 `RailAngle.E.angleDegrees = 0` 对得上）。
→ 绑定时必须确认信号灯的 `HORIZONTAL_FACING` 能匹配节点上某段轨道的 `facingStart` 角度，
否则一段都挑不中、灯永远灭。原型里做了「不匹配就把方块朝向转到能匹配的那一面」。

### 2.5 节点是方块，且可点

```
$ javap -c mtr.block.BlockNode
public VoxelShape getOutlineShape(...)
   IS_CONNECTED ? Block.createCuboidShape(0,0,0,16,1,16)   // 整块 1px 薄板
                : VoxelShapes.empty()                      // 没接轨道 → 空心，点不到
```
`BlockNode` 没有 `onUse`（`BlockDirectionalMapper` 只覆写 `onBlockAdded` / `onStateReplaced` /
`tick` / `randomTick`），所以右键会被 `Item#useOnBlock` 接住，`context.getBlockPos()` 就是节点坐标。

### 2.6 MTR 自己的「信号连接器」是另一套东西（别混淆）

```
$ javap -p mtr.item.ItemSignalModifier
public class ItemSignalModifier extends ItemNodeModifierBase
  protected void onConnect(World, ItemStack, TransportMode, BlockState, BlockState,
                           BlockPos, BlockPos, RailAngle, RailAngle, PlayerEntity, RailwayData);
$ javap -p mtr.data.SignalBlocks mtr.data.SignalBlocks\$SignalBlock
  public long add(long id, DyeColor color, UUID rail);
  public boolean isOccupied(UUID rail);
```
MTR 的彩色信号连接器是「连两个节点 → 定义一个彩色信号区间（若干条 rail 的集合）」，
用来做闭塞/联锁；它**不认信号灯方块**。我们要做的是「信号灯 → 节点」的显示绑定，两回事。
`RailwayData#addSignal(Player, DyeColor, BlockPos, BlockPos)` 就是那条路，不用它。

---

## 3. 职责划分

| 端 | 负责 | 不负责 |
|---|---|---|
| 服务端（`main`） | 注册物品/页签；处理两次右键；校验（节点是 `BlockNode` 且 `getRailsMap().get(node)` 非空、权限、同维度）；写 `SignalLinkStore`（每维度 `PersistentState`）；把朝向调正；广播变更 + 进服全量同步 | 渲染 |
| 客户端 | 收包维护 `SignalLinks`（维度分桶）；`RenderSignalBaseMixin` 在 `getNodePos` HEAD 返回绑定节点 | 任何权威写操作（客户端 `useOnBlock` 直接 return） |
| MTR | 继续算 aspect、继续管 `ClientData.SIGNAL_BLOCKS`/`OCCUPIED_RAILS`/`RAILS` | 完全不知道绑定存在 |

失败模式：服务端没装 LTE → 没有绑定表、没有包，客户端行为与原生完全一致；
MTR 换版本删了 `getNodePos` → `required:false` + `defaultRequire:0`，mixin 静默失效，退回原生。

---

## 4. 新增/改动的类与注入点

新增（`v1.20.1/src/main/java/dev/js/mtrheadlight/signal/`）：

| 类 | 职责 |
|---|---|
| `SignalLinks` | 客户端镜像：`Map<维度id, Map<信号灯pos, 节点pos>>`；`nodeFor(BlockView, BlockPos)` 给 mixin 用 |
| `SignalLinkStore` | 服务端 `PersistentState`（`world/data/mtrlte_signal_links.dat`） |
| `SignalLinkItem` | 两次右键的物品逻辑、NBT 暂存、朝向校正、提示文案 |
| `SignalLinkNet` | 包编解码 + 广播 + 反射取 `RailwayData#getInstance` |
| `SignalLinkClient` | `client` 入口：注册接收器、断线清表 |
| `LteCoreItems` | 物品 `mtrlte:signal_linker` + 页签 `mtrlte:core` |

注入点（**唯一一个**）：

```java
@Mixin(value = mtr.render.RenderSignalBase.class, remap = false)
public abstract class RenderSignalBaseMixin {
    @Inject(method = "getNodePos", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lte$useLinkedNode(BlockView view, BlockPos signalPos, Direction facing,
                                          CallbackInfoReturnable<BlockPos> cir) { ... }
}
```
- 打在基类 → 2/3/4 aspect 灯 + 半自动臂（`RenderSignalSemaphore extends RenderSignalBase`）全覆盖；
- `remap = false`（MTR 未 remap）；`require` 由 `mtrlte.mtr.mixins.json` 的 `defaultRequire: 0` 兜底；
- 已核验：remap 后 handler 描述符 =
  `(Lnet/minecraft/class_1922;Lnet/minecraft/class_2338;Lnet/minecraft/class_2350;L...CallbackInfoReturnable;)V`，
  与 MTR 的 `getNodePos(class_1922, class_2338, class_2350)` **完全一致**。

改动：`mtrlte.mtr.mixins.json`（client 数组）、`fabric.mod.json`（client 入口）、
`server/LteServer.java`（注册 + 进服同步）、`assets/mtrlte/{models/item/signal_linker.json,lang/*.json}`。

### 编译期真正的坑：MTR 的 MC 类型是 intermediary 名

MTR 未 remap，它的方法签名里 `World` 写作 `class_1937`、`Block` 写作 `class_2248`。于是：

- `state.getBlock() instanceof BlockNode` **编译不过**（`BlockNode extends class_2383`，
  在 javac 眼里跟 `net.minecraft.block.Block` 无继承关系）→ 用 `BlockNode.class.isInstance(...)`；
- `RailwayData.getInstance(world)` **编译不过** → 反射
  `RailwayData.class.getMethod("getInstance", World.class)`（运行期是同一个类，和
  `ServerCollision#ridingAny` 同一套处理）。

只要参数里有 MC 类型，就绕不过这两条 —— 这也是本方案只碰 `RenderSignalBase` 的原因：
它的状态计算链只用到 `UUID` / `RailAngle` / `BlockPos` 这类能直接调的东西。

---

## 5. 创造页签注册方案（1.20.1 / Fabric Item Group API v1）

```java
Registry.register(Registries.ITEM, new Identifier("mtrlte", "signal_linker"), item);

Registry.register(Registries.ITEM_GROUP, new Identifier("mtrlte", "core"),
        FabricItemGroup.builder()
                .displayName(Text.translatable("itemGroup.mtrlte.core"))
                .icon(() -> new ItemStack(item))          // supplier，注册顺序无关
                .build());

ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, id))
        .register(entries -> entries.add(item));
```
要点：
- `ITEM_GROUP` 是**注册表**（1.19.3 起），不再是 `ItemGroup.GROUPS` 数组，也不再用
  `Item.Settings#group(...)`（1.19.3 已删）；
- `RegistryKey.of` 要用 `RegistryKeys.ITEM_GROUP`，传 `Registries.ITEM_GROUP`（`Registry` 实例）编译不过；
- 图标用 supplier；
- 物品必须在 `main` 入口注册（两端都要），页签同理；
- 跨版本（本仓库还出 1.18.2 / 1.19.2 / 1.19.4）：1.18.2 与 1.19.2 没有 `ITEM_GROUP` 注册表，得走
  `fabric-item-groups-v0` 的 `FabricItemGroupBuilder` + `Item.Settings#group(...)`。**本次只验了 1.20.1。**

模型/贴图：直接复用 MTR 的物品贴图，避免新增二进制资源 ——
`assets/mtrlte/models/item/signal_linker.json` → `{"parent":"item/generated","textures":{"layer0":"mtr:item/signal_connector"}}`。

---

## 6. 已完成的验证（无游戏）

在隔离副本（`D:/tmp/sigtest`，因为同一工作树里有别的任务在改 `ServerCollision.java`，
编译不过与本次改动无关）里跑：

```
gradle compileJava  --no-daemon --offline   → BUILD SUCCESSFUL
gradle build        --no-daemon --offline   → BUILD SUCCESSFUL（含 remapJar）
```
产物检查：
- `dev/js/mtrheadlight/signal/*.class`（6 个）在 jar 里；
- `mtrlte.mtr.mixins.json` 含 `RenderSignalBaseMixin`，`assets/mtrlte/models/item/signal_linker.json` 打包正确；
- mixin handler 已 remap 成 intermediary 描述符（与 MTR 目标方法逐字匹配，见 §4）；
- 反向验证：把 `method = "getNodePos"` 改成 `"getNodePosXYZ"` → 构建**失败**
  （说明 mixin AP 确实在校验目标方法，编译通过不是「没检查」）。

---

## 7. 最小游戏内验证步骤（下一步，本文不做）

1. 把 `v1.20.1` 的 jar 丢进带 MTR 3.6.4.2 的实例，进单人创造；
2. 创造物品栏出现页签「LTE 核心功能」→ 有「信号连接器」（图标是 MTR 的信号连接器贴图）；
3. 铺一段两节点的直线轨道，放一个 2-aspect 信号灯**离节点 10 格远**（原生此时一定不亮）；
4. 手持连接器右键节点（应提示「已选节点 x,y,z」）→ 右键信号灯（提示「已绑定」）；
5. 让列车驶入该节点前方的区间 → 信号灯应亮起（和把灯放在节点旁边时的行为一致）；
6. 潜行右键信号灯 → 解除绑定 → 灯恢复常灭；
7. 退出重进（验证 `mtrlte_signal_links.dat` 落盘 + 进服全量同步）；
8. 回归：把一个**没绑定**的信号灯放在节点旁边，行为必须和装模组前完全一致。

已知需要在实测里确认的三件事：
- 绑定节点与观察点距离 > 约 1600 格时（MTR 只同步 2560 格内的轨道）灯不亮 —— 这是 MTR 的
  轨道同步策略，不是绑定失效；要支持更远就得把该节点的 `Rail` 自己同步过去并塞进
  `ClientData.RAILS`（`Rail` 有 `writePacket(PacketByteBuf)` 构造，可做，但要多一个包 + 一个
  「MTR 重发轨道后补回去」的钩子）；
- 弯道 / 道岔节点上有两段以上轨道时，朝向决定「管哪一段」，这是原生语义，UI 上值得给个提示；
- 权限：原型沿用 MTR 的 `RailwayData#hasNoPermission` 语义，是否要求 op 由产品决定。

---

## 8. 证据复现命令

```bash
cd <jar 解包目录>
javap -p -cp . mtr.render.RenderSignalBase
javap -c -p -cp . mtr.render.RenderSignalBase        # getNodePos / getOccupiedAspect 全量字节码
javap -p -cp . 'mtr.block.BlockSignalLight2Aspect1$TileEntitySignalLight2Aspect1'
javap -p -cp . mtr.client.ClientData
javap -c -p -cp . mtr.data.SignalBlocks
javap -c -p -cp . mtr.data.RailAngle                 # similarFacing 的 90°
javap -c -p -cp . mtr.block.BlockNode                # getOutlineShape
javap -c -p -cp . mtr.data.RailwayData               # lambda$simulateTrains$0 的 2560.0
```
