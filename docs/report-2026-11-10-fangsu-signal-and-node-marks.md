# 方速交通灯能不能绑节点 · 手持连接器显示节点轮廓

日期：2026-11-10 · 只针对 **1.20.1**（`v1.20.1/`）· 未提交 git

---

## 1. 结论先说

| 项 | 结果 |
|---|---|
| 方速信号灯（`fangsu:traffic_light`）右键完成绑定 | **做不到**，理由不是「没写」，是它的信号来源里没有「节点」这个概念（详见第 2 节的三条证据） |
| 服务端给方速灯写一条绑定 | **故意不做**：没有任何消费者 → 那就是假功能 |
| 右键方速灯时的反馈 | **做了**：认出方块并给出具体原因（`message.mtrlte.signal_link.fangsu_unsupported`），不再显示「请右键轨道节点或信号灯」 |
| 手持连接器时高亮附近轨道节点 | **做了**（白框，已选节点青色，≤32 格，每帧 ≤200 个） |

---

## 2. 反编译证据

对象：
`%APPDATA%/../Desktop/PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/[方速MTR扩展] [MTR3]fangsu-mc1.20.1-FABRIC-1.3.2-build20261102090023.jar`
（62016527 字节，`sha256` 前 32 位 `d5c22fc51f4ad2ba37e78b3a38ee7dd3`，`fabric.mod.json` 里 `"version": "1.3.2"`；
与仓库里 `temp/fangsu.jar` 是同一个文件。下文行号/偏移都来自 `javap -p -c`。）

### 2.1 它不是 MTR 的信号灯

```
com.fangsu.blocks.BlockTrafficLight   extends com.fangsu.blocks.BaseObjBlock
com.fangsu.blocks.BaseObjBlock        extends net.minecraft.class_2383        // HorizontalFacingBlock
com.fangsu.blockEntities.BlockEntityTrafficLight
                                      extends com.fangsu.blockEntities.FunctionalObjBlockEntity
com.fangsu.blockEntities.BaseObjBlockEntity extends net.minecraft.class_2586   // BlockEntity
```

继承链上没有 `mtr.block.BlockSignalLightBase` / `BlockSignalSemaphoreBase`，方块实体也不是
`TileEntitySignalLightXAspectY`。所以现有那条 mixin（`RenderSignalBaseMixin` 注入
`mtr.render.RenderSignalBase#getNodePos`）**永远收不到它** —— 它根本不走 `RenderSignalBase.render`。

方块 id：`assets/fangsu/blockstates/traffic_light.json` → `fangsu:traffic_light`；
语言文件 `block.fangsu.traffic_light` = 交通灯，`block.fangsu.traffic_light.desc` 明确写着
「显示类型可选「程序控制」或「MTR 信号联动」」。

### 2.2 「MTR 信号联动」这一档在 1.3.2 里没有任何实现

`BlockEntityTrafficLight.getConfigs()`（bytecode）：

```
29: ldc "program"                         // 选项值
31: ldc "mtr"
37: List.of("program", "mtr")             // EnumConfig 的 values
42: ldc "modular.fangsu.tl.dispType"      // 标签「显示类型」
19/22: ldc "modular.fangsu.tl.dispProgram" / "modular.fangsu.tl.dispMtr"
                                          //   zh_cn: 程序控制 / MTR信号联动
```

`BlockEntityTrafficLight.whenRendering()`（bytecode，全类里唯一读 `dispType` 的地方）：

```
22: ldc "program"
25: getfield dispType       (this.dispType)
28: String.equals → ifeq 38              // 不等于 "program"（也就是 "mtr"）→ 跳过
35: invokevirtual drive()                // drive(): TrafficLightProgram.tick(nanoTime/1e9)
                                         //          → getLightStates() → 写 this.lightState
38: … 之后只按 lightState 画 subModelDmhs
```

渲染循环里，`lightState.get(subModel)` 为 `null` 的灯珠在偏移 123 处 `ifnull` **直接跳过**。
也就是说 `dispType="mtr"` 的全部作用是「不跑积木程序」，没有任何代码往 `lightState` 里写值，
红/黄/绿灯珠一律不亮。`TrafficLightProgram#setLight` 的唯一调用者是积木语句
（`TrafficLightProgramBlocks.TYPE_SET_LIGHT`），只属于 `program` 档。
另：`TrafficLightContent` 没有 script 字段，`assets/fangsu/traffic_light/basic_traffic_light.json`
也没有 `script`，所以也没有「内容包脚本」这条路。

### 2.3 方速内部唯一的 MTR 信号接口，形参里没有节点，而且没人调用

```
com.fangsu.scripting.MinecraftClientUtil#getOccupiedAspect(
        com.fangsu.render.sowcer.math.Vector3f pos, float angleDeg, int depth) : int
   0: Vector3f.toBlockPos()                       // 起点 = 传进来的方块坐标
  14: map.put(blockPos, angleDeg)                 // 起点 + 朝向角
  33..294: while (dist < depth)
     getstatic mtr/client/ClientData.RAILS
       Rail.facingStart.similarFacing(angle)      → 挑「朝这个方向」的那一段
       ClientData.SIGNAL_BLOCKS.isOccupied(PathData.getRailProduct(pos, railPos)) → return dist
       ClientData.OCCUPIED_RAILS.get(...)                                        → return dist
       next.put(railPos, Rail.facingEnd.getOpposite().angleDegrees)
```

这是 `RenderSignalBase#getOccupiedAspect` 的一份复制品，但**起点固定是调用方给的那个 BlockPos**：
没有 `BlockNode` 形参、没有绑定表概念。并且：

```
$ grep -ral "getOccupiedAspect" com/fangsu/
com/fangsu/scripting/MinecraftClientUtil.class     # 只有它自己（定义处）
```

`grep -ral "SignalLight|SignalSemaphore|SIGNAL_BLOCKS" com/fangsu/` 也只命中这一类
+MTR 数据读取类；**没有任何类读 MTR 的信号灯方块**。

### 2.4 因此缺什么（阻塞点，逐条）

1. **缺「按节点取 aspect」的入口**：方速只有 `getOccupiedAspect(pos, angle, depth)`，起点是灯自己，
   没有节点参数。想让它显示远处节点，必须由我们替换起点。
2. **缺一个会被调用的位置可供 mixin**：1.3.2 里 `getOccupiedAspect` 无 Java 调用点
   （唯一的 MTR 信号联动档位 `dispType="mtr"` 是空实现）。注入一个永不执行的 HEAD 等于自欺。
3. **缺可写的灯色钩子**：`lightState` 是 `private ConcurrentHashMap`，键名来自内容包
   （`subModels: {main, red, yellow, green}`），且 `whenRendering` 每帧只读不写；要驱动它必须
   反射写私有字段 + 猜键名 + 猜 aspect→颜色映射。这是「猜」，不是「接」。
4. **它不是 `RenderSignalBase`**：现有 `getNodePos` 注入与其渲染管线无关。

> 若方速以后把 `dispType="mtr"` 实现成「每帧调 `getOccupiedAspect(自身坐标, 自身朝向)`」，
> 那么一个 `require=0` 的 HEAD 注入（命中时换成绑定节点 + 该节点 rails 的角度）就能直接生效，
> 无需改方块。届时只需加一个 mixin，本文件的证据够定位。

---

## 3. 实际改了什么

### 3.1 方速信号灯：认得出，绑不上（明确拒绝）

* 新文件 `v1.20.1/src/main/java/dev/js/mtrheadlight/signal/FangsuSignalCompat.java`
  * `isFangsuTrafficLight(BlockState)` —— 按 registry id 判断
    （`fangsu:traffic_light` / `fangsu:modular_traffic_light`），**不引入** `com.fangsu.*` 编译期依赖
    （与 `mixin/FangsuMixinPlugin` 的 `Class.forName` 探针同一个原则）。
  * 类注释里带上面第 2 节的全部证据（类名 + 方法 + 字节码偏移），方便以后复查。
* `SignalLinkItem#useOnBlock`：最先判断方速灯 → 发
  `message.mtrlte.signal_link.fangsu_unsupported`（金色 actionbar）并 `SUCCESS`（不再落到
  `findNode` 附近的「请右键轨道节点或信号灯」，也不再误选旁边的节点）。
* 语言：`assets/mtrlte/lang/zh_cn.json` / `en_us.json` 各加一条。

### 3.2 手持连接器时的节点轮廓

* 新文件 `v1.20.1/src/main/java/dev/js/mtrheadlight/signal/SignalNodeHighlight.java`
  * 触发：主手或副手是 `mtrlte:signal_linker`（`LteCoreItems.signalLinker()`）。
  * 节点来源：`mtr.client.ClientData.RAILS` 的键集合（客户端本来就有这张表，键 = 节点方块坐标），
    距离 `≤ 32` 格（平方比较），收集到 **200** 个就停。
  * 绘制：`RenderLayer.getLines()` + `WorldRenderer.drawBox(MatrixStack, VertexConsumer, x1..z2, r,g,b,a)`
    （1.20.1 的这个重载自己写 `vertex → color → normal → next`，已用 `javap` 核对，配 LINES 不会缺 normal）；
    顶点用世界坐标，`matrices.translate(-camera)` 抵消相机；塞完立刻
    `VertexConsumerProvider.Immediate#draw(RenderLayer.getLines())`。
  * 颜色：普通节点白 `(1,1,1,0.55)`；物品 NBT 里记着的待连接节点青 `(0.25,0.90,1.00,0.90)`。
  * 整个回调包在 `try/catch (Throwable)` 里：渲染异常只会让标记消失，不会黑屏。
* 注册：`v1.20.1/src/main/java/dev/js/mtrheadlight/RenderHooks.java`
  → `WorldRenderEvents.AFTER_TRANSLUCENT.register(SignalNodeHighlight::render)`（带 try/catch）。
* `SignalLinkItem` 新增只读辅助 `selectedNode(ItemStack)`（用 `getNbt()`，不 `getOrCreateNbt()`，
  客户端不会因为渲染写 NBT）；`appendTooltip` 多一行 `tooltip.mtrlte.signal_link.node_marks`。

---

## 4. 构建

```
JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"
/d/MC-MTR-JS/temp/gradle/gradle-8.14/bin/gradle build --offline     # 在 v1.20.1/
BUILD SUCCESSFUL
```

产物 `v1.20.1/build/libs/lte-1.0.0.jar`（684050 字节，md5 `feb314667ac593b8a18db2143bd62cf4`），已含
`dev/js/mtrheadlight/signal/FangsuSignalCompat.class` 与
`dev/js/mtrheadlight/signal/SignalNodeHighlight.class`；`mtrlte.fangsu.mixins.json` /
`mtrlte.mtr.mixins.json` / `lte.refmap.json` 均照旧。语言文件用 `JSON.parse` 校验过（300 键，无语法错误）。

同一份 jar 已拷到：

* `C:/Users/AD/Desktop/lte-1.0.0+1.20.1.jar`
* `C:/Users/AD/Desktop/PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.0+1.20.1.jar`

两处 md5 与 build 输出一致（拷前确认没有 Minecraft 进程，只有 Gradle daemon 与 PCL 启动器；未启动游戏）。


> 构建期顺带修了两处**不属于本任务**、但会把编译卡死的错误（其他并行任务的新文件）：
> `CustomPack.java:39` 的 javadoc 里 `{@code */...}` 提前闭合注释；`TrainNames.java:94`
> `TrainProperties#name` 的静态类型是 intermediary 名 `class_2561`，需 `(Text) (Object)` 中转。
